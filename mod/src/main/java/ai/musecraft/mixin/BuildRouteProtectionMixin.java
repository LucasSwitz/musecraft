package ai.musecraft.mixin;

import ai.musecraft.BaritoneBuild;
import baritone.api.pathing.movement.ActionCosts;
import baritone.process.BuilderProcess;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The upstream layer mask must not hide finished cells from route protection. */
@Mixin(value=BuilderProcess.BuilderCalculationContext.class,remap=false)
public abstract class BuildRouteProtectionMixin {
    @Inject(method="breakCostMultiplierAt",at=@At("HEAD"),cancellable=true,remap=false)
    private void preserveFinishedCells(int x,int y,int z,BlockState current,CallbackInfoReturnable<Double> out) {
        if(BaritoneBuild.preserve(new BlockPos(x,y,z),current)||ai.musecraft.BaritoneProtection.protectedCell(new BlockPos(x,y,z),current))out.setReturnValue(ActionCosts.COST_INF);
    }
}
