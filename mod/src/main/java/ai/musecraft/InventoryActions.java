package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/** Normal survival clicks, without optimistic client inventory mutation. */
final class InventoryActions {
    private static final Gson JSON=new Gson();
    private static ScreenHandler handler;
    private static final Map<Integer,ItemStack> expected=new LinkedHashMap<>();
    private static String id, state;
    private static long deadline, before;

    static Map<String,Object> status(MinecraftClient c) {
        if (id==null) return Map.of("status","idle");
        if ("running".equals(state)) {
            if (c.player.currentScreenHandler!=handler) state="container_changed";
            else if (MusecraftClient.inventoryRevision>before && handler.getCursorStack().isEmpty()
                    && expected.entrySet().stream().allMatch(e -> matches(e.getKey(),e.getValue()))) state="completed";
            else if (System.currentTimeMillis()>deadline) state="uncertain";
        }
        return Map.of("id",id,"status",state,"confirmation","server_inventory_update");
    }
    private static boolean matches(int slot,ItemStack wanted) {
        var actual=handler.getSlot(slot).getStack();
        if(ItemStack.areEqual(actual,wanted))return true;
        // The server can consume the first fuel before sending its slot snapshot.
        // Require the exact source decrement, an empty cursor, server updates and
        // a burning furnace; only one fuel item may differ from the planned move.
        if(slot==1&&handler instanceof net.minecraft.screen.AbstractFurnaceScreenHandler furnace
                &&furnace.isBurning()&&!wanted.isEmpty()) {
            if(wanted.getCount()==1&&actual.isEmpty())return true;
            return ItemStack.areItemsAndComponentsEqual(actual,wanted)&&actual.getCount()==wanted.getCount()-1;
        }
        return false;
    }
    static void requireSettled(MinecraftClient c) {
        if ("running".equals(status(c).get("status"))) throw new IllegalStateException("inventory_transaction_pending");
    }
    static JsonElement begin(MinecraftClient c, ScreenHandler h, Map<Integer,ItemStack> after) {
        handler=h; expected.clear(); after.forEach((slot,stack)->expected.put(slot,stack.copy()));
        id=UUID.randomUUID().toString(); state="running"; before=MusecraftClient.inventoryRevision;
        deadline=System.currentTimeMillis()+7000;
        return JSON.toJsonTree(Map.of("id",id,"status",state));
    }
    static void click(MinecraftClient c, ScreenHandler h, int slot, int button, SlotActionType action, ItemStack cursorBefore) {
        // Report pre-click cursor and no predicted slots. Vanilla server sends the
        // actual changes; only those packets can satisfy our completion predicate.
        c.player.networkHandler.sendPacket(new ClickSlotC2SPacket(h.syncId,h.getRevision(),slot,button,action,
            cursorBefore.copy(),new Int2ObjectOpenHashMap<>()));
    }
    static JsonElement move(MinecraftClient c, JsonObject p) {
        var h=ContainerState.admit(c,p);
        var source=ContainerState.slot(h,p.get("from").getAsInt());
        var from=source.getStack().copy();
        int count=p.get("count").getAsInt();
        if (count<1 || count>from.getCount() || !source.canTakeItems(c.player)) throw new IllegalStateException("insufficient_items");
        if ("unsupported".equals(ContainerState.role(c,h,source))) throw new IllegalStateException("unsupported_container");
        var remainder=from.copyWithCount(from.getCount()-count);
        if (p.has("drop") && p.get("drop").getAsBoolean()) {
            if (source.inventory!=c.player.getInventory()) throw new IllegalStateException("drop_requires_player_inventory");
            var result=begin(c,h,Map.of(source.id,remainder));
            if (count==from.getCount()) click(c,h,source.id,1,SlotActionType.THROW,ItemStack.EMPTY);
            else for (int i=0;i<count;i++) click(c,h,source.id,0,SlotActionType.THROW,ItemStack.EMPTY);
            return result;
        }
        var dest=ContainerState.slot(h,p.get("to").getAsInt());
        var to=dest.getStack().copy();
        if (source.id==dest.id || !dest.canInsert(from) || "unsupported".equals(ContainerState.role(c,h,dest))
                || (!to.isEmpty() && !ItemStack.areItemsAndComponentsEqual(from,to))
                || to.getCount()+count>Math.min(from.getMaxCount(),dest.getMaxItemCount(from))) throw new IllegalStateException("destination_cannot_accept_items");
        if ("output".equals(ContainerState.role(c,h,source)) && count!=from.getCount()) throw new IllegalStateException("take_entire_recipe_output");
        var result=begin(c,h,Map.of(source.id,remainder,dest.id,from.copyWithCount(to.getCount()+count)));
        // Submit the bounded transaction in one client tick. Cancellation cannot
        // strand a half-written click sequence on the cursor.
        click(c,h,source.id,0,SlotActionType.PICKUP,ItemStack.EMPTY);
        if (count==from.getCount()) click(c,h,dest.id,0,SlotActionType.PICKUP,from);
        else {
            for (int i=0;i<count;i++) click(c,h,dest.id,1,SlotActionType.PICKUP,from.copyWithCount(from.getCount()-i));
            click(c,h,source.id,0,SlotActionType.PICKUP,remainder);
        }
        return result;
    }
    static JsonElement swap(MinecraftClient c, JsonObject p) {
        var h=ContainerState.admit(c,p);
        var a=ContainerState.slot(h,p.get("from").getAsInt());
        var b=ContainerState.slot(h,p.get("to").getAsInt());
        var first=a.getStack().copy(); var second=b.getStack().copy();
        if (a.id==b.id || a.inventory!=c.player.getInventory() || b.inventory!=c.player.getInventory()) throw new IllegalArgumentException("swap_requires_distinct_player_slots");
        if (first.isEmpty() && second.isEmpty()) throw new IllegalStateException("both_slots_empty");
        if (!a.canTakeItems(c.player) || !b.canTakeItems(c.player)
                || (!first.isEmpty() && (!b.canInsert(first) || first.getCount()>b.getMaxItemCount(first)))
                || (!second.isEmpty() && (!a.canInsert(second) || second.getCount()>a.getMaxItemCount(second)))) throw new IllegalStateException("equipment_slot_rejects_item");
        if (!first.isEmpty() && !second.isEmpty() && ItemStack.areItemsAndComponentsEqual(first,second)) throw new IllegalStateException("matching_stacks; use moveItem to merge");
        var result=begin(c,h,Map.of(a.id,second,b.id,first));
        if (first.isEmpty()) {click(c,h,b.id,0,SlotActionType.PICKUP,ItemStack.EMPTY);click(c,h,a.id,0,SlotActionType.PICKUP,second);}
        else {
            click(c,h,a.id,0,SlotActionType.PICKUP,ItemStack.EMPTY);click(c,h,b.id,0,SlotActionType.PICKUP,first);
            if (!second.isEmpty()) click(c,h,a.id,0,SlotActionType.PICKUP,second);
        }
        return result;
    }
}
