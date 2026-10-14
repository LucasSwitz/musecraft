package ai.musecraft;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import java.util.*;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;

/** Immutable worker-thread goal. Minecraft geometry is sampled only on the client thread. */
final class BaritoneGoal implements Goal {
    private final Map<BlockPos,List<Vec3d>> cells;
    private final BaritoneDestination destination;
    private BaritoneGoal(BaritoneDestination destination){this.destination=destination;cells=Map.of();}
    BaritoneGoal(MinecraftClient c,List<Vec3d> poses) {
        destination=null;
        var grouped=new HashMap<BlockPos,List<Vec3d>>();
        for(var pose:poses) {
            // Match Baritone IPlayerContext.playerFeet, including bottom slabs and paths.
            var cell=BlockPos.ofFloored(pose.add(0,.1251,0));
            if(c.world.getBlockState(cell).getBlock() instanceof SlabBlock)cell=cell.up();
            grouped.computeIfAbsent(cell,k->new ArrayList<>()).add(pose);
        }
        var frozen=new HashMap<BlockPos,List<Vec3d>>();
        grouped.forEach((k,v)->frozen.put(k,List.copyOf(v)));cells=Map.copyOf(frozen);
    }
    @Override public boolean isInGoal(int x,int y,int z){return destination!=null?destination.isInGoal(x,y,z):cells.containsKey(new BlockPos(x,y,z));}
    @Override public double heuristic(int x,int y,int z) {
        if(destination!=null)return destination.heuristic(x,y,z);
        double best=Double.POSITIVE_INFINITY;
        for(var cell:cells.keySet())best=Math.min(best,GoalBlock.calculate(x-cell.getX(),y-cell.getY(),z-cell.getZ()));
        return best;
    }
    Vec3d nearest(BlockPos cell,Vec3d here) {
        if(destination!=null)return destination.alignment(MinecraftClient.getInstance(),cell,here);
        return cells.getOrDefault(cell,List.of()).stream().min(Comparator.comparingDouble(here::squaredDistanceTo)).orElse(null);
    }
    int size(){return destination!=null?destination.size():cells.size();}
    int candidateCount(){return destination!=null?destination.candidateCount():cells.size();}
    boolean coordinate(){return destination!=null;}
    BaritoneGoal excluding(Set<BlockPos> cells){return new BaritoneGoal(destination.excluding(cells));}
    BaritoneGoal expanded(){var wider=destination==null?null:destination.expanded();return wider==null?null:new BaritoneGoal(wider);}

    /** Batch valid action endpoints; no path search or world reads run on a worker here. */
    static final class Builder {
        final NavigationGoal goal;
        private final List<Vec3d> columns=new ArrayList<>(),poses=new ArrayList<>(),clearablePoses=new ArrayList<>();
        private final Set<Block> clearable;
        private boolean clearance;
        private int index;
        private boolean initialized;
        private BaritoneGoal result;
        private BaritoneDestination.Builder destination;
        Builder(NavigationGoal goal){this(goal,Set.of());}
        Builder(NavigationGoal goal,Set<Block> clearable){this.goal=goal;this.clearable=Set.copyOf(clearable);}
        boolean clearance(){return clearance;}
        boolean done(){return result!=null;}
        BaritoneGoal result(){return result;}
        void step(MinecraftClient c) {
            if(goal.volumeGoal()) {
                if(destination==null)destination=new BaritoneDestination.Builder(goal);
                destination.step(c);
                if(destination.result()!=null)result=new BaritoneGoal(destination.result());
                return;
            }
            long until=System.nanoTime()+3_000_000L;
            do {
                if(!goal.ready()){goal.resolveStep(c);continue;}
                if(goal.failure()!=null){result=new BaritoneGoal(c,List.of());return;}
                if(!initialized) {
                    initialized=true;
                    if(goal.kind.equals("exact")) {
                        if(goal.accepts(c,goal.requested))poses.add(goal.requested);
                        result=new BaritoneGoal(c,poses);return;
                    }
                    var target=goal.anchor();int radius=(int)Math.ceil(goal.radius());
                    for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)
                        columns.add(new Vec3d(Math.floor(target.x)+x+.5,target.y,Math.floor(target.z)+z+.5));
                    if(goal.accepts(c,c.player.getPos()))poses.add(c.player.getPos());
                }
                if(index==columns.size()){
                    clearance=poses.isEmpty()&&!clearablePoses.isEmpty();
                    result=new BaritoneGoal(c,clearance?clearablePoses:poses);return;
                }
                var column=columns.get(index++);
                double vertical=goal.kind.equals("near")?goal.radius():7;
                for(var pose:NavigationTerrain.travelSurfaces(c,column.x,column.z,column.y-vertical,column.y+vertical,true))
                    if(goal.accepts(c,pose))poses.add(pose);
                if(!clearable.isEmpty()&&(goal.kind.equals("placement")||goal.kind.equals("action")))for(int y=(int)Math.floor(column.y-4);y<=column.y+3;y++) {
                    var pose=new Vec3d(column.x,y,column.z);
                    if(pose.distanceTo(goal.requested)<=4&&clearableStance(c,pose))clearablePoses.add(pose);
                }
            }while(System.nanoTime()<until);
        }
        /** A bounded approach only: the real placement predicate is checked again after arrival. */
        private boolean clearableStance(MinecraftClient c,Vec3d pose) {
            if(!NavigationTerrain.fullFloor(c,pose,pose)||clearable.contains(c.world.getBlockState(BlockPos.ofFloored(pose).down()).getBlock()))return false;
            var body=NavigationTerrain.body(pose);boolean obstructed=false;
            for(var pos:BlockPos.iterate(BlockPos.ofFloored(body.minX,body.minY-1,body.minZ),BlockPos.ofFloored(body.maxX,body.maxY,body.maxZ))) {
                if(!WorldActions.loaded(c,pos))return false;
                if(new Box(pos).intersects(body)&&(!WorldActions.safeSupport(c,pos)||!c.world.getFluidState(pos).isEmpty()))return false;
                for(var shape:NavigationMap.shapes(c,pos))if(shape.offset(pos).intersects(body)) {
                    if(!clearable.contains(c.world.getBlockState(pos).getBlock()))return false;
                    obstructed=true;
                }
            }
            return obstructed&&c.world.getEntityCollisions(c.player,body).isEmpty();
        }
    }
}
