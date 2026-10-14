package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.Slot;

/** Slot identities belong to a particular open handler, never to a screen title. */
final class ContainerState {
    private static ScreenHandler observed;
    private static String token;
    static String token(ScreenHandler handler) {
        if (observed != handler) { observed=handler; token=UUID.randomUUID().toString(); }
        return token;
    }
    static String type(ScreenHandler h) {
        if (h instanceof PlayerScreenHandler) return "player";
        return Registries.SCREEN_HANDLER.getId(h.getType()).toString();
    }
    static String role(MinecraftClient c, ScreenHandler h, Slot s) {
        if (s.inventory == c.player.getInventory()) return s.getIndex()<36?"inventory":"equipment";
        if (h instanceof CraftingScreenHandler || h instanceof PlayerScreenHandler) return s.id==0?"output":"crafting";
        if (h instanceof AbstractFurnaceScreenHandler) return switch(s.id) {case 0 -> "input"; case 1 -> "fuel"; default -> "output";};
        if (h instanceof GenericContainerScreenHandler || h instanceof Generic3x3ContainerScreenHandler
                || h instanceof ShulkerBoxScreenHandler || h instanceof HopperScreenHandler) return "storage";
        return "unsupported";
    }
    static Map<String,Object> stack(ItemStack s) {
        var result=new LinkedHashMap<String,Object>(ItemActions.stack(s));
        result.put("maxCount",s.getMaxCount());
        // Compact merge hint for the controller. Native admission still compares
        // complete components, so a hash collision cannot permit a wrong merge.
        result.put("components",s.isEmpty()?"":Integer.toUnsignedString(s.getComponents().hashCode(),16));
        return result;
    }
    static Map<String,Object> snapshot(MinecraftClient c) {
        var h=c.player.currentScreenHandler;
        var result=new LinkedHashMap<String,Object>();
        result.put("token",token(h)); result.put("syncId",h.syncId); result.put("revision",h.getRevision());
        result.put("serverRevision",MusecraftClient.inventoryRevision); result.put("type",type(h));
        result.put("title",c.currentScreen==null?null:c.currentScreen.getTitle().getString());
        result.put("cursor",stack(h.getCursorStack()));
        var slots=new ArrayList<Map<String,Object>>();
        for (var s:h.slots) {
            var entry=new LinkedHashMap<String,Object>(stack(s.getStack()));
            entry.put("id",s.id); entry.put("role",role(c,h,s));
            entry.put("limit",s.getMaxItemCount());
            if (s.inventory==c.player.getInventory()) entry.put("inventorySlot",s.getIndex());
            slots.add(entry);
        }
        result.put("slots",slots);
        if (h instanceof AbstractFurnaceScreenHandler f) result.put("furnace",Map.of(
            "burning",f.isBurning(),"fuelProgress",f.getFuelProgress(),"cookProgress",f.getCookProgress()));
        result.put("transaction",InventoryActions.status(c));
        return result;
    }
    static ScreenHandler admit(MinecraftClient c, JsonObject p) {
        var h=c.player.currentScreenHandler;
        if (!p.get("token").getAsString().equals(token(h))) throw new IllegalStateException("container_changed");
        if (p.get("revision").getAsInt()!=h.getRevision()) throw new IllegalStateException("inventory_changed; inspect again");
        if (!h.getCursorStack().isEmpty()) throw new IllegalStateException("cursor_occupied; close screen to return held items");
        InventoryActions.requireSettled(c);
        return h;
    }
    static Slot slot(ScreenHandler h, int id) {
        if (id<0 || id>=h.slots.size()) throw new IllegalArgumentException("invalid_slot");
        return h.getSlot(id);
    }
}
