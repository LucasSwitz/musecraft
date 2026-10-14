package ai.musecraft;

import baritone.api.*;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.process.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.registry.Registries;
import net.minecraft.block.Block;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.*;

/** Action-scoped adapter to upstream Baritone's background planner and movement executor. */
public final class BaritoneNavigation {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static IBaritone engine;
    private static final Process PROCESS=new Process();
    private static final NavigationExecution execution=new NavigationExecution();
    private static String id,status="idle",reason,phase="idle";
    private static JsonObject request;
    private static NavigationGoal goal;
    private static BaritoneGoal.Builder builder;
    private static BaritoneGoal routeGoal;
    private static Vec3d target,resolved,initial;
    private static double tolerance,goalWorkMs,maxTickWorkMs,maxPathCost,plannedPathCost;
    private static long leaseUntil,startedAt,firstMotionAt,searchStartedAt,searchWallMs,lastRetarget;
    private static int searches,retargets,settled;
    private static double alignmentBest;
    private static int alignmentStalls;
    private static boolean active,returnable,safeToCancel,clearanceApproach,clearanceAvailable;
    private static final Set<BlockPos> rejectedDestinations=new HashSet<>();
    private static String lastPathEvent;
    private static BlockVolume excavationArea;
    private static BlockPos refusedExcavation;
    private static List<Block> defaultAvoidBreaking;
    private static List<Block> defaultDisallowBreaking;
    public static boolean isRunning(){return running();}
    static boolean running(){return status.equals("running");}
    static boolean mayPlace(BlockPos pos){return RoutePlacement.mayPlace(pos);}

    /** Fence the real breaking admission, including terrain changed after planning. */
    public static boolean rejectExcavation(BlockPos pos) {
        if(!status.equals("running")||(!active&&!phase.equals("clearing")))return false;
        var c=MinecraftClient.getInstance();
        if(excavationArea!=null&&!excavationArea.contains(pos)) {
            refusedExcavation=pos.toImmutable();stop(c,"blocked","access_outside_gather_area");return true;
        }
        // The mining action owns harvest-tool selection and target accounting.
        // Navigation may expose its target but never consume it as clearance.
        if(goal.excavatingAction()&&request.getAsJsonObject("action").has("mining")&&pos.equals(BlockPos.ofFloored(target))) {
            boolean reached=goal.accepts(c,c.player.getPos());
            stop(c,reached?"completed":"blocked",reached?"arrived":"mining_target_exposed");return true;
        }
        if(goal.excavatingAction()&&!(request.has("use_hands")&&request.get("use_hands").getAsBoolean())) {
            var block=c.world.getBlockState(pos);boolean suitable=!block.isToolRequired();
            for(int i=0;i<36&&!suitable;i++)suitable=c.player.getInventory().getStack(i).isSuitableFor(block);
            if(!suitable){refusedExcavation=pos.toImmutable();stop(c,"blocked","missing_harvest_tool");return true;}
        }
        return false;
    }

    private static void initialize() {
        if(engine!=null)return;
        engine=BaritoneAPI.getProvider().getPrimaryBaritone();
        engine.getPathingControlManager().registerProcess(PROCESS);
        engine.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override public void onPathEvent(PathEvent event) {
                if(!active)return;
                lastPathEvent=event.name();
                if(event==PathEvent.CALC_STARTED||event==PathEvent.NEXT_SEGMENT_CALC_STARTED) {
                    searches++;searchStartedAt=System.currentTimeMillis();
                }else if(event==PathEvent.CALC_FINISHED_NOW_EXECUTING||event==PathEvent.NEXT_SEGMENT_CALC_FINISHED||event==PathEvent.CALC_FAILED) {
                    if(searchStartedAt!=0)searchWallMs+=System.currentTimeMillis()-searchStartedAt;
                    searchStartedAt=0;
                }
            }
        });
        var s=BaritoneAPI.getSettings();
        defaultAvoidBreaking=List.copyOf(s.blocksToAvoidBreaking.value);
        defaultDisallowBreaking=List.copyOf(s.blocksToDisallowBreaking.value);
        s.allowBreak.value=false;s.allowBreakAnyway.value=List.of();s.allowPlace.value=false;
        s.allowParkour.value=true;s.allowParkourPlace.value=false;s.allowWaterBucketFall.value=false;
        s.allowInventory.value=false;s.autoTool.value=false;s.allowVines.value=true;
        s.chatControl.value=false;s.chatControlAnyway.value=false;s.prefixControl.value=false;
        s.renderPath.value=false;s.renderGoal.value=false;s.notificationOnPathComplete.value=false;
        s.chatDebug.value=false;s.freeLook.value=false;
    }
    static IBaritone engine() {initialize();return engine;}
    static JsonElement start(MinecraftClient c,JsonObject params) {
        initialize();stop(c,"cancelled","replaced");
        BaritoneProtection.start(c);
        request=params.deepCopy();
        WaterDescent.resolve(c,request);
        excavationArea=null;refusedExcavation=null;
        if(request.has("area")) {
            var area=request.getAsJsonObject("area");var center=area.getAsJsonArray("near");
            excavationArea=BlockVolume.around(new Vec3d(center.get(0).getAsDouble(),center.get(1).getAsDouble(),center.get(2).getAsDouble()),area.get("radius").getAsInt());
        }
        var protectedBlocks=new HashSet<String>();
        if(request.has("protectedBlocks"))for(var block:request.getAsJsonArray("protectedBlocks"))protectedBlocks.add(block.getAsString());
        var disallowBreaking=new HashSet<>(defaultDisallowBreaking);
        disallowBreaking.addAll(defaultAvoidBreaking);
        for(var name:protectedBlocks) {
            var blockId=net.minecraft.util.Identifier.tryParse(name);
            if(blockId==null||!Registries.BLOCK.containsId(blockId))throw new IllegalArgumentException("unknown_protected_block");
            disallowBreaking.add(Registries.BLOCK.get(blockId));
        }
        BaritoneAPI.getSettings().blocksToDisallowBreaking.value=List.copyOf(disallowBreaking);
        boolean clearVegetation=request.has("clearVegetation")&&request.get("clearVegetation").getAsBoolean();
        BaritoneAPI.getSettings().allowBreakAnyway.value=clearVegetation?Registries.BLOCK.stream()
            .filter(block->block.getDefaultState().isIn(BlockTags.LEAVES)&&!protectedBlocks.contains(Registries.BLOCK.getId(block).toString()))
            .toList():List.of();
        if(request.has("entityId")) {
            var entity=c.world.getEntityById(request.get("entityId").getAsInt());
            if(entity==null||!entity.isAlive())throw new IllegalStateException("target_gone");
            if(entity==c.player)throw new IllegalArgumentException("cannot_approach_self");
            double range=request.has("distance")?request.get("distance").getAsDouble():2.5;
            if(!Double.isFinite(range)||range<1||range>8)throw new IllegalArgumentException("invalid_entity_distance");
            var action=new JsonObject();action.addProperty("entityId",entity.getId());action.addProperty("uuid",entity.getUuidAsString());action.addProperty("range",range);
            request.add("action",action);request.addProperty("goal","entity");
            request.addProperty("x",entity.getX());request.addProperty("y",entity.getY());request.addProperty("z",entity.getZ());
        }
        target=new Vec3d(request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble());
        if(!Double.isFinite(target.x)||!Double.isFinite(target.y)||!Double.isFinite(target.z))throw new IllegalArgumentException("finite_position_required");
        tolerance=request.has("tolerance")?request.get("tolerance").getAsDouble():.35;
        if(!Double.isFinite(tolerance)||tolerance<.15||tolerance>2)throw new IllegalArgumentException("invalid_tolerance");
        if(c.player.getAbilities().flying&&!c.player.isOnGround()&&!Navigation.valid(c,c.player.getPos(),false,false))throw new IllegalStateException("land_before_walking");
        if(c.player.getAbilities().flying){c.player.getAbilities().flying=false;c.player.sendAbilitiesUpdate();}
        id=UUID.randomUUID().toString();status="running";reason=null;phase="goals";
        initial=c.player.getPos();resolved=null;routeGoal=null;startedAt=System.currentTimeMillis();leaseUntil=startedAt+5000;
        firstMotionAt=0;searchStartedAt=0;searchWallMs=0;searches=0;retargets=0;settled=0;lastRetarget=0;
        rejectedDestinations.clear();goalWorkMs=0;maxTickWorkMs=0;lastPathEvent=null;safeToCancel=false;clearanceApproach=false;clearanceAvailable=clearVegetation;
        maxPathCost=request.has("maxPathCost")?request.get("maxPathCost").getAsDouble():Double.POSITIVE_INFINITY;
        if(Double.isNaN(maxPathCost)||maxPathCost<=0)throw new IllegalArgumentException("invalid_path_cost");
        plannedPathCost=0;execution.reset(c);
        returnable=request.has("returnable")&&request.get("returnable").getAsBoolean();
        BaritoneAPI.getSettings().maxFallHeightNoWater.value=returnable?1:3;
        goal=new NavigationGoal(target,false,request);builder=newBuilder();
        if(goal.excavatingAction()&&request.getAsJsonObject("action").has("mining")&&c.player.getEyePos().distanceTo(Vec3d.ofCenter(BlockPos.ofFloored(target)))<=6) {
            // Body paths cannot represent mining through a one-block window.
            // Compare a nearby, observed clearance against the planned route;
            // hand it back to mining only when walking is more expensive.
            var access=MiningAccess.next(c,request);
            if(Boolean.TRUE.equals(access.get("obstruction"))&&Boolean.TRUE.equals(access.get("reachable"))&&access.get("localPathCost") instanceof Number cost&&cost.doubleValue()>0) {
                var pos=(List<?>)access.get("target");
                var block=new BlockPos(((Number)pos.get(0)).intValue(),((Number)pos.get(1)).intValue(),((Number)pos.get(2)).intValue());
                if(excavationArea==null||excavationArea.contains(block))maxPathCost=Math.min(maxPathCost,cost.doubleValue());
            }
        }
        var settings=BaritoneAPI.getSettings();
        boolean excavate=goal.excavatingAction()||request.has("allowBreak")&&request.get("allowBreak").getAsBoolean();
        settings.allowBreak.value=excavate;settings.autoTool.value=excavate;settings.allowInventory.value=excavate;
        RoutePlacement.start(c,request,target);
        return snapshot(c);
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(id==null||!id.equals(p.get("id").getAsString()))throw new IllegalStateException("movement_identity_changed");
        if(status.equals("running"))leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static void stop(MinecraftClient c,String terminal,String why) {
        if(!status.equals("running"))return;
        if(terminal.equals("completed")&&RoutePlacement.pending())return;
        status=terminal;reason=why;phase="terminal";execution.stop(c);release();
        var settings=BaritoneAPI.getSettings();
        settings.allowBreak.value=false;settings.autoTool.value=false;settings.allowInventory.value=false;
        settings.blocksToDisallowBreaking.value=defaultDisallowBreaking;
        WaterDescent.clear();RoutePlacement.stop();
        if(c.player!=null){c.player.input.movementForward=0;c.player.input.movementSideways=0;c.player.input.jumping=false;c.player.input.sneaking=false;}
    }
    private static void release() {
        active=false;
        if(engine!=null) {
            // A replaced action must not retain an old falling/jumping path or worker result.
            engine.getPathingBehavior().forceCancel();
            engine.getInputOverrideHandler().clearAllKeys();
        }
    }
    static void tick(MinecraftClient c) {
        if(!status.equals("running"))return;
        long begin=System.nanoTime();
        try{advance(c);}finally{maxTickWorkMs=Math.max(maxTickWorkMs,(System.nanoTime()-begin)/1e6);}
    }
    private static BaritoneGoal.Builder newBuilder() {
        return new BaritoneGoal.Builder(goal,clearanceAvailable?new HashSet<>(BaritoneAPI.getSettings().allowBreakAnyway.value):Set.of());
    }
    private static boolean recover(MinecraftClient c) {
        var clearance=execution.clearance(c,pos->(BaritoneAPI.getSettings().allowBreak.value
                ||BaritoneAPI.getSettings().allowBreakAnyway.value.contains(c.world.getBlockState(pos).getBlock()))
            &&(excavationArea==null||excavationArea.contains(pos))
            &&!BaritoneAPI.getSettings().blocksToAvoidBreaking.value.contains(c.world.getBlockState(pos).getBlock())
            &&!(goal.excavatingAction()&&request.getAsJsonObject("action").has("mining")&&pos.equals(BlockPos.ofFloored(target))));
        if(clearance==null)return false;
        release();phase="clearing";
        try {execution.begin(c,clearance,!goal.excavatingAction()||request.has("use_hands")&&request.get("use_hands").getAsBoolean());}
        catch(IllegalStateException unavailable){execution.failed(unavailable.getMessage());stop(c,"blocked","movement_stalled");}
        return true;
    }
    static void changed(BlockPos pos) {
        if(status.equals("running"))execution.changed(pos,MinecraftClient.getInstance().world.getBlockState(pos));
        if(!status.equals("running")||target==null||clearanceApproach||pos.getSquaredDistance(target)>100)return;
        if(goal.volumeGoal()) {
            // The arrival region expresses user intent, not current occupancy.
            // Baritone already observes excavated terrain. Preserve the region
            // and refusals; only final live alignment needs fresh geometry.
            goal.invalidateGeometry();
            if(phase.equals("aligning")){builder=newBuilder();phase="goals";}
            return;
        }
        goal=new NavigationGoal(target,false,request);builder=newBuilder();
        if(phase.equals("aligning"))phase="goals";
    }
    private static void advance(MinecraftClient c) {
        if(c.player==null||c.world==null||c.currentScreen!=null){stop(c,"cancelled","client_not_ready");return;}
        long now=System.currentTimeMillis();var here=c.player.getPos();
        if(now>=leaseUntil){stop(c,"cancelled","movement_lease_expired");return;}
        execution.observe(c,engine);
        if(RoutePlacement.refusal()!=null){stop(c,"blocked",RoutePlacement.refusal());return;}
        if(BaritoneProtection.refusal()!=null){stop(c,"blocked",BaritoneProtection.refusal());return;}
        if(c.player.isOnGround()&&!WaterDescent.valid(c)){stop(c,"blocked","descent_water_landing_changed");return;}
        if(goal.targetGone(c)) {
            // Pickup can remove the target in the middle of a gap jump. Finish
            // the current native movement before handing control back; explicit
            // action replacement still revokes ownership immediately.
            if(active&&!safeLanding(c))return;
            stop(c,"blocked","target_gone");return;
        }
        if(phase.equals("clearing")) {
            String result=execution.poll(c);
            if(result.equals("blocked"))stop(c,"blocked","movement_stalled");
            else if(result.equals("completed")) {
                if(goal.reached(c,here,tolerance)){active=false;phase="pathing";}
                else if(!recover(c)){active=true;phase="pathing";}
            }
            return;
        }
        if(firstMotionAt==0&&here.distanceTo(initial)>.1)firstMotionAt=now;
        if(goal.tracksEntity()) {
            target=goal.entityPosition(c);
            if(goal.requested.distanceTo(target)>1&&now-lastRetarget>=500&&builder==null) {
                goal=new NavigationGoal(target,false,request);builder=newBuilder();lastRetarget=now;retargets++;
            }
        }
        var feet=engine.getPlayerContext().playerFeet();
        boolean reachedCell=routeGoal!=null&&routeGoal.isInGoal(feet.getX(),feet.getY(),feet.getZ());
        if(clearanceApproach&&reachedCell&&safeLanding(c)) {
            release();clearanceApproach=false;routeGoal=null;
            goal=new NavigationGoal(target,false,request);builder=newBuilder();phase="goals";return;
        }
        if(!clearanceApproach&&goal.reached(c,here,tolerance)&&(!active||(reachedCell||goal.kind.equals("near"))&&safeLanding(c))) {
            if(active)release();
            phase="settling";resolved=here;
            var velocity=c.player.getVelocity();
            boolean verticalSettled=c.player.isTouchingWater()&&!c.player.isSubmergedInWater()
                ||c.player.isOnGround()&&velocity.y<=.025
                ||c.player.isClimbing()&&Math.abs(velocity.y)<.025;
            if(velocity.horizontalLength()<.04&&verticalSettled)settled++;else settled=0;
            if(settled>=3)stop(c,"completed","arrived");
            return;
        }
        settled=0;
        if(phase.equals("settling")){active=true;phase="pathing";}
        if(builder!=null) {
            long begin=System.nanoTime();builder.step(c);goalWorkMs+=(System.nanoTime()-begin)/1e6;
            if(!builder.done())return;
            routeGoal=builder.result();clearanceApproach=builder.clearance();
            // Excavation changes nearby blocks and rebuilds the immutable goal.
            // Keep this action's refused endpoints excluded across those rebuilds.
            if(routeGoal.coordinate())routeGoal=routeGoal.excluding(rejectedDestinations);
            if(clearanceApproach)clearanceAvailable=false;
            builder=null;
            if(routeGoal.size()==0){stop(c,"blocked",goal.failure()!=null?goal.failure():
                rejectedDestinations.isEmpty()?"no_acceptable_destination":"destination_alignment_unavailable");return;}
            active=true;phase="pathing";
        }
        if(phase.equals("aligning")) {
            double gap=here.distanceTo(resolved);
            if(gap<alignmentBest-.01){alignmentBest=gap;alignmentStalls=0;}
            else if(++alignmentStalls>=40)stop(c,"blocked","movement_stalled");
            return;
        }
        if(active&&execution.needsRecovery()&&(safeToCancel||execution.stalled())) {
            if(!recover(c))stop(c,"blocked","movement_stalled");
            return;
        }
        if(!clearanceApproach&&reachedCell&&safeLanding(c)) {
            resolved=routeGoal.nearest(feet,here);
            if(resolved!=null&&here.distanceTo(resolved)<1.5&&Navigation.clear(c,here,resolved,false)) {
                release();phase="aligning";alignmentBest=here.distanceTo(resolved);alignmentStalls=0;
            }else if(routeGoal.coordinate()) {
                rejectedDestinations.add(feet.toImmutable());routeGoal=routeGoal.excluding(rejectedDestinations);
                if(routeGoal.size()==0)stop(c,"blocked","destination_alignment_unavailable");
            }
        }
    }
    private static boolean safeLanding(MinecraftClient c) {
        return safeToCancel&&(c.player.isOnGround()&&c.player.getVelocity().y<=.025
            ||c.player.isTouchingWater()||c.player.isClimbing());
    }
    /** Only a final within-cell alignment; all route search/execution belongs to Baritone. */
    static void align(Input input) {
        if(!status.equals("running")||(!phase.equals("aligning")&&!phase.equals("settling")))return;
        input.movementForward=0;input.movementSideways=0;input.jumping=false;input.sneaking=false;
        if(!phase.equals("aligning")||resolved==null)return;
        var c=MinecraftClient.getInstance();if(c.player==null)return;
        var delta=resolved.subtract(c.player.getPos());var velocity=c.player.getVelocity();
        double gap=delta.horizontalLength(),speed=Math.min(.15,gap*.35),drag=c.player.isTouchingWater()?.8:.91;
        double x=(gap>.001?delta.x/gap*speed:0)-velocity.x*drag,z=(gap>.001?delta.z/gap*speed:0)-velocity.z*drag;
        c.player.setSprinting(false);
        if(Math.hypot(x,z)>.001){c.player.setYaw((float)Math.toDegrees(Math.atan2(-x,z)));input.movementForward=(float)Math.min(1,Math.hypot(x,z)/.1);}
        input.jumping=c.player.isTouchingWater();
    }
    static JsonElement snapshot(MinecraftClient c) {
        var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("status",status);out.put("reason",reason);out.put("backend","baritone");out.put("backendVersion","1.11.3");out.put("phase",phase);
        out.put("goalSatisfied",status.equals("completed")&&"arrived".equals(reason));out.put("replans",searches);out.put("retargets",retargets);
        out.put("requestedTarget",goal==null?null:xyz(goal.requested));out.put("target",xyz(target));out.put("resolvedTarget",xyz(resolved));
        if(request!=null&&request.has("descentFrom")){out.put("requestedTarget",request.get("descentFrom"));out.put("waterLanding",xyz(target));}
        if(c.player!=null)out.put("pos",xyz(c.player.getPos()));
        out.put("firstMotionMs",firstMotionAt==0?null:firstMotionAt-startedAt);out.put("maxTickWorkMs",maxTickWorkMs);
        out.put("planning",Map.of("backend","baritone","goalWorkMs",goalWorkMs,"searchWallMs",searchWallMs,"goalCells",routeGoal==null?0:routeGoal.size(),"candidateCells",routeGoal==null?0:routeGoal.candidateCount(),"rejectedDestinations",rejectedDestinations.size(),"searchAttempted",searches>0));
        out.put("lastPathEvent",lastPathEvent);
        out.put("execution",execution.snapshot(c,engine));
        if(!RoutePlacement.snapshot().isEmpty())out.put("route",RoutePlacement.snapshot());
        if(Double.isFinite(maxPathCost)){out.put("maxPathCost",maxPathCost);out.put("plannedPathCost",plannedPathCost);}
        if(status.equals("blocked")&&goal!=null&&c.player!=null&&c.world!=null){
            var blocker=new LinkedHashMap<>(goal.blocker(c,c.player.getPos(),reason));
            if(reason.equals("movement_stalled"))blocker.putAll(execution.blocker(c));
            if(refusedExcavation!=null) {
                blocker.put("reason",reason);blocker.put("obstruction",List.of(refusedExcavation.getX(),refusedExcavation.getY(),refusedExcavation.getZ()));
                blocker.put("block",Registries.BLOCK.getId(c.world.getBlockState(refusedExcavation).getBlock()).toString());
                blocker.put("explanation",reason.equals("access_outside_gather_area")?
                    "The planned approach would break the reported block outside the requested gathering cube. Widen the area or choose another approach.":
                    "The approach requires breaking the reported block, but inventory has no harvest-capable tool. Obtain a suitable tool or explicitly permit use_hands.");
            }
            out.put("blocker",blocker);
        }
        return JSON.toJsonTree(out);
    }
    private static List<Double> xyz(Vec3d p){return p==null?null:List.of(p.x,p.y,p.z);}
    private static final class Process implements IBaritoneProcess {
        @Override public boolean isActive(){return active;}
        @Override public boolean isTemporary(){return false;}
        @Override public double priority(){return 10;}
        @Override public String displayName0(){return "Musecraft movement";}
        @Override public void onLostControl(){active=false;}
        @Override public PathingCommand onTick(boolean failed,boolean safeToCancel) {
            BaritoneNavigation.safeToCancel=safeToCancel;
            if(failed) {
                var wider=routeGoal==null?null:routeGoal.expanded();
                if(wider!=null){routeGoal=wider;return new PathingCommand(routeGoal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);}
                stop(MinecraftClient.getInstance(),"blocked","no_route_in_region");
                return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
            }
            var current=engine.getPathingBehavior().getCurrent();
            if(current!=null&&Double.isFinite(maxPathCost)) {
                plannedPathCost=current.getPath().ticksRemainingFrom(current.getPosition());
                if(plannedPathCost>maxPathCost&&safeToCancel) {
                    stop(MinecraftClient.getInstance(),"blocked","local_excavation_cheaper");
                    return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            if(returnable&&engine.getPathingBehavior().getCurrent()!=null) {
                var positions=engine.getPathingBehavior().getCurrent().getPath().positions();
                for(int i=1;i<positions.size();i++)if(positions.get(i-1).getY()-positions.get(i).getY()>1) {
                    stop(MinecraftClient.getInstance(),"blocked","return_path_unavailable");
                    return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommand(routeGoal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
    }
}
