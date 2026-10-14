package ai.musecraft;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.util.Hand;

/** Ordinary empty-hand riding and saddle interactions; the controller owns retries. */
final class MountActions {
    static Map<String,Object> interact(MinecraftClient c, Entity target, boolean prepare) {
        if (!(target instanceof BoatEntity) && !(target instanceof AbstractHorseEntity))
            throw new IllegalStateException("vehicle_type_unsupported");
        if (target.hasPassengers() && c.player.getVehicle() != target)
            throw new IllegalStateException("vehicle_occupied");
        String stage = "boarding";
        if (target instanceof AbstractHorseEntity horse) {
            if (horse instanceof LlamaEntity) throw new IllegalStateException("mount_not_steerable");
            if (horse.isBaby()) throw new IllegalStateException("mount_requires_adult");
            if (!prepare && (!horse.isTame() || !horse.isSaddled()))
                throw new IllegalStateException("mount_requires_tamed_saddled_horse");
            if (horse.isTame() && !horse.isSaddled()) {
                if (c.player.hasVehicle()) throw new IllegalStateException("dismount_before_saddling");
                JsonObject request = new JsonObject(); request.addProperty("item", "minecraft:saddle");
                ItemActions.equip(c, request);
                stage = "saddling";
            } else {
                emptyHand(c);
                if (!horse.isTame()) stage = "taming";
            }
        } else emptyHand(c);
        var result = c.interactionManager.interactEntity(c.player, target, Hand.MAIN_HAND);
        if (result.shouldSwingHand()) c.player.swingHand(Hand.MAIN_HAND);
        return Map.of("accepted", result.isAccepted(), "stage", stage);
    }

    private static void emptyHand(MinecraftClient c) {
        if (c.player.getMainHandStack().isEmpty()) return;
        int empty = -1;
        for (int i=0; i<36; i++) if (c.player.getInventory().getStack(i).isEmpty()) {empty=i;break;}
        if (empty<0) throw new IllegalStateException("free_one_inventory_slot_for_empty_hand");
        if (empty<9) c.player.getInventory().selectedSlot=empty;
        else c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId, empty,
            c.player.getInventory().selectedSlot, net.minecraft.screen.slot.SlotActionType.SWAP, c.player);
        c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(c.player.getInventory().selectedSlot));
    }
}
