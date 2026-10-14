package ai.musecraft.mixin;

import ai.musecraft.BaritoneBuild;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.block.*;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.*;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/** Builder clicks place against workbenches/containers instead of opening them.
 * Synchronize an upstream-requested sneak before the click, including its first
 * tick. Path interactions such as opening doors must retain normal use behavior.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ConstructionInteractionMixin {
    @WrapMethod(method="interactBlock")
    private ActionResult placeWhileSneaking(ClientPlayerEntity player,Hand hand,BlockHitResult hit,Operation<ActionResult> original) {
        if(!(player.getStackInHand(hand).getItem() instanceof BlockItem held))return original.call(player,hand,hit);
        var placement=held.getPlacementContext(new ItemPlacementContext(player,hand,player.getStackInHand(hand),hit));
        if(placement!=null&&ai.musecraft.BaritoneProtection.protectedCell(placement.getBlockPos(),player.getWorld().getBlockState(placement.getBlockPos())))return ActionResult.FAIL;
        if(!(BaritoneBuild.running()||ai.musecraft.BaritoneNavigation.isRunning()))return original.call(player,hand,hit);
        boolean sneak=baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getInputOverrideHandler()
            .isInputForcedDown(baritone.api.utils.input.Input.SNEAK);
        var block=player.getWorld().getBlockState(hit.getBlockPos()).getBlock();
        boolean opens=block instanceof DoorBlock door&&door.getBlockSetType().canOpenByHand()
            ||block instanceof TrapdoorBlock&&block!=Blocks.IRON_TRAPDOOR
            ||block instanceof FenceGateBlock;
        if(!sneak&&opens)return original.call(player,hand,hit);
        // Fence the outbound interaction packet, not merely client prediction:
        // cancelling BlockItem.place alone still lets the server place the item.
        var context=new ItemPlacementContext(player,hand,player.getStackInHand(hand),hit);
        if(!ai.musecraft.RoutePlacement.admit(context.getBlockPos(),context.getStack())||!BaritoneBuild.allowPlacement(context.getBlockPos(),context.getStack())
            ||BaritoneBuild.rejectMutation(context.getBlockPos(),false))return ActionResult.FAIL;
        if(!sneak)return original.call(player,hand,hit);
        boolean before=player.input.sneaking;
        try {
            player.input.sneaking=true;
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player,ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
            return original.call(player,hand,hit);
        } finally {
            player.input.sneaking=before;
            if(!before)player.networkHandler.sendPacket(new ClientCommandC2SPacket(player,ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
        }
    }
}
