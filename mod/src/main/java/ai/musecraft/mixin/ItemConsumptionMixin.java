package ai.musecraft.mixin;

import ai.musecraft.ItemActions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class ItemConsumptionMixin {
    @Inject(method="consumeItem", at=@At("HEAD"))
    private void musecraftConsuming(CallbackInfo ci) {
        var client=MinecraftClient.getInstance();
        if((Object)this==client.player)ItemActions.consuming(client);
    }
    @Inject(method="consumeItem", at=@At("RETURN"))
    private void musecraftConsumed(CallbackInfo ci) {
        if((Object)this==MinecraftClient.getInstance().player)ItemActions.consumed();
    }
}
