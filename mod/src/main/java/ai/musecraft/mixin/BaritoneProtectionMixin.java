package ai.musecraft.mixin;

import ai.musecraft.BaritoneProtection;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=CalculationContext.class,remap=false)
public abstract class BaritoneProtectionMixin {
    @Inject(method="breakCostMultiplierAt",at=@At("HEAD"),cancellable=true,remap=false)
    private void protectBreak(int x,int y,int z,BlockState current,CallbackInfoReturnable<Double> out) {
        if(BaritoneProtection.breakDenied((CalculationContext)(Object)this,x,y,z,current))out.setReturnValue(ActionCosts.COST_INF);
    }
    @Inject(method="costOfPlacingAt",at=@At("HEAD"),cancellable=true,remap=false)
    private void protectPlace(int x,int y,int z,BlockState current,CallbackInfoReturnable<Double> out) {
        if(BaritoneProtection.placeDenied(x,y,z,current))out.setReturnValue(ActionCosts.COST_INF);
    }
}
