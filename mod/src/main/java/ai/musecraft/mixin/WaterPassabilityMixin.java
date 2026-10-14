package ai.musecraft.mixin;

import ai.musecraft.WaterDescent;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the global block-state cache unchanged; admit only the verified landing. */
@Mixin(value=PrecomputedData.class,remap=false)
public abstract class WaterPassabilityMixin {
    @Inject(method="canWalkThrough",at=@At("HEAD"),cancellable=true,remap=false)
    private void verifiedWaterLanding(BlockStateInterface bsi,int x,int y,int z,BlockState state,CallbackInfoReturnable<Boolean> out) {
        if(WaterDescent.permitsFlow(x,y,z)&&state.getFluidState().isIn(FluidTags.WATER))out.setReturnValue(true);
    }
}
