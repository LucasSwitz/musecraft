package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.util.math.*;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;

/** Explicit flight and mounted travel; ordinary walking uses BaritoneNavigation. */
final class SpecializedNavigation {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static String id, status="idle", reason;
    private static Vec3d target, waypoint, resolved;
    private static NavigationGoal goal;
    private static JsonObject request;
    private static NavigationRecovery recovery;
    private static NavigationStep movementStep;
    private static double totalPlanningMs;
    private static boolean flying,boating;
    private static Vec3d position(MinecraftClient c){return c.player.hasVehicle()?c.player.getVehicle().getPos():c.player.getPos();}
    private static boolean grounded(MinecraftClient c){return c.player.hasVehicle()?c.player.getVehicle().isOnGround():c.player.isOnGround();}
    private static double tolerance;
    private static long leaseUntil, progressAt, doorAt, dismountUntil;
    private static int replans,settledTicks,retargets;
    private static long retargetAt;
    private static NavigationMap map;
    private static NavigationPlanner planner;
    private static Map<String,Object> planning=Map.of();
    private static long applyMaxNanos,routeRevision;
    private static final LinkedHashSet<String> visited=new LinkedHashSet<>();
    private static List<NavigationStep> path = new ArrayList<>();

    static JsonElement start(MinecraftClient c, JsonObject p) {
        stop(c,"cancelled","replaced");
        boating=c.player.getVehicle() instanceof net.minecraft.entity.vehicle.BoatEntity;
        if(boating) {
            if(p.has("entityId")){boating=false;throw new IllegalStateException("dismount_before_entity_navigation");}
            return BoatNavigation.start(c,p);
        }
        p=p.deepCopy();
        if(p.has("entityId")) {
            var entity=c.world.getEntityById(p.get("entityId").getAsInt());
            if(entity==null||!entity.isAlive())throw new IllegalStateException("target_gone");
            if(entity==c.player)throw new IllegalArgumentException("cannot_approach_self");
            double range=p.has("distance")?p.get("distance").getAsDouble():2.5;
            if(!Double.isFinite(range)||range<1||range>8)throw new IllegalArgumentException("invalid_entity_distance");
            var action=new JsonObject();action.addProperty("entityId",entity.getId());
            action.addProperty("uuid",entity.getUuidAsString());action.addProperty("range",range);
            p.add("action",action);p.addProperty("goal","entity");
            p.addProperty("x",entity.getX());p.addProperty("y",entity.getY());p.addProperty("z",entity.getZ());
        }
        target=new Vec3d(p.get("x").getAsDouble(),p.get("y").getAsDouble(),p.get("z").getAsDouble());
        if(!Double.isFinite(target.x)||!Double.isFinite(target.y)||!Double.isFinite(target.z))throw new IllegalArgumentException("finite_position_required");
        flying=p.has("flying")&&p.get("flying").getAsBoolean();
        if(!flying&&c.player.getAbilities().flying&&!c.player.isOnGround()&&!valid(c,c.player.getPos(),false,false))throw new IllegalStateException("land_before_walking");
        if(flying&&!c.player.getAbilities().allowFlying)throw new IllegalStateException("flight_not_allowed");
        tolerance=p.has("tolerance")?p.get("tolerance").getAsDouble():.35;
        if(!Double.isFinite(tolerance)||tolerance<.15||tolerance>2)throw new IllegalArgumentException("invalid_tolerance");
        request=p.deepCopy();goal=new NavigationGoal(target,flying,request);resolved=null;movementStep=null;recovery=new NavigationRecovery();totalPlanningMs=0;
        map=NavigationMap.acquire(c,flying,position(c));map.refresh(c);
        planning=Map.of();applyMaxNanos=0;routeRevision=map.revision;
        id=UUID.randomUUID().toString();status="running";reason=null;replans=0;settledTicks=0;visited.clear();retargets=0;retargetAt=0;
        leaseUntil=System.currentTimeMillis()+5000;progressAt=System.currentTimeMillis();
        setFlight(c,flying);
        if(flying&&c.player.isOnGround())c.player.jump();
        return snapshot(c);
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(boating)return BoatNavigation.poll(c,p);
        if(id==null||!id.equals(p.get("id").getAsString()))throw new IllegalStateException("movement_identity_changed");
        if(status.equals("running"))leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static JsonElement snapshot(MinecraftClient c) {
        if(boating)return BoatNavigation.snapshot(c);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",id);out.put("status",status);out.put("reason",reason);out.put("replans",replans);
        out.put("requestedTarget",target==null?null:List.of(target.x,target.y,target.z));
        out.put("resolvedTarget",resolved==null?null:List.of(resolved.x,resolved.y,resolved.z));
        out.put("goalSatisfied",status.equals("completed")&&"arrived".equals(reason));
        out.put("totalPlanningMs",totalPlanningMs);
        if(status.equals("blocked")&&goal!=null&&c.player!=null&&c.world!=null&&Set.of("no_route_in_region","search_incomplete","movement_stalled","no_acceptable_destination").contains(reason))
            out.put("blocker",goal.blocker(c,position(c),reason));
        if(goal!=null&&goal.tracksEntity()) {
            out.put("targetEntity",Map.of("id",goal.entityId(),"uuid",goal.entityUuid(),"distance",goal.radius()));
            out.put("retargets",retargets);
        }
        if(recovery!=null)out.put("recovery",recovery.metrics());
        if(movementStep!=null)out.put("transition",movementStep.kind().name().toLowerCase(Locale.ROOT));
        out.put("planning",planner==null?planning:planner.metrics());out.put("maxTickWorkMs",applyMaxNanos/1e6);
        if(waypoint!=null)out.put("waypoint",List.of(waypoint.x,waypoint.y,waypoint.z));
        if(c.player!=null)out.put("pos",List.of(position(c).x,position(c).y,position(c).z));
        if(target!=null)out.put("target",List.of(target.x,target.y,target.z));
        return JSON.toJsonTree(out);
    }
    private static void setFlight(MinecraftClient c,boolean value) {
        if(c.player.getAbilities().flying!=value){c.player.getAbilities().flying=value;c.player.sendAbilitiesUpdate();}
    }
    static void dismount() {dismountUntil=System.currentTimeMillis()+5000;}
    static void stop(MinecraftClient c,String terminal,String why) {
        dismountUntil=0;
        BoatNavigation.stop(c,terminal,why);
        if(status.equals("running")){status=terminal;reason=why;}
        path.clear();waypoint=null;planner=null;
        if(c.player!=null){c.player.input.movementForward=0;c.player.input.movementSideways=0;c.player.input.jumping=false;c.player.input.sneaking=false;}
    }
    static boolean door(MinecraftClient c,BlockPos p) {
        var s=c.world.getBlockState(p);
        return (s.isIn(BlockTags.WOODEN_DOORS)||s.isIn(BlockTags.FENCE_GATES))&&s.contains(Properties.OPEN)&&!s.get(Properties.OPEN);
    }
    static boolean valid(MinecraftClient c,Vec3d feet,boolean fly,boolean allowDoors) {
        return fly?NavigationTerrain.empty(c,feet,allowDoors):NavigationTerrain.travelValid(c,feet,allowDoors);
    }
    static Vec3d feet(BlockPos p){return new Vec3d(p.getX()+.5,p.getY(),p.getZ()+.5);}
    static boolean clear(MinecraftClient c,Vec3d from,Vec3d to,boolean fly) {
        if(fly)return NavigationTerrain.sweep(c,from,to,false);
        if(NavigationTerrain.swimming(c,from)||NavigationTerrain.swimming(c,to)||NavigationTerrain.climbing(c,from)||NavigationTerrain.climbing(c,to))
            return NavigationTerrain.travelLine(c,from,to,false);
        return NavigationTerrain.walk(c,from,to,false);
    }
    /** Called after KeyboardInput reads the keyboard; no key bindings are synthesized. */
    public static void apply(Input input) {
        long begin=System.nanoTime();
        try {applyMovement(input);} finally {applyMaxNanos=Math.max(applyMaxNanos,System.nanoTime()-begin);}
    }
    private static void applyMovement(Input input) {
        var c=MinecraftClient.getInstance();
        // A one-shot input packet can be overwritten by vanilla's next tick
        // before the server observes it. Hold native sneak until the server's
        // passenger update confirms dismount, with the same bounded release.
        if(dismountUntil!=0) {
            input.movementForward=0;input.movementSideways=0;input.jumping=false;
            input.pressingForward=false;input.pressingBack=false;input.pressingLeft=false;input.pressingRight=false;
            input.sneaking=c.player!=null&&c.player.hasVehicle()&&System.currentTimeMillis()<dismountUntil;
            if(!input.sneaking)dismountUntil=0;
            else return;
        }
        if(boating) {
            if(c.player==null||c.world==null||c.currentScreen!=null){stop(c,"cancelled","client_not_ready");return;}
            BoatNavigation.apply(c,input);return;
        }
        if(!status.equals("running"))return;
        long now=System.currentTimeMillis();
        input.movementForward=0;input.movementSideways=0;input.jumping=false;input.sneaking=false;
        if(c.player==null||c.world==null||c.currentScreen!=null){stop(c,"cancelled","client_not_ready");return;}
        if(now>=leaseUntil){stop(c,"cancelled","movement_lease_expired");return;}
        if(goal.targetGone(c)){stop(c,"blocked","target_gone");return;}
        var moving=c.player.hasVehicle()?c.player.getVehicle():c.player;
        Vec3d here=position(c);
        if(goal.tracksEntity()) {
            target=goal.entityPosition(c);
            // Keep an in-flight search bounded and let it finish; restarting it
            // on every target tick would starve planning for a walking player.
            if(planner==null&&goal.requested.distanceTo(target)>.75&&now-retargetAt>=250) {
                goal=new NavigationGoal(target,flying,request);path.clear();waypoint=null;resolved=null;
                movementStep=null;retargetAt=now;retargets++;
            }
        }
        // onGround can still be true on the tick that slime launches us upward.
        // Require stable physical arrival, not one transient contact/apex sample.
        Vec3d currentVelocity=moving.getVelocity();
        // Surface swimming deliberately bobs to preserve air. A safe, stationary
        // horizontal arrival must not wait for zero vertical velocity in water.
        boolean afloat=moving.isTouchingWater()&&!c.player.isSubmergedInWater();
        boolean verticalSettled=afloat||(grounded(c)?currentVelocity.y<=.025:
            (flying||c.player.isClimbing())&&Math.abs(currentVelocity.y)<.025);
        boolean arrived=goal.satisfied(c,here,resolved,tolerance);
        if(arrived&&currentVelocity.horizontalLength()<.025&&verticalSettled)settledTicks++;
        else settledTicks=0;
        if(settledTicks>=3){stop(c,"completed","arrived");return;}
        if(goal.tracksEntity()&&arrived) {path.clear();waypoint=null;planner=null;resolved=here;return;}
        if(!flying&&!c.player.hasVehicle()&&resolved!=null&&currentVelocity.y<=0&&
            Math.hypot(here.x-resolved.x,here.z-resolved.z)<.7&&
            c.world.getBlockState(BlockPos.ofFloored(resolved.add(0,-.01,0))).isOf(net.minecraft.block.Blocks.SLIME_BLOCK))
            input.sneaking=true; // Vanilla sneak suppresses the landing bounce.
        if(recovery.stalledMs()>15000){stop(c,"blocked","movement_stalled");return;}
        setFlight(c,flying);
        NavigationMap currentMap=NavigationMap.current(map,c,flying)?map:NavigationMap.acquire(c,flying,here);
        if(currentMap!=map){map=currentMap;path.clear();waypoint=null;planner=null;routeRevision=map.revision;}
        if(routeRevision!=map.revision){
            boolean affected=map.affects(here,path,target)||planner!=null;
            recovery.changed(map);map.refresh(c);routeRevision=map.revision;
            if(affected){path.clear();waypoint=null;planner=null;resolved=null;goal=new NavigationGoal(target,flying,request);}
        }
        if(waypoint!=null&&here.distanceTo(waypoint)<.25&&(resolved==null||!waypoint.equals(resolved))) {
            if(!path.isEmpty())path.removeFirst();waypoint=null;recovery.advanced();
            if(path.isEmpty())recovery.section(here);
        }
        if(waypoint==null) {
            if(path.isEmpty()) {
                NavigationMap local=NavigationMap.acquire(c,flying,here);
                if(local!=map){map=local;planner=null;}
                map.refresh(c);
                if(planner==null||planner.stale()){planner=new NavigationPlanner(map,here,goal,flying,recovery,request.has("returnable")&&request.get("returnable").getAsBoolean());replans++;}
                planner.step(c);planning=planner.metrics();routeRevision=map.revision;
                if(!planner.done())return;
                totalPlanningMs+=((Number)planning.get("cpuMs")).doubleValue();
                path=new ArrayList<>(planner.result());String outcome=planner.reason();resolved=planner.resolved();planner=null;
                if(path.isEmpty()) {
                    if(goal.tracksEntity()&&goal.requested.distanceTo(target)>.75)return;
                    if(resolved!=null&&goal.satisfied(c,here,resolved,tolerance))return; // Next tick verifies settled motion.
                    stop(c,"blocked",outcome);return;
                }
            }
            movementStep=path.getFirst();waypoint=movementStep.to();
        }
        recovery.following(here,waypoint);
        if(recovery.retryDue()&&movementStep!=null) {
            if(!recovery.failed(movementStep)){stop(c,"blocked","movement_stalled");return;}
            path.clear();waypoint=null;planner=null;resolved=null;return;
        }

        // A half-block path cell can overlap a door in the adjacent block.
        // Open every closed door intersecting the player's proposed body, not
        // only the block containing its feet, before rejecting the waypoint.
        Box nextBody=NavigationTerrain.body(waypoint);
        Set<BlockPos> prerequisites=new LinkedHashSet<>(movementStep.doors());
        for(BlockPos next:BlockPos.iterate(BlockPos.ofFloored(nextBody.minX,nextBody.minY,nextBody.minZ),BlockPos.ofFloored(nextBody.maxX,nextBody.maxY,nextBody.maxZ)))prerequisites.add(next.toImmutable());
        for(BlockPos next:prerequisites) {
            if(!door(c,next))continue;
            if(now-doorAt>700) {
                try {c.interactionManager.interactBlock(c.player,Hand.MAIN_HAND,WorldActions.visibleFace(c,next,null));doorAt=now;}
                catch(IllegalStateException blocked) { /* The next route attempt may approach from another face. */ }
            }
            return;
        }
        if(!valid(c,waypoint,flying,false)) {if(!recovery.failed(movementStep)){stop(c,"blocked","movement_stalled");return;}path.clear();waypoint=null;resolved=null;return;}
        Vec3d delta=waypoint.subtract(here),velocity=moving.getVelocity();
        double horizontal=Math.hypot(delta.x,delta.z);
        // A proportional velocity target plus measured-velocity damping brakes before arrival.
        boolean water=moving.isTouchingWater();
        double speed=Math.min(flying?.45:water?.12:.22,horizontal*.3);
        double wantedX=horizontal>.001?delta.x/horizontal*speed:0;
        double wantedZ=horizontal>.001?delta.z/horizontal*speed:0;
        double acceleration=flying?c.player.getAbilities().getFlySpeed()*2:water?.02:.1;
        double drag=water?.8:.91;
        double x=(wantedX-velocity.x*drag)/acceleration,z=(wantedZ-velocity.z*drag)/acceleration;
        double inputMagnitude=Math.hypot(x,z);
        c.player.setSprinting(false);
        if(inputMagnitude>.01) {
            c.player.setYaw((float)Math.toDegrees(Math.atan2(-x,z)));
            input.movementForward=(float)Math.min(1,inputMagnitude);
        }
        if(flying) {
            double vertical=delta.y-velocity.y*4;
            input.jumping=vertical>.08;input.sneaking=vertical<-.08;
            if(c.player.isOnGround()&&delta.y>=0)c.player.jump();
        } else if(water)input.jumping=!c.player.hasVehicle();
        else if(c.player.isClimbing()) {
            input.jumping=delta.y>.05;
            input.sneaking=Math.abs(delta.y)<.05;
        } else input.jumping=c.player.isOnGround()&&movementStep.kind()==NavigationStep.Kind.JUMP&&(horizontal>.35||delta.y>.08);
        if(moving.horizontalCollision&&recovery.stalledMs()>1000){if(!recovery.failed(movementStep)){stop(c,"blocked","movement_stalled");return;}path.clear();waypoint=null;resolved=null;}
    }
}
