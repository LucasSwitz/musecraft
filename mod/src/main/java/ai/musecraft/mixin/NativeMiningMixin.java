package ai.musecraft.mixin;

import ai.musecraft.BaritoneTasks;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import baritone.process.MineProcess;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bound upstream discovery without reimplementing its search or unreachable-target recovery. */
@Mixin(value=MineProcess.class,remap=false)
public abstract class NativeMiningMixin {
    @Inject(method="plausibleToBreak",at=@At("HEAD"),cancellable=true,remap=false)
    private static void discoverProtectedBlocks(CalculationContext context,BlockPos pos,CallbackInfoReturnable<Boolean> out) {
        // GetToBlock reuses the mining search. Finding a protected chest must
        // not require permission to break it; route mutation remains fenced.
        if(BaritoneTasks.finding(context))out.setReturnValue(true);
    }
    @org.spongepowered.asm.mixin.injection.Redirect(method="searchWorld",at=@At(value="INVOKE",target="Lbaritone/api/cache/IWorldScanner;scanChunkRadius(Lbaritone/api/utils/IPlayerContext;Lbaritone/api/utils/BlockOptionalMetaLookup;III)Ljava/util/List;",remap=false),remap=false)
    private static List<BlockPos> boundedScan(baritone.api.cache.IWorldScanner scanner,baritone.api.utils.IPlayerContext context,BlockOptionalMetaLookup filter,int max,int yThreshold,int radius) {
        return BaritoneTasks.boundedScan(scanner,context,filter,max,yThreshold,radius);
    }
    @Inject(method="searchWorld",at=@At("RETURN"),cancellable=true,remap=false)
    private static void boundedSources(CalculationContext context,BlockOptionalMetaLookup filter,int max,List<BlockPos> known,List<BlockPos> blacklist,List<BlockPos> dropped,CallbackInfoReturnable<List<BlockPos>> out) {
        out.setReturnValue(BaritoneTasks.filterTargets(context,out.getReturnValue(),false));
    }
    @Inject(method="droppedItemsScan",at=@At("RETURN"),cancellable=true,remap=false)
    private void boundedDrops(CallbackInfoReturnable<List<BlockPos>> out) {
        out.setReturnValue(BaritoneTasks.filterTargets(null,out.getReturnValue(),true));
    }
}
