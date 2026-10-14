package ai.musecraft.mixin;
import ai.musecraft.Navigation;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardInput.class)
public class MovementInputMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void musecraftMovement(boolean slowDown,float slowDownFactor,CallbackInfo ci) { Navigation.apply((Input)(Object)this); ai.musecraft.JumpPlacement.apply((Input)(Object)this); ai.musecraft.SurfaceSwimming.apply((Input)(Object)this); }
}
