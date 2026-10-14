package ai.musecraft.mixin;

import ai.musecraft.WaterDescent;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Upstream rejects all flowing water; allow only the explicitly verified landing cell. */
@Mixin(value=MovementHelper.class,remap=false)
public interface WaterLandingMixin {
    @Inject(method="isFlowing",at=@At("HEAD"),cancellable=true,remap=false)
    private static void verifiedWaterLanding(int x,int y,int z,BlockState state,BlockStateInterface bsi,CallbackInfoReturnable<Boolean> out) {
        if(WaterDescent.permitsFlow(x,y,z))out.setReturnValue(false);
    }
}
