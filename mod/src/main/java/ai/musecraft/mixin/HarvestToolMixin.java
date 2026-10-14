package ai.musecraft.mixin;

import ai.musecraft.BaritoneTasks;
import baritone.utils.ToolSet;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Upstream still ranks tools; disqualify tools that cannot produce the requested drop. */
@Mixin(value=ToolSet.class,remap=false)
public abstract class HarvestToolMixin {
    @Inject(method="calculateSpeedVsBlock",at=@At("HEAD"),cancellable=true,remap=false)
    private static void requestedDrop(ItemStack stack,BlockState state,CallbackInfoReturnable<Double> out) {
        if(!BaritoneTasks.validTool(stack,state))out.setReturnValue(0.0);
    }
}
