package ai.musecraft.mixin;

import ai.musecraft.BaritoneBuild;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Journal the actual vanilla placement cell before its first mutation. */
@Mixin(BlockItem.class)
public class ConstructionPlacementMixin {
    @Inject(method="place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",at=@At("HEAD"),cancellable=true)
    private void musecraftBuildPlacement(ItemPlacementContext context,CallbackInfoReturnable<ActionResult> result) {
        if(ai.musecraft.BaritoneProtection.protectedCell(context.getBlockPos(),context.getWorld().getBlockState(context.getBlockPos()))
            ||!ai.musecraft.RoutePlacement.admit(context.getBlockPos(),context.getStack())||!BaritoneBuild.allowPlacement(context.getBlockPos(),context.getStack())
            ||BaritoneBuild.rejectMutation(context.getBlockPos(),false))result.setReturnValue(ActionResult.FAIL);
    }
}
