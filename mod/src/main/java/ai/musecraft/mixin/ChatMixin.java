package ai.musecraft.mixin;

import ai.musecraft.ChatInbox;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import java.time.Instant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MessageHandler.class)
public class ChatMixin {
    // Only messages accepted by vanilla's blocking, trust and text filters.
    @Inject(method = "processChatMessageInternal", at = @At("RETURN"))
    private void musecraftPlayerChat(MessageType.Parameters params, SignedMessage message, Text decorated,
            GameProfile sender, boolean secureOnly, Instant receivedAt, CallbackInfoReturnable<Boolean> ci) {
        if (!ci.getReturnValue()) return;
        Text visible = decorated;
        if (!message.filterMask().isPassThrough()) {
            Text filtered = message.filterMask().getFilteredText(message.getSignedContent());
            if (filtered == null) return;
            visible = params.applyChatDecoration(filtered);
        }
        ChatInbox.add("player", visible.getString(), sender.getId(), sender.getName(), message.hasSignature());
    }
    // This overload records displayed system and profileless server messages.
    @Inject(method = "addToChatLog(Lnet/minecraft/text/Text;Ljava/time/Instant;)V", at = @At("HEAD"))
    private void musecraftServerChat(Text text, Instant receivedAt, CallbackInfo ci) {
        String key=text.getContent() instanceof net.minecraft.text.TranslatableTextContent translated?translated.getKey():null;
        ChatInbox.add("server", text.getString(), null, null, false,key);
    }
}
