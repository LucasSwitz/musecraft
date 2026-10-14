package ai.musecraft.mixin;

import ai.musecraft.SurfaceSwimming;
import net.minecraft.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class MountSwimmingMixin {
    @Inject(method="tickMovement",at=@At(value="FIELD",
        target="Lnet/minecraft/entity/LivingEntity;jumping:Z",opcode=Opcodes.GETFIELD))
    private void musecraft$swimAtVehicleTick(CallbackInfo ci) {
        SurfaceSwimming.beforeMountJump((LivingEntity)(Object)this);
    }
}
