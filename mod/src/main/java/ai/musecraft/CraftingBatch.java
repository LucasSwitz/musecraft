package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.recipe.*;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.*;
import net.minecraft.util.Identifier;

/** Recipe-book fill, bounded output clicks and server-confirmed collection. */
final class CraftingBatch {
    private static final Gson JSON=new Gson();
    private static CraftingBatch current;
    private final String id=UUID.randomUUID().toString();
    private final ScreenHandler handler;
    private final AbstractRecipeScreenHandler<?,?> grid;
    private final RecipeEntry<?> entry;
    private final CraftingRecipe recipe;
    private final ItemStack output;
    private final int requested,produced;
    private final long started=System.currentTimeMillis();
    private final CompletableFuture<Void> stopped=new CompletableFuture<>();
    private long leaseUntil=started+5000;
    private int crafted,pendingCrafts,transactions,batches;
    private String status="running",reason,phase="ready",transaction;

    private CraftingBatch(MinecraftClient c,JsonObject p) {
        requested=p.get("count").getAsInt();
        if(requested<1||requested>256)throw new IllegalArgumentException("invalid_craft_count");
        handler=ContainerState.admit(c,p);
        if(!(handler instanceof CraftingScreenHandler)&&!(handler instanceof PlayerScreenHandler))
            throw new IllegalStateException("crafting_grid_required");
        grid=(AbstractRecipeScreenHandler<?,?>)handler;
        entry=c.player.networkHandler.getRecipeManager().get(Identifier.of(p.get("recipe").getAsString()))
            .orElseThrow(()->new IllegalArgumentException("unknown_recipe"));
        if(!(entry.value() instanceof CraftingRecipe value)||value.isIgnoredInRecipeBook())
            throw new IllegalArgumentException("not_a_crafting_recipe");
        recipe=value;
        if(!c.player.getRecipeBook().contains(entry))throw new IllegalStateException("recipe_not_unlocked");
        if(!recipe.fits(grid.getCraftingWidth(),grid.getCraftingHeight()))throw new IllegalStateException("crafting_table_required");
        for(var slot:handler.slots)if(slot.inventory!=c.player.getInventory()&&!slot.getStack().isEmpty())
            throw new IllegalStateException("crafting_grid_not_empty");
        output=recipe.getResult(c.world.getRegistryManager()).copy();
        if(output.isEmpty())throw new IllegalStateException("unexpected_recipe_output");
        produced=((requested+output.getCount()-1)/output.getCount())*output.getCount();
        var matcher=new RecipeMatcher();
        for(int i=0;i<36;i++)matcher.addUnenchantedInput(c.player.getInventory().getStack(i));
        if(matcher.countCrafts(entry,null)<produced/output.getCount())throw new IllegalStateException("missing_ingredients");
        int capacity=0;
        for(var slot:handler.slots)if(inventory(c,slot)&&slot.canInsert(output)) {
            var stack=slot.getStack();
            if(stack.isEmpty()||ItemStack.areItemsAndComponentsEqual(stack,output))
                capacity+=Math.min(output.getMaxCount(),slot.getMaxItemCount(output))-stack.getCount();
        }
        if(capacity<produced)throw new IllegalStateException("inventory_full");
    }
    static boolean running(){return current!=null&&(current.status.equals("running")||current.status.equals("cancelling"));}
    static CompletableFuture<Void> whenStopped(){return running()?current.stopped:CompletableFuture.completedFuture(null);}
    static JsonElement status(){return JSON.toJsonTree(current==null?Map.of("status","idle"):current.snapshot());}
    static JsonElement start(MinecraftClient c,JsonObject p) {
        if(running())throw new IllegalStateException("craft_batch_running");
        var next=new CraftingBatch(c,p);
        current=next;
        next.fill(c);
        return JSON.toJsonTree(next.snapshot());
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(current==null||!current.id.equals(p.get("id").getAsString()))throw new IllegalStateException("craft_batch_changed");
        current.leaseUntil=System.currentTimeMillis()+5000;
        tick(c);
        return JSON.toJsonTree(current.snapshot());
    }
    static void tick(MinecraftClient c) {
        if(!running())return;
        if(c.player==null||c.world==null||c.player.currentScreenHandler!=current.handler) {
            current.status="cancelled";current.reason="container_changed";current.stopped.complete(null);return;
        }
        if(current.status.equals("cancelling")) {
            // The client deliberately does not predict inventory clicks. Wait for
            // the entire server-confirmed transfer before closing; otherwise a
            // late cursor packet can attach to the new player screen handler.
            if(current.transaction!=null) {
                var state=InventoryActions.status(c);
                if(state.get("status").equals("running"))return;
                if(state.get("status").equals("completed")&&current.phase.equals("take"))
                    current.crafted+=current.pendingCrafts*current.output.getCount();
            }
            current.status="cancelled";current.close(c);current.stopped.complete(null);return;
        }
        if(System.currentTimeMillis()>=current.leaseUntil){stop(c,"craft_lease_expired");return;}
        try {current.advance(c);}
        catch(Exception e){current.status="blocked";current.reason=e.getMessage();current.close(c);}
    }
    static void stop(MinecraftClient c,String reason) {
        if(!running())return;
        current.status="cancelling";current.reason=reason;
        tick(c);
    }
    private void close(MinecraftClient c) {
        if(c.player!=null&&c.player.currentScreenHandler==handler) {
            c.player.closeHandledScreen();c.setScreen(null);
        }
    }
    private boolean inventory(MinecraftClient c,Slot slot) {
        return slot.inventory==c.player.getInventory()&&slot.getIndex()<36;
    }
    private List<Slot> inputs() {
        var result=new ArrayList<Slot>();
        for(int i=1;i<=grid.getCraftingWidth()*grid.getCraftingHeight();i++)result.add(handler.getSlot(i));
        return result;
    }
    private CraftingRecipeInput.Positioned input() {
        return CraftingRecipeInput.createPositioned(grid.getCraftingWidth(),grid.getCraftingHeight(),
            inputs().stream().map(slot->slot.getStack().copy()).toList());
    }
    private Slot destination(MinecraftClient c,ItemStack stack,int minimum) {
        // Merge into an existing stack before spending an empty slot.
        for(boolean empty:new boolean[]{false,true})for(var slot:handler.slots) {
            var held=slot.getStack();
            if(inventory(c,slot)&&held.isEmpty()==empty&&slot.canInsert(stack)
                    &&(held.isEmpty()||ItemStack.areItemsAndComponentsEqual(held,stack))
                    &&Math.min(stack.getMaxCount(),slot.getMaxItemCount(stack))-held.getCount()>=minimum)return slot;
        }
        throw new IllegalStateException("inventory_full; inspect partial crafting progress");
    }
    private void pending(JsonElement started,String phase) {
        transaction=started.getAsJsonObject().get("id").getAsString();this.phase=phase;transactions++;
    }
    private void fill(MinecraftClient c) {
        var matcher=new RecipeMatcher();
        for(int i=0;i<36;i++)matcher.addUnenchantedInput(c.player.getInventory().getStack(i));
        if(!matcher.match(recipe,null))throw new IllegalStateException("missing_ingredients");
        pending(InventoryActions.begin(c,handler,Map.of(grid.getCraftingResultSlotIndex(),output)),"fill");
        // Filling all moves ingredients into the grid without consuming them.
        // The output transaction below takes only the requested number of crafts.
        c.player.networkHandler.sendPacket(new CraftRequestC2SPacket(handler.syncId,entry,true));
    }
    private void advance(MinecraftClient c) {
        if(transaction!=null) {
            var state=InventoryActions.status(c);
            if(!transaction.equals(state.get("id")))throw new IllegalStateException("inventory_transaction_changed");
            if(state.get("status").equals("running"))return;
            if(!state.get("status").equals("completed"))throw new IllegalStateException("inventory_"+state.get("status"));
            transaction=null;
            if(phase.equals("take")){crafted+=pendingCrafts*output.getCount();pendingCrafts=0;batches++;}
            phase=phase.equals("return")?"clearing":"ready";
        }
        if(phase.equals("ready")&&crafted<produced) {
            var placed=input();
            if(recipe.matches(placed.input(),c.world)) {take(c,placed);return;}
            phase="clearing";
        } else if(crafted>=produced)phase="clearing";
        if(phase.equals("clearing")) {
            for(var slot:inputs())if(!slot.getStack().isEmpty()) {
                var stack=slot.getStack();var dest=destination(c,stack,1);
                int count=Math.min(stack.getCount(),Math.min(stack.getMaxCount(),dest.getMaxItemCount(stack))-dest.getStack().getCount());
                var p=new JsonObject();p.addProperty("token",ContainerState.token(handler));p.addProperty("revision",handler.getRevision());
                p.addProperty("from",slot.id);p.addProperty("to",dest.id);p.addProperty("count",count);
                pending(InventoryActions.move(c,p),"return");return;
            }
            if(crafted>=produced){status="completed";reason="server_inventory_update";return;}
            fill(c);
        }
    }
    private void take(MinecraftClient c,CraftingRecipeInput.Positioned placed) {
        if(!ItemStack.areEqual(handler.getSlot(grid.getCraftingResultSlotIndex()).getStack(),output))
            throw new IllegalStateException("unexpected_recipe_output");
        var dest=destination(c,output,output.getCount());
        int crafts=Math.min((produced-crafted)/output.getCount(),
            (Math.min(output.getMaxCount(),dest.getMaxItemCount(output))-dest.getStack().getCount())/output.getCount());
        for(var slot:inputs())if(!slot.getStack().isEmpty())crafts=Math.min(crafts,slot.getStack().getCount());
        var remainders=recipe.getRemainder(placed.input());
        // Container recipes can leave buckets or bottles in the grid. Resolve
        // those after each craft instead of assuming stack consumption.
        if(remainders.stream().anyMatch(stack->!stack.isEmpty()))crafts=1;
        if(crafts<1)throw new IllegalStateException("craft_output_capacity_changed");
        var expected=new LinkedHashMap<Integer,ItemStack>();
        var remaining=new ArrayList<ItemStack>();
        for(var slot:inputs()) {
            var stack=slot.getStack();var after=stack.copyWithCount(Math.max(0,stack.getCount()-crafts));
            int index=slot.id-1,x=index%grid.getCraftingWidth()-placed.left(),y=index/grid.getCraftingWidth()-placed.top();
            if(x>=0&&x<placed.input().getWidth()&&y>=0&&y<placed.input().getHeight()) {
                var rest=remainders.get(y*placed.input().getWidth()+x);
                if(!rest.isEmpty()) {
                    if(after.isEmpty())after=rest.copy();
                    else if(ItemStack.areItemsAndComponentsEqual(after,rest))after.increment(rest.getCount());
                }
            }
            expected.put(slot.id,after);remaining.add(after);
        }
        var afterInput=CraftingRecipeInput.create(grid.getCraftingWidth(),grid.getCraftingHeight(),remaining);
        expected.put(grid.getCraftingResultSlotIndex(),recipe.matches(afterInput,c.world)?output:ItemStack.EMPTY);
        int count=crafts*output.getCount();
        expected.put(dest.id,output.copyWithCount(dest.getStack().getCount()+count));
        pendingCrafts=crafts;
        pending(InventoryActions.begin(c,handler,expected),"take");
        // Normal output pickups, capped by one destination stack. Do not use
        // shift-click: it could craft beyond the requested additional count.
        for(int i=0;i<crafts;i++)InventoryActions.click(c,handler,grid.getCraftingResultSlotIndex(),0,SlotActionType.PICKUP,
            i==0?ItemStack.EMPTY:output.copyWithCount(i*output.getCount()));
        InventoryActions.click(c,handler,dest.id,0,SlotActionType.PICKUP,output.copyWithCount(count));
    }
    private Map<String,Object> snapshot() {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",id);result.put("status",status);result.put("reason",reason);result.put("phase",phase);
        result.put("item",Registries.ITEM.getId(output.getItem()).toString());result.put("recipe",entry.id().toString());
        result.put("requested",requested);result.put("crafted",crafted);result.put("remaining",produced-crafted);
        result.put("elapsedMs",System.currentTimeMillis()-started);result.put("transactions",transactions);result.put("batches",batches);
        return result;
    }
}
