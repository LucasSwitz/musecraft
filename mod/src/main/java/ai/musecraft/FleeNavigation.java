package ai.musecraft;

import baritone.api.*;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.process.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.util.math.*;

/** Continuous escape with one native owner and upstream background path planning. */
final class FleeNavigation {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static IBaritone engine;
    private static String id,status="idle",phase="idle",reason;
    private static int revision,retargets;
    private static double desired,distance,maxTickMs;
    private static long leaseUntil,lastGoal;
    private static boolean active,safeToCancel;
    private static GoalRunAway goal;
    private static final Map<UUID,Threat> targets=new LinkedHashMap<>();
    private static List<BlockPos> anchors=List.of();
    private static final Map<Settings.Setting<?>,Object> saved=new LinkedHashMap<>();
    private static final class Threat {
        final int entityId;
        final UUID uuid;
        Vec3d position;
        boolean loaded=true;
        Threat(Entity entity){entityId=entity.getId();uuid=entity.getUuid();position=entity.getPos();}
    }

    private static List<Entity> resolve(MinecraftClient c,JsonObject p) {
        var ids=new LinkedHashMap<Integer,String>();
        if(p.has("entities"))for(var value:p.getAsJsonArray("entities")) {
            var entity=value.getAsJsonObject();ids.put(entity.get("entityId").getAsInt(),entity.get("uuid").getAsString());
        }else if(p.has("entityIds"))for(var value:p.getAsJsonArray("entityIds"))ids.put(value.getAsInt(),null);
        else if(p.has("entityId"))ids.put(p.get("entityId").getAsInt(),null);
        if(ids.isEmpty()||ids.size()>32)throw new IllegalArgumentException("flee_requires_1_to_32_entities");
        var result=new ArrayList<Entity>();
        for(var entry:ids.entrySet()) {
            var entity=c.world.getEntityById(entry.getKey());
            if(entity==null||!entity.isAlive())throw new IllegalStateException("target_gone:"+entry.getKey());
            if(entry.getValue()!=null&&!entity.getUuidAsString().equals(entry.getValue()))throw new IllegalStateException("entity_identity_changed");
            if(entity==c.player)throw new IllegalArgumentException("cannot_flee_self");
            result.add(entity);
        }
        return result;
    }
    static JsonElement check(MinecraftClient c,JsonObject p) {
        if(c.player.hasVehicle())throw new IllegalStateException("dismount_before_fleeing");
        if(c.player.getAbilities().flying)throw new IllegalStateException("land_before_fleeing");
        var entities=resolve(c,p);
        var out=new LinkedHashMap<String,Object>();
        out.put("entities",entities.stream().map(e->Map.of("entityId",e.getId(),"uuid",e.getUuidAsString())).toList());
        if(p.has("distance")) {
            double range=p.get("distance").getAsDouble();
            if(!Double.isFinite(range)||range<8||range>64)throw new IllegalArgumentException("distance_must_be_8_to_64");
            out.put("distance",range);
        }
        return JSON.toJsonTree(out);
    }
    static JsonElement add(MinecraftClient c,JsonObject p) {
        if(!status.equals("running")||!Objects.equals(id,p.get("id").getAsString()))throw new IllegalStateException("flee_not_running");
        check(c,p);
        var additions=resolve(c,p);
        long added=additions.stream().filter(e->!targets.containsKey(e.getUuid())).count();
        if(targets.size()+added>32)throw new IllegalArgumentException("flee_target_limit");
        // Validation is atomic: an unavailable addition never drops existing threats.
        for(var entity:additions)targets.put(entity.getUuid(),new Threat(entity));
        if(p.has("distance"))desired=p.get("distance").getAsDouble();
        goal=null;revision++;
        leaseUntil=System.currentTimeMillis()+5000;
        tick(c);return snapshot(c);
    }

    private static <T> void setting(Settings.Setting<T> setting,T value) {
        saved.put(setting,setting.value);setting.value=value;
    }
    private static <T> void restore(Settings.Setting<T> setting,Object value) {
        setting.value=setting.getValueClass().cast(value);
    }
    static JsonElement start(MinecraftClient c,JsonObject p) {
        check(c,p);
        var entities=resolve(c,p);
        desired=p.has("distance")?p.get("distance").getAsDouble():40;
        if(!Double.isFinite(desired)||desired<8||desired>64)throw new IllegalArgumentException("distance_must_be_8_to_64");
        if(c.player.getAbilities().flying)throw new IllegalStateException("land_before_fleeing");
        if(engine==null) {
            engine=BaritoneNavigation.engine();
            engine.getPathingControlManager().registerProcess(new Process());
        }
        var s=BaritoneAPI.getSettings();
        setting(s.allowParkour,false);setting(s.allowSprint,true);setting(s.maxFallHeightNoWater,1);
        setting(s.allowBreakAnyway,List.of());setting(s.avoidance,true);
        setting(s.mobAvoidanceRadius,6);setting(s.mobAvoidanceCoefficient,4.0);
        id=UUID.randomUUID().toString();targets.clear();
        for(var entity:entities)targets.put(entity.getUuid(),new Threat(entity));
        status="running";phase="retreating";reason=null;revision=0;retargets=0;lastGoal=0;
        maxTickMs=0;goal=null;anchors=List.of();active=false;safeToCancel=false;
        leaseUntil=System.currentTimeMillis()+5000;
        tick(c);return snapshot(c);
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(id==null||!id.equals(p.get("id").getAsString()))throw new IllegalStateException("movement_identity_changed");
        if(status.equals("running"))leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static void stop(MinecraftClient c,String terminal,String why) {
        if(!status.equals("running"))return;
        status=terminal;reason=why;phase="terminal";release();
        saved.forEach(FleeNavigation::restore);saved.clear();
    }
    private static void release() {
        active=false;
        if(engine!=null){engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().clearAllKeys();}
    }
    static void tick(MinecraftClient c) {
        if(!status.equals("running"))return;
        long start=System.nanoTime();
        try{advance(c);}finally{maxTickMs=Math.max(maxTickMs,(System.nanoTime()-start)/1e6);}
    }
    private static double gap(Entity a,Entity b){return Math.hypot(a.getX()-b.getX(),a.getZ()-b.getZ());}
    private static void advance(MinecraftClient c) {
        if(c.player==null||c.world==null||c.currentScreen!=null||!c.player.isAlive()){stop(c,"cancelled","client_not_ready");return;}
        long now=System.currentTimeMillis();
        if(now>=leaseUntil){stop(c,"cancelled","movement_lease_expired");return;}
        for(var iterator=targets.values().iterator();iterator.hasNext();) {
            var threat=iterator.next();var entity=c.world.getEntityById(threat.entityId);
            threat.loaded=entity!=null&&entity.getUuid().equals(threat.uuid);
            if(threat.loaded) {
                if(!entity.isAlive()){iterator.remove();goal=null;revision++;continue;}
                threat.position=entity.getPos();
            }
        }
        if(targets.isEmpty()){stop(c,"completed","threats_dead");return;}
        distance=targets.values().stream().mapToDouble(t->Math.hypot(t.position.x-c.player.getX(),t.position.z-c.player.getZ())).min().orElseThrow();
        var nearby=c.world.getOtherEntities(c.player,c.player.getBoundingBox().expand(12),
            e->e instanceof Monster&&e.isAlive()&&Math.abs(e.getY()-c.player.getY())<5&&ThreatPerception.lineOfSight(c,e));
        boolean otherDanger=nearby.stream().anyMatch(e->!targets.containsKey(e.getUuid())&&gap(e,c.player)<8);
        boolean landed=c.player.isOnGround()||c.player.isTouchingWater()||c.player.isClimbing();
        if(distance>=desired&&!otherDanger&&landed&&(!active||safeToCancel)) {
            if(!phase.equals("holding")){phase="holding";revision++;release();}
            return;
        }
        if(phase.equals("holding")) {
            if(distance>desired-4&&!otherDanger)return;
            phase="retreating";revision++;
        }
        var feet=engine.getPlayerContext().playerFeet();
        boolean reached=goal!=null&&goal.isInGoal(feet.getX(),feet.getY(),feet.getZ());
        var sources=new ArrayList<BlockPos>();
        for(var threat:targets.values())sources.add(BlockPos.ofFloored(threat.position));
        for(var mob:nearby)if(!targets.containsKey(mob.getUuid()))sources.add(mob.getBlockPos());
        boolean changed=sources.size()!=anchors.size();
        for(int i=0;!changed&&i<sources.size();i++)changed=sources.get(i).getSquaredDistance(anchors.get(i))>=4;
        if(goal==null||!active||reached||now-lastGoal>=750&&changed) {
            double nearest=sources.stream().mapToDouble(p->Math.hypot(p.getX()-c.player.getX(),p.getZ()-c.player.getZ())).min().orElse(distance);
            // Short goals allow the existing executor to look ahead. Revalidation
            // preserves a valid path rather than cancelling it on every sample.
            goal=new GoalRunAway(Math.min(desired+1,nearest+8),sources.toArray(BlockPos[]::new));
            anchors=List.copyOf(sources);lastGoal=now;retargets++;active=true;
        }
    }
    static JsonElement snapshot(MinecraftClient c) {
        var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("status",status);out.put("reason",reason);out.put("phase",phase);
        out.put("backend","baritone");out.put("kind","flee");
        var first=targets.values().stream().findFirst().orElse(null);
        out.put("entityId",first==null?null:first.entityId);out.put("uuid",first==null?null:first.uuid);
        out.put("targets",targets.values().stream().map(t->Map.of("entityId",t.entityId,"uuid",t.uuid,
            "position",List.of(t.position.x,t.position.y,t.position.z),"loaded",t.loaded,
            "distance",c.player==null?0:Math.hypot(t.position.x-c.player.getX(),t.position.z-c.player.getZ()))).toList());
        out.put("distance",distance);out.put("safeDistance",desired);out.put("phaseRevision",revision);
        out.put("retargets",retargets);out.put("maxTickWorkMs",maxTickMs);
        if(c.player!=null)out.put("pos",List.of(c.player.getX(),c.player.getY(),c.player.getZ()));
        return JSON.toJsonTree(out);
    }
    private static final class Process implements IBaritoneProcess {
        public boolean isActive(){return active;}
        public boolean isTemporary(){return false;}
        public double priority(){return 10;}
        public String displayName0(){return "Musecraft flee";}
        public void onLostControl(){active=false;}
        public PathingCommand onTick(boolean failed,boolean safe) {
            safeToCancel=safe;
            if(failed) {
                stop(MinecraftClient.getInstance(),"blocked","escape_route_unavailable");
                return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
    }
}
