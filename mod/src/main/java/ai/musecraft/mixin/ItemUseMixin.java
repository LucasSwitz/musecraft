package ai.musecraft.mixin;
import ai.musecraft.ItemActions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
@Mixin(MinecraftClient.class)
public class ItemUseMixin {
    @Redirect(method="handleInputEvents",at=@At(value="INVOKE",target="Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean musecraftItemUse(KeyBinding binding) {
        return binding==MinecraftClient.getInstance().options.useKey && (ItemActions.maintainingUse() || ai.musecraft.Combat.maintainingUse()) || binding.isPressed();
    }
}
