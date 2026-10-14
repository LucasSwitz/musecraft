package ai.musecraft.mixin;

import ai.musecraft.BaritoneBuild;
import java.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Baritone 1.11.3 inventory capability checks must not compare placement orientation.
 * Actual click simulation and world-state validation still use upstream valid().
 * The intermediary descriptor below is pinned to the shipped Minecraft version.
 */
@Mixin(value=baritone.process.BuilderProcess.class,remap=false)
public abstract class BaritoneMaterialsMixin {
    @Invoker(value="valid",remap=false)
    private static boolean originalValid(BlockState current,BlockState desired,boolean itemVerify) {throw new AssertionError();}

    @Inject(method="approxPlaceable",at=@At("RETURN"),cancellable=true,remap=false)
    private void materialStates(int size,CallbackInfoReturnable<List<BlockState>> out) {
        if(!BaritoneBuild.running())return;
        var states=new ArrayList<>(out.getReturnValue());
        var inventory=MinecraftClient.getInstance().player.getInventory();
        for(int i=0;i<size;i++) {
            var stack=inventory.getStack(i);
            // Doors and beds can reject the fake placement at the player's feet.
            // The stack remains available even when that sample position is occupied.
            if(states.get(i).isAir()&&!stack.isEmpty()&&stack.getItem() instanceof BlockItem item)
                states.set(i,item.getBlock().getDefaultState());
        }
        out.setReturnValue(states);
    }
    @Inject(method="containsBlockState",at=@At("HEAD"),cancellable=true,remap=false)
    private static void containsMaterial(Collection<BlockState> states,BlockState wanted,CallbackInfoReturnable<Boolean> out) {
        if(BaritoneBuild.running())out.setReturnValue(states.stream().anyMatch(s->s.getBlock()==wanted.getBlock()));
    }
    @Inject(method="placementGoal",at=@At("RETURN"),cancellable=true,remap=false)
    private void facingGoal(net.minecraft.util.math.BlockPos pos,
            @org.spongepowered.asm.mixin.injection.Coerce Object context,
            CallbackInfoReturnable<baritone.api.pathing.goals.Goal> out) {
        if(BaritoneBuild.running())out.setReturnValue(ai.musecraft.BuildFacingGoal.constrain(pos,out.getReturnValue()));
    }
    @Redirect(method="toBreakNearPlayer",at=@At(value="INVOKE",target="Lbaritone/api/utils/RotationUtils;reachable(Lbaritone/api/utils/IPlayerContext;Lnet/minecraft/class_2338;D)Ljava/util/Optional;",remap=false),remap=false)
    private Optional<baritone.api.utils.Rotation> safeSupportRemoval(baritone.api.utils.IPlayerContext context,net.minecraft.util.math.BlockPos pos,double reach) {
        // Skip an unsafe candidate inside upstream's search, so it can still
        // select another reachable support instead of discarding the whole scan.
        if(BaritoneBuild.unsafeSupportRemoval(pos))return Optional.empty();
        return baritone.api.utils.RotationUtils.reachable(context,pos,reach);
    }
    @Redirect(method="hasAnyItemThatWouldPlace",at=@At(value="INVOKE",target="Lbaritone/process/BuilderProcess;valid(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z",remap=false),remap=false)
    private boolean placementConstraints(BlockState actual,BlockState desired,boolean itemVerify,
            BlockState requested,net.minecraft.util.hit.HitResult hit,baritone.api.utils.Rotation rotation) {
        if(BaritoneBuild.running()&&hit instanceof net.minecraft.util.hit.BlockHitResult blockHit) {
            // This adapter relaxes unspecified properties, never the requested
            // block type. In particular an AIR cleanup goal cannot place a slab
            // merely because its adjusted click context touches a finished slab.
            if(actual.getBlock()!=desired.getBlock())return false;
            var c=MinecraftClient.getInstance();
            var context=new net.minecraft.item.ItemPlacementContext(c.player,net.minecraft.util.Hand.MAIN_HAND,
                new net.minecraft.item.ItemStack(actual.getBlock().asItem()),blockHit);
            if(!BaritoneBuild.allowPlacement(context.getBlockPos(),context.getStack()))return false;
            var wanted=BaritoneBuild.wanted(context.getBlockPos());
            if(wanted!=null)return !BaritoneBuild.matchesPlacement(c.world.getBlockState(context.getBlockPos()),wanted)
                &&BaritoneBuild.matchesPlacement(actual,wanted);
        }
        return originalValid(actual,desired,itemVerify);
    }
    @Redirect(method="onTick(ZZI)Lbaritone/api/process/PathingCommand;",at=@At(value="INVOKE",target="Lbaritone/process/BuilderProcess;valid(Lnet/minecraft/class_2680;Lnet/minecraft/class_2680;Z)Z",remap=false),remap=false,require=2)
    private boolean hotbarMaterial(BlockState current,BlockState desired,boolean itemVerify) {
        return BaritoneBuild.running()&&itemVerify?current.getBlock()==desired.getBlock():originalValid(current,desired,itemVerify);
    }
}
