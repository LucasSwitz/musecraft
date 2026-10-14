package ai.musecraft.mixin;

import ai.musecraft.BaritoneBuild;
import baritone.api.process.PathingCommand;
import baritone.process.BuilderProcess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fence a cleanup's path request before Baritone can leave the safe work position. */
@Mixin(value=BuilderProcess.class,remap=false)
public abstract class RouteCleanupMovementMixin {
    @Inject(method="onTick(ZZ)Lbaritone/api/process/PathingCommand;",at=@At("RETURN"),cancellable=true,remap=false)
    private void preserveCleanupPosition(boolean calcFailed,boolean safeToCancel,CallbackInfoReturnable<PathingCommand> out) {
        out.setReturnValue(BaritoneBuild.constrainCleanup(out.getReturnValue()));
    }
}
