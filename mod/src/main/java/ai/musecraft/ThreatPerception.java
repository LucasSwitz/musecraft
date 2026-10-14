package ai.musecraft;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Forward perception only. Threat alerts do not choose or cancel gameplay actions. */
final class ThreatPerception {
    static final int RANGE = 64;
    static final int FIELD_OF_VIEW = 120;
    private static final double MIN_DOT = Math.cos(Math.toRadians(FIELD_OF_VIEW / 2.0));
    private static final Map<UUID,Track> tracks=new HashMap<>();
    private static Object world;
    private record Track(Vec3d pos,long time,long closingSince) {}

    static Map<String,Object> snapshot(MinecraftClient c) {
        if(world!=c.world){world=c.world;tracks.clear();}
        long now=System.currentTimeMillis();
        tracks.entrySet().removeIf(e->now-e.getValue().time()>10000);
        var eye = c.player.getEyePos();
        var forward = c.player.getRotationVec(1.0F);
        var visible = new ArrayList<Map<String,Object>>();
        // Independent of the general entity list's limit: nearby players/items
        // cannot crowd a visible threat out of the event source.
        for (var entity : c.world.getOtherEntities(c.player, c.player.getBoundingBox().expand(RANGE),
                e -> e instanceof Monster && e.isAlive() && !e.isInvisibleTo(c.player)
                    && e.squaredDistanceTo(c.player) <= RANGE * RANGE)) {
            if (!visible(c, entity, eye, forward)) continue;
            var row = new LinkedHashMap<String,Object>(WorldActions.entity(entity));
            row.put("distance", Math.sqrt(entity.squaredDistanceTo(c.player)));
            row.put("lineOfSight", true);
            row.put("inFieldOfView", true);
            row.put("threat",assess(c,entity,now));
            visible.add(row);
        }
        visible.sort(Comparator.<Map<String,Object>>comparingDouble(e -> ((Number)e.get("distance")).doubleValue())
            .thenComparing(e -> (String)e.get("uuid")));
        return Map.of("range", RANGE, "fieldOfViewDegrees", FIELD_OF_VIEW,
            "entities", visible.subList(0, Math.min(8, visible.size())), "omitted", Math.max(0, visible.size()-8));
    }

    private static Map<String,Object> assess(MinecraftClient c,Entity entity,long now) {
        double distance=entity.distanceTo(c.player);
        var prior=tracks.get(entity.getUuid());
        long since=prior==null?0:prior.closingSince();
        // Multiple RPC readers can observe one game tick. They must not reset
        // pursuit simply because the entity has not advanced between reads.
        if(prior==null||now-prior.time()>=150) {
            boolean closing=false;
            if(prior!=null&&now-prior.time()<1500) {
                var toward=c.player.getPos().subtract(entity.getPos()).normalize();
                closing=entity.getPos().subtract(prior.pos()).dotProduct(toward)/(now-prior.time())*1000>.4;
            }
            since=closing?(since==0?now:since):0;
            tracks.put(entity.getUuid(),new Track(entity.getPos(),now,since));
        }
        boolean pursuing=since!=0&&now-since>=600&&distance<=16;
        boolean attacking=entity instanceof MobEntity mob&&mob.isAttacking();
        boolean swelling=entity instanceof CreeperEntity creeper&&creeper.getFuseSpeed()>0;
        var evidence=new ArrayList<String>();
        if(pursuing)evidence.add("moving_toward_player");
        if(attacking)evidence.add("attack_state_active");
        if(distance<=10)evidence.add("dangerous_proximity");
        if(swelling)evidence.add("creeper_fuse_active");
        int level=swelling||distance<=4?3:distance<=10||pursuing||attacking&&distance<=24?2:0;
        // Vanilla clients do not receive a general server-selected mob target.
        return Map.of("level",level,"severity",level==3?"immediate":level==2?"danger":"sighting",
            "evidence",evidence,"targetingPlayer","unconfirmed","pursuitInferred",pursuing);
    }

    static boolean lineOfSight(MinecraftClient c,Entity entity) {
        return visible(c,entity,c.player.getEyePos(),null);
    }

    private static boolean visible(MinecraftClient c, Entity entity, Vec3d eye, Vec3d forward) {
        var box = entity.getBoundingBox();
        for (var point : List.of(entity.getEyePos(), box.getCenter(),
                new Vec3d(entity.getX(), box.minY + box.getLengthY() * .25, entity.getZ()))) {
            var delta = point.subtract(eye);
            if (forward!=null && delta.lengthSquared() > .0001 && forward.dotProduct(delta.normalize()) < MIN_DOT) continue;
            var ray = c.world.raycast(new RaycastContext(eye, point,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, c.player));
            if (ray.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
