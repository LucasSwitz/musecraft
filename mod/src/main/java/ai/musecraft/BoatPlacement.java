package ai.musecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

/** Select a vanilla fluid ray hit where a full boat fits beside the shore. */
final class BoatPlacement {
    private BoatPlacement() {}
    static BlockHitResult hit(MinecraftClient c,BlockPos target,Vec3d eye) {
        var fluid=c.world.getFluidState(target);
        if(!fluid.isIn(FluidTags.WATER))throw new IllegalStateException("boat_water_required");
        double surface=target.getY()+fluid.getHeight(c.world,target);
        double half=EntityType.BOAT.getWidth()/2.,height=EntityType.BOAT.getHeight();
        BlockHitResult best=null;double cost=Double.MAX_VALUE;
        for(double dx:new double[]{.5,.15,.85})for(double dz:new double[]{.5,.15,.85}) {
            var aim=new Vec3d(target.getX()+dx,surface-.005,target.getZ()+dz);
            var hit=c.world.raycast(new RaycastContext(eye,aim,
                RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.SOURCE_ONLY,c.player));
            if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(target))continue;
            var point=hit.getPos();double distance=eye.distanceTo(point);
            if(distance>c.player.getBlockInteractionRange())continue;
            var body=new Box(point.x-half,point.y,point.z-half,point.x+half,point.y+height,point.z+half);
            if(!c.world.isSpaceEmpty(null,body))continue;
            if(distance<cost){best=hit;cost=distance;}
        }
        if(best==null)throw new IllegalStateException("no_reachable_boat_placement");
        return best;
    }
}
