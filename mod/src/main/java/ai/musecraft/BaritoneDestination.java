package ai.musecraft;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;

/** Coordinate goals describe destination cells; Baritone decides how to stand there. */
final class BaritoneDestination implements Goal {
    private final Set<BlockPos> cells;
    private final Set<BlockPos> activeCells;
    private final boolean expanded;
    private final Goal heuristic;
    private final NavigationGoal destination;
    BaritoneDestination(NavigationGoal destination,Set<BlockPos> cells) {
        this(destination,cells,false);
    }
    private BaritoneDestination(NavigationGoal destination,Set<BlockPos> cells,boolean expanded) {
        this.destination=destination;this.cells=Set.copyOf(cells);
        var interior=new HashSet<BlockPos>();
        for(var cell:cells)if(destination.inRegion(new Vec3d(cell.getX()+.5,cell.getY(),cell.getZ()+.5),true))interior.add(cell);
        this.expanded=expanded||interior.isEmpty();
        this.activeCells=this.expanded?this.cells:Set.copyOf(interior);
        // The enclosing native sphere is a constant-time lower bound, including
        // partial-block feet heights. Final alignment can only increase cost.
        this.heuristic=new GoalNear(BlockPos.ofFloored(destination.regionCenter()),(int)Math.ceil(destination.radius()+2));
    }
    public boolean isInGoal(int x,int y,int z){return activeCells.contains(new BlockPos(x,y,z));}
    public double heuristic(int x,int y,int z){return heuristic.heuristic(x,y,z);}
    int size(){return activeCells.size();}
    int candidateCount(){return cells.size();}
    BaritoneDestination expanded(){return expanded||activeCells.size()==cells.size()?null:new BaritoneDestination(destination,cells,true);}
    BaritoneDestination excluding(Set<BlockPos> rejected) {
        var remaining=new HashSet<>(cells);remaining.removeAll(rejected);
        return new BaritoneDestination(destination,remaining,expanded);
    }
    Vec3d alignment(MinecraftClient c,BlockPos cell,Vec3d here) {
        // A corner belongs to several approach cells. Route to any of them,
        // then align to the requested point instead of rounding it to a center.
        var candidates=List.of(here,new Vec3d(destination.requested.x,here.y,destination.requested.z),
            new Vec3d(MathHelper.clamp(destination.requested.x,cell.getX()+.01,cell.getX()+.99),here.y,
                MathHelper.clamp(destination.requested.z,cell.getZ()+.01,cell.getZ()+.99)),
            new Vec3d(MathHelper.clamp(destination.requested.x,cell.getX()+.301,cell.getX()+.699),here.y,
                MathHelper.clamp(destination.requested.z,cell.getZ()+.301,cell.getZ()+.699)));
        return candidates.stream().filter(p->here.distanceTo(p)<1.5&&destination.accepts(c,p)
            &&Navigation.clear(c,here,p,false)).min(Comparator.comparingDouble(here::squaredDistanceTo)).orElse(null);
    }

    static final class Builder {
        private final NavigationGoal destination;
        private final Set<BlockPos> cells=new HashSet<>();
        private Iterator<BlockPos> candidates;
        private BaritoneDestination result;
        Builder(NavigationGoal destination){this.destination=destination;}
        BaritoneDestination result(){return result;}
        void step(MinecraftClient c) {
            long until=System.nanoTime()+3_000_000L;
            do {
                if(!destination.prepareBaritone(c))continue;
                if(destination.failure()!=null){result=new BaritoneDestination(destination,Set.of());return;}
                if(candidates==null) {
                    var center=destination.regionCenter();double r=destination.radius(),vertical=destination.verticalRadius();
                    candidates=BlockPos.iterate(BlockPos.ofFloored(center.x-r-1,center.y-vertical-1,center.z-r-1),
                        BlockPos.ofFloored(center.x+r,center.y+vertical,center.z+r)).iterator();
                }
                if(!candidates.hasNext()){result=new BaritoneDestination(destination,cells);return;}
                var cell=candidates.next().toImmutable();var center=destination.regionCenter();
                // IPlayerContext.playerFeet rounds feet Y up by .1251. Use
                // the cell's possible feet volume, not just its center point.
                var closest=new Vec3d(MathHelper.clamp(center.x,cell.getX(),cell.getX()+1),
                    MathHelper.clamp(center.y,cell.getY()-.1251,cell.getY()+.8748),
                    MathHelper.clamp(center.z,cell.getZ(),cell.getZ()+1));
                if(destination.inRegion(closest,false))cells.add(cell);
            }while(System.nanoTime()<until);
        }
    }
}
