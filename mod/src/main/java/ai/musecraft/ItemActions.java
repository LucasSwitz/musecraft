package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Item use runs on vanilla client ticks; controller polls renew a bounded lease. */
public final class ItemActions {
    private static final Gson JSON = new Gson();
    private static String id;
    private static long leaseUntil, started, stoppedAt, inventoryRevision;
    private static int startTick, ticks, holdTicks;
    private static boolean continuous, released, finiteConsumable, consuming, consumed;
    private static JsonElement before;
    private static String item;
    private static final Map<BlockPos,String> blocks = new LinkedHashMap<>();
    private static long blockRevision;

    static Map<String,Object> stack(ItemStack s) {
        return Map.of("name",Registries.ITEM.getId(s.getItem()).toString(),"count",s.getCount(),
            "damage",s.getDamage(),"maxDamage",s.getMaxDamage());
    }
    static JsonElement equip(MinecraftClient c, JsonObject p) {
        String name=p.get("item").getAsString();
        var parsed=Identifier.tryParse(name);
        if(parsed==null || !Registries.ITEM.containsId(parsed))throw new IllegalArgumentException("unknown_item:"+name);
        int current=c.player.getInventory().selectedSlot, from=-1;
        for(int i=0;i<36;i++) {
            var s=c.player.getInventory().getStack(i);
            if(!s.isEmpty() && Registries.ITEM.getId(s.getItem()).toString().equals(name)) {
                if(from<0)from=i;
                if(i==current){from=i;break;}
            }
        }
        if(from<0)throw new IllegalStateException("missing_item:"+name);
        int to=from<9?from:current;
        if(from!=to)c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,from,to,
            net.minecraft.screen.slot.SlotActionType.SWAP,c.player);
        c.player.getInventory().selectedSlot=to;
        c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(to));
        return JSON.toJsonTree(Map.of("slot",to,"from",from,"item",stack(c.player.getMainHandStack())));
    }
    private static boolean playerObstructsPlacement(MinecraftClient c,ItemStack held,net.minecraft.util.hit.BlockHitResult hit) {
        if(!(held.getItem() instanceof net.minecraft.item.BlockItem item))return false;
        var context=item.getPlacementContext(new net.minecraft.item.ItemPlacementContext(c.player,Hand.MAIN_HAND,held,hit));
        if(context==null||!context.canPlace())return false;
        var state=item.getBlock().getPlacementState(context);
        if(state==null||!state.canPlaceAt(c.world,context.getBlockPos()))return false;
        for(Box shape:state.getCollisionShape(c.world,context.getBlockPos(),net.minecraft.block.ShapeContext.of(c.player)).getBoundingBoxes())
            if(shape.offset(context.getBlockPos()).intersects(c.player.getBoundingBox()))return true;
        return false;
    }
    static JsonElement start(MinecraftClient c, JsonObject p) {
        if(id!=null)throw new IllegalStateException("item_use_in_progress");
        var held=c.player.getMainHandStack();
        item=p.get("item").getAsString();
        if(!Registries.ITEM.getId(held.getItem()).toString().equals(item))throw new IllegalStateException("selected_item_changed");

        int duration=held.getMaxUseTime(c.player);
        finiteConsumable=held.getUseAction()==net.minecraft.util.UseAction.EAT
            || held.getUseAction()==net.minecraft.util.UseAction.DRINK;
        consuming=false;consumed=false;
        int holdMs=p.has("holdMs")?p.get("holdMs").getAsInt():0;
        if(holdMs<0 || holdMs>60000)throw new IllegalArgumentException("holdMs_must_be_0_to_60000");
        if(duration>=72000 && holdMs==0)throw new IllegalArgumentException("holdMs_required_for_charged_or_held_item");
        blocks.clear();
        before=JSON.toJsonTree(MusecraftClient.inventory(c));
        inventoryRevision=MusecraftClient.inventoryRevision;blockRevision=MusecraftClient.revision;
        net.minecraft.util.ActionResult result;
        if(p.has("entityId")) {
            var target=c.world.getEntityById(p.get("entityId").getAsInt());
            if(target==null || !target.getUuidAsString().equals(p.get("uuid").getAsString()))throw new IllegalStateException("entity_identity_changed");
            result=WorldActions.dispatch(c,"interactTarget",p).getAsJsonObject().get("accepted").getAsBoolean()
                ?net.minecraft.util.ActionResult.SUCCESS:net.minecraft.util.ActionResult.PASS;
        } else if(p.has("x")) {
            BlockPos target=new BlockPos(p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt());
            if(held.getItem() instanceof net.minecraft.item.BlockItem blockItem && c.world.getBlockState(target).isReplaceable()) {
                // A replaceable target names the desired placement cell. Reuse the
                // builder's vanilla support-face selection instead of aiming at air.
                blocks.put(target,c.world.getBlockState(target).toString());
                blocks.put(target.up(),c.world.getBlockState(target.up()).toString());
                JsonObject placement=new JsonObject();
                placement.addProperty("x",target.getX());placement.addProperty("y",target.getY());placement.addProperty("z",target.getZ());
                placement.addProperty("block",Registries.BLOCK.getId(blockItem.getBlock()).toString());
                result=WorldActions.dispatch(c,"placeAt",placement).getAsJsonObject().get("accepted").getAsBoolean()
                    ?net.minecraft.util.ActionResult.SUCCESS:net.minecraft.util.ActionResult.PASS;
            } else {
            var hit=ItemUseGeometry.hit(c,p,target,c.player.getEyePos());
            WorldActions.aim(c,hit.getPos());
            WorldActions.orientDoor(c,held,hit);
            c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(c.player.getYaw(),c.player.getPitch(),c.player.isOnGround()));
            for(BlockPos pos:List.of(target,hit.getBlockPos().offset(hit.getSide())))blocks.put(pos,c.world.getBlockState(pos).toString());
            boolean placingAgainstContainer=held.getItem() instanceof net.minecraft.item.BlockItem
                && c.world.getBlockState(target).createScreenHandlerFactory(c.world,target)!=null;
            result=placingAgainstContainer?WorldActions.placeAgainst(c,hit)
                :c.interactionManager.interactBlock(c.player,Hand.MAIN_HAND,hit);
            if(!result.isAccepted()&&playerObstructsPlacement(c,held,hit))
                throw new IllegalStateException("placement_blocked_by_player");
            if(!result.isAccepted())result=c.interactionManager.interactItem(c.player,Hand.MAIN_HAND);
            }
        } else result=c.interactionManager.interactItem(c.player,Hand.MAIN_HAND);
        if(!result.isAccepted() && !c.player.isUsingItem())throw new IllegalStateException("item_use_rejected");
        id=UUID.randomUUID().toString();started=System.currentTimeMillis();leaseUntil=started+5000;
        ticks=0;startTick=0;holdTicks=(int)Math.ceil(holdMs/50.0);continuous=c.player.isUsingItem();released=false;stoppedAt=continuous?0:started;
        // Feeding an animal is an immediate interaction, not held consumption.
        finiteConsumable=finiteConsumable&&continuous;
        if(result.shouldSwingHand())c.player.swingHand(Hand.MAIN_HAND);
        return JSON.toJsonTree(Map.of("id",id,"continuous",continuous));
    }
    static JsonElement poll(MinecraftClient c, JsonObject p) {
        if(id==null || !id.equals(p.get("id").getAsString()))throw new IllegalStateException("item_use_ended");
        leaseUntil=System.currentTimeMillis()+5000;
        var after=JSON.toJsonTree(MusecraftClient.inventory(c));
        boolean inventoryChanged=MusecraftClient.inventoryRevision>inventoryRevision && !before.equals(after);
        boolean consumptionConfirmed=consumed && (c.player.isCreative()
            || inventoryChanged && itemCount(after)<itemCount(before));
        List<List<Integer>> changed=new ArrayList<>();
        blocks.forEach((pos,state)->{if(!state.equals(c.world.getBlockState(pos).toString()) && MusecraftClient.confirmed(pos,blockRevision))changed.add(List.of(pos.getX(),pos.getY(),pos.getZ()));});
        boolean done=stoppedAt>0 && ((finiteConsumable?consumptionConfirmed:inventoryChanged || !changed.isEmpty())
            || System.currentTimeMillis()-stoppedAt>=1500);
        boolean blocked=done&&finiteConsumable&&!consumptionConfirmed;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",id);out.put("status",done?(blocked?"blocked":"completed"):"running");out.put("item",item);out.put("usingItem",c.player.isUsingItem());
        out.put("elapsedMs",System.currentTimeMillis()-started);
        if(done) {
            out.put("reason",finiteConsumable?(consumptionConfirmed?"consumed":consumed?"consumption_unconfirmed":"consumption_interrupted"):"observed");
            if(finiteConsumable)out.put("explanation",consumptionConfirmed?"Consumed one item and released use."
                :consumed?"The consumption animation finished, but the server has not confirmed consumption. Check inventory and hunger before retrying."
                :"Item use stopped before consumption finished. No consumption was confirmed; check current hunger and inventory before retrying.");
            out.put("confirmation",blocked?"unconfirmed":!changed.isEmpty()?"server_block_update":inventoryChanged?"server_inventory_update":consumptionConfirmed?"creative_consumption":"submitted");
            out.put("inventoryBefore",before);out.put("inventoryAfter",after);out.put("changedBlocks",changed);out.put("released",released);
        }
        return JSON.toJsonTree(out);
    }
    private static int itemCount(JsonElement inventory) {
        int count=0;
        for(var entry:inventory.getAsJsonArray()) {
            var stack=entry.getAsJsonObject();
            if(stack.get("name").getAsString().equals(item))count+=stack.get("count").getAsInt();
        }
        return count;
    }
    // Called around vanilla consumption, before held input can start another item
    // or switch to the offhand. Inventory confirmation remains server-owned.
    public static void consuming(MinecraftClient c) {
        consuming=id!=null && finiteConsumable && stoppedAt==0 && c.player!=null
            && c.player.getActiveHand()==Hand.MAIN_HAND
            && Registries.ITEM.getId(c.player.getActiveItem().getItem()).toString().equals(item);
    }
    public static void consumed() {
        if(consuming){consumed=true;stoppedAt=System.currentTimeMillis();released=true;consuming=false;}
    }
    public static boolean maintainingUse() { return id!=null && continuous && stoppedAt==0 && System.currentTimeMillis()<leaseUntil; }
    static void tick(MinecraftClient c) {
        if(id==null)return;
        if(System.currentTimeMillis()>=leaseUntil){stop(c);return;}
        if(continuous && stoppedAt==0) {
            ticks++;
            if(!c.player.isUsingItem())stoppedAt=System.currentTimeMillis();
            else if(!finiteConsumable && holdTicks>0 && ticks-startTick>=holdTicks) {
                c.interactionManager.stopUsingItem(c.player);released=true;stoppedAt=System.currentTimeMillis();
            }
        }
    }
    static void stop(MinecraftClient c) {
        if(id!=null && c.player!=null && c.interactionManager!=null && c.player.isUsingItem()) {
            // Cancellation must not release a partially drawn bow into a shot.
            int slot=c.player.getInventory().selectedSlot;
            c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket((slot+1)%9));
            c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
            c.player.clearActiveItem();
        }
        id=null;leaseUntil=0;consuming=false;blocks.clear();
    }
}
