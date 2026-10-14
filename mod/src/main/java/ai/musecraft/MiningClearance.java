package ai.musecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;

/** Small, visible excavation alternatives; Baritone still owns route planning. */
final class MiningClearance {
    record Access(BlockPos block,Vec3d approach) {}
    static Access next(MinecraftClient c,BlockPos target) {
        var here=c.player.getPos();
        var delta=Vec3d.ofCenter(target).subtract(here);
        if(delta.horizontalLength()<1)return null;
        var previous=here;
        // Inspect at most four local steps. A clear ray through a descending
        // tunnel is not enough: its head sweep must also fit before dropping.
        for(int distance=1;distance<=Math.min(4,delta.horizontalLength());distance++) {
            double x=Math.floor(here.x+distance*delta.x/delta.horizontalLength())+.5;
            double z=Math.floor(here.z+distance*delta.z/delta.horizontalLength())+.5;
            var surfaces=NavigationTerrain.travelSurfaces(c,x,z,previous.y-1,previous.y,false);
            var next=surfaces.stream().max(java.util.Comparator.comparingDouble(v->v.y)).orElse(null);
            var corner=new Vec3d(x,previous.y,z);
            var sweep=NavigationTerrain.body(previous).union(NavigationTerrain.body(corner));
            for(var pos:BlockPos.iterate(BlockPos.ofFloored(sweep.minX,sweep.minY,sweep.minZ),
                    BlockPos.ofFloored(sweep.maxX,sweep.maxY,sweep.maxZ))) {
                if(!MiningAccess.removable(c,pos,here))continue;
                boolean intersects=NavigationMap.shapes(c,pos).stream()
                    .anyMatch(shape->shape.offset(pos).intersects(sweep));
                if(intersects&&!WorldActions.visibleFaces(c,pos,null,c.player.getEyePos(),true).isEmpty())
                    return new Access(pos.toImmutable(),null);
            }
            if(next==null||!NavigationTerrain.edge(c,previous,next))break;
            previous=next;
        }
        // Advance through a proven short, empty approach so a currently hidden
        // headroom block can be inspected from the lip. No speculative digging.
        return previous.distanceTo(here)>.75?new Access(null,previous):null;
    }
    static double pathCost(MinecraftClient c,BlockPos obstruction,BlockPos target) {
        var state=c.world.getBlockState(obstruction);
        double hardness=state.getHardness(c.world,obstruction),best=0;
        for(int slot=0;slot<36;slot++) {
            var stack=c.player.getInventory().getStack(slot);
            boolean harvest=!state.isToolRequired()||stack.isSuitableFor(state);
            best=Math.max(best,stack.getMiningSpeedMultiplier(state)/(harvest?30.:100.));
        }
        // Same cost units as Baritone (ticks). Budget two local removals plus
        // a direct walk, with a margin to prefer walking when costs are close.
        double digging=Math.ceil(Math.max(0,hardness)/Math.max(best,.0001));
        return 20+2*(digging+5)+5*c.player.getPos().distanceTo(Vec3d.ofCenter(target));
    }
}
