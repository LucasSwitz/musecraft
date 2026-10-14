package ai.musecraft.mixin;

import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenInvoker {
    // Minecraft 1.21.1's Cancel button handler cancels both DNS/connect and Netty.
    @Invoker("method_19800")
    void musecraft$cancel(ButtonWidget button);
}
