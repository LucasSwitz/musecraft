package ai.musecraft.mixin;

import ai.musecraft.BaritoneNavigation;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gathering may walk outside its area but must not excavate outside it. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ExcavationAreaMixin {
    @Inject(method={"attackBlock","updateBlockBreakingProgress"},at=@At("HEAD"),cancellable=true)
    private void musecraftExcavationArea(BlockPos pos,Direction side,CallbackInfoReturnable<Boolean> result) {
        if(ai.musecraft.BaritoneProtection.rejectBreak(pos)||ai.musecraft.BaritoneBuild.rejectMutation(pos,true)||BaritoneNavigation.rejectExcavation(pos))result.setReturnValue(false);
    }
}
