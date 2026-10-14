package ai.musecraft.mixin;
import ai.musecraft.MusecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPlayNetworkHandler.class)
public class ServerUpdatesMixin {
    @Inject(method="onPlayerList",at=@At("TAIL"))
    private void playersAdded(net.minecraft.network.packet.s2c.play.PlayerListS2CPacket packet,CallbackInfo ci) {
        ai.musecraft.PlayerPresence.added((ClientPlayNetworkHandler)(Object)this,packet);
    }
    @Inject(method="onPlayerRemove",at=@At("TAIL"))
    private void playersRemoved(net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket packet,CallbackInfo ci) {
        ai.musecraft.PlayerPresence.removed((ClientPlayNetworkHandler)(Object)this,packet);
    }
    @Inject(method="onChunkData",at=@At("TAIL"))
    private void chunkData(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket packet,CallbackInfo ci) { MusecraftClient.serverChunkUpdated(packet.getChunkX(),packet.getChunkZ()); }
    @Inject(method="onEntityDamage",at=@At("TAIL"))
    private void damage(net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket packet,CallbackInfo ci) { MusecraftClient.serverDamage(packet); }
    @Inject(method="onScreenHandlerSlotUpdate",at=@At("TAIL"))
    private void inventorySlot(net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) { MusecraftClient.serverInventoryUpdated(); }
    @Inject(method="onInventory",at=@At("TAIL"))
    private void inventory(net.minecraft.network.packet.s2c.play.InventoryS2CPacket packet, CallbackInfo ci) { MusecraftClient.serverInventoryUpdated(); }
    @Inject(method="onBlockUpdate",at=@At("TAIL"))
    private void blockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        MusecraftClient.serverBlockUpdated(packet.getPos(),packet.getState());
    }
    @Inject(method="onChunkDeltaUpdate",at=@At("TAIL"))
    private void chunkUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        packet.visitUpdates(MusecraftClient::serverBlockUpdated);
    }
}
