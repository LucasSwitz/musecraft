package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BoatItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

/** Navigation and execution use the same item, face, reach and fluid hit. */
final class ItemUseGeometry {
    static BlockHitResult hit(MinecraftClient c,JsonObject p,BlockPos target,Vec3d eye) {
        Direction face=p.has("face")?Direction.byName(p.get("face").getAsString()):null;
        if(p.has("face")&&face==null)throw new IllegalArgumentException("invalid_face");
        var item=Registries.ITEM.get(Identifier.of(p.get("item").getAsString()));
        BlockHitResult hit;
        if(item instanceof BoatItem&&c.world.getFluidState(target).isIn(FluidTags.WATER)) {
            hit=BoatPlacement.hit(c,target,eye);
        }else if(!c.world.getFluidState(target).isEmpty()&&c.world.getBlockState(target).getOutlineShape(c.world,target).isEmpty()) {
            hit=c.world.raycast(new RaycastContext(eye,Vec3d.ofCenter(target),RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.SOURCE_ONLY,c.player));
        }else {
            var faces=WorldActions.visibleFaces(c,target,face,eye,true);
            if(faces.isEmpty())throw new IllegalStateException("no_reachable_visible_face");
            hit=faces.getFirst();
        }
        if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(target)
                ||face!=null&&hit.getSide()!=face||eye.distanceTo(hit.getPos())>c.player.getBlockInteractionRange())
            throw new IllegalStateException("no_reachable_visible_face");
        return hit;
    }
    static boolean accepts(MinecraftClient c,JsonObject p,BlockPos target,Vec3d feet) {
        var eye=feet.add(0,c.player.getStandingEyeHeight(),0);
        var item=Registries.ITEM.get(Identifier.of(p.get("item").getAsString()));
        if(item instanceof BlockItem block&&c.world.getBlockState(target).isReplaceable()) {
            if(NavigationTerrain.body(feet).intersects(new Box(target)))return false;
            var placement=p.deepCopy();
            placement.addProperty("block",Registries.BLOCK.getId(block.getBlock()).toString());
            return WorldActions.placement(c,placement,eye,true)!=null;
        }
        try {hit(c,p,target,eye);return true;}
        catch(IllegalStateException unavailable){return false;}
    }
}
