package ai.musecraft;

import baritone.api.*;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.*;
import baritone.pathing.movement.CalculationContext;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Action ownership and observed outcomes around upstream mining and discovery processes. */
public final class BaritoneTasks {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static volatile String status="idle",kind,item;
    private static String id,reason;
    private static volatile BlockVolume area;
    private static volatile List<ItemStack> equipment=List.of();
    private static volatile List<BlockPos> targets=List.of();
    private static IBaritoneProcess process;
    private static java.util.concurrent.CompletableFuture<BlockOptionalMetaLookup> preparing;
    private static BaritoneSettingsScope settings;
    private static int initial,requested,held,ticks,inactiveTicks;
    private static long leaseUntil;
    private static List<ChunkPos> chunks=List.of();
    private static Block wantedBlock;
    static boolean running(){return status.equals("running");}
    static JsonElement start(MinecraftClient c,JsonObject p) throws Exception {
        stop(c,"cancelled","replaced");
        Navigation.stop(c,"cancelled","native_task_started");
        var engine=BaritoneNavigation.engine();BaritoneProtection.start(c);
        kind=p.get("kind").getAsString();
        if(!Set.of("gather","findBlock","explore").contains(kind))throw new IllegalArgumentException("unknown_native_task");
        var center=p.has("near")?vector(p.getAsJsonArray("near")):c.player.getPos();
        int radius=p.has("radius")?p.get("radius").getAsInt():16;
        if(radius<1||radius>256||kind.equals("gather")&&radius>32)throw new IllegalArgumentException("invalid_search_radius");
        area=BlockVolume.around(center,radius);preparing=null;process=null;targets=List.of();chunks=List.of();item=null;wantedBlock=null;
        if(p.has("allowStructures")&&p.get("allowStructures").getAsBoolean())throw new IllegalArgumentException("structure_harvesting_forbidden");
        initial=held=requested=ticks=inactiveTicks=0;equipment=inventory(c);
        id=UUID.randomUUID().toString();status="running";reason=null;leaseUntil=System.currentTimeMillis()+5000;
        try {
        settings=new BaritoneSettingsScope();var s=BaritoneAPI.getSettings();
        settings.set(s.allowBreak,kind.equals("gather"));settings.set(s.allowPlace,false);
        settings.set(s.allowInventory,kind.equals("gather"));settings.set(s.autoTool,kind.equals("gather"));
        settings.set(s.exploreForBlocks,false);settings.set(s.legitMine,false);
        settings.set(s.rightClickContainerOnArrival,false);settings.set(s.mineScanDroppedItems,true);
        settings.set(s.mineMaxOreLocationsCount,64);settings.set(s.maxCachedWorldScanCount,4096);
        settings.set(s.notificationOnExploreFinished,false);settings.set(s.notificationOnMineFail,false);
        var protectedTypes=new HashSet<>(s.blocksToDisallowBreaking.value);protectedTypes.addAll(s.blocksToAvoidBreaking.value);
        if(p.has("protectedBlocks"))for(var name:p.getAsJsonArray("protectedBlocks"))protectedTypes.add(block(name.getAsString()));
        settings.set(s.blocksToDisallowBreaking,List.copyOf(protectedTypes));
            switch(kind) {
                case "gather" -> {
                    item=p.get("item").getAsString();requested=p.get("count").getAsInt();
                    if(requested<1||requested>2304||!Registries.ITEM.containsId(Identifier.of(item)))throw new IllegalArgumentException("invalid_gather_request");
                    initial=held=count(c);
                    if(!hasPickupSpace(c)){stop(c,"blocked","inventory_full");break;}
                    var sources=Registries.BLOCK.stream().filter(b->HarvestDrops.potential(b.getDefaultState(),item)).toList();
                    if(sources.isEmpty()){stop(c,"blocked","no_mining_source");break;}
                    // Native quantity counts total held; our public contract requests additional items.
                    String wanted=item;
                    preparing=java.util.concurrent.CompletableFuture.supplyAsync(()->new BlockOptionalMetaLookup(sources) {
                        @Override public boolean has(ItemStack stack){return Registries.ITEM.getId(stack.getItem()).toString().equals(wanted);}
                    });
                }
                case "findBlock" -> {
                    wantedBlock=block(p.get("block").getAsString());Block wanted=wantedBlock;
                    preparing=java.util.concurrent.CompletableFuture.supplyAsync(()->new BlockOptionalMetaLookup(wanted));
                }
                case "explore" -> {
                    int r=p.has("radiusChunks")?p.get("radiusChunks").getAsInt():1;
                    if(r<0||r>16)throw new IllegalArgumentException("invalid_exploration_radius");
                    var origin=new ChunkPos(BlockPos.ofFloored(center));var list=new ArrayList<ChunkPos>();var rows=new JsonArray();
                    for(int x=origin.x-r;x<=origin.x+r;x++)for(int z=origin.z-r;z<=origin.z+r;z++) {
                        list.add(new ChunkPos(x,z));var row=new JsonObject();row.addProperty("x",x);row.addProperty("z",z);rows.add(row);
                    }
                    chunks=List.copyOf(list);settings.set(s.worldExploringChunkOffset,0);settings.set(s.exploreChunkSetMinimumSize,1);
                    settings.set(s.exploreMaintainY,c.player.getBlockY());settings.set(s.disableCompletionCheck,false);
                    var file=Files.createTempFile("musecraft-explore-",".json");
                    try {Files.writeString(file,JSON.toJson(rows));engine.getExploreProcess().applyJsonFilter(file,true);}
                    finally {Files.deleteIfExists(file);}
                    process=engine.getExploreProcess();engine.getExploreProcess().explore((int)Math.floor(center.x),(int)Math.floor(center.z));
                }
            }
        } catch(Exception e){stop(c,"blocked","native_task_start_failed");throw e;}
        return snapshot(c);
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(!Objects.equals(id,p.get("id").getAsString()))throw new IllegalStateException("native_task_identity_changed");
        if(running())leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static void tick(MinecraftClient c) {
        if(!running())return;
        if(c.player==null||c.world==null){stop(c,"blocked","client_disconnected");return;}
        if(c.player.isDead()){stop(c,"blocked","player_died");return;}
        if(System.currentTimeMillis()>leaseUntil){stop(c,"cancelled","control_lease_expired");return;}
        if(BaritoneProtection.refusal()!=null){stop(c,"blocked",BaritoneProtection.refusal());return;}
        equipment=inventory(c);ticks++;
        if(kind.equals("gather")) {
            held=count(c);
            if(held>=initial+requested){stop(c,"completed","count_reached");return;}
            if(!hasPickupSpace(c)){stop(c,held>initial?"partial":"blocked","inventory_full");return;}
        }
        if(preparing!=null) {
            if(!preparing.isDone())return;
            try {
                var filter=preparing.join();
                if(kind.equals("gather")&&!prepareHarvestTool(c,filter))return;
                preparing=null;var engine=BaritoneNavigation.engine();
                if(kind.equals("gather")){process=engine.getMineProcess();engine.getMineProcess().mine(initial+requested,filter);}
                else {process=engine.getGetToBlockProcess();engine.getGetToBlockProcess().getToBlock(filter.blocks().getFirst());}
            }catch(RuntimeException e){System.err.println("Musecraft native task: "+e);stop(c,"blocked","native_task_preparation_failed");return;}
        }
        if(ticks<5||process!=null&&process.isActive()){inactiveTicks=0;return;}
        // Native exhaustion can precede the final pickup's inventory packet.
        // Keep the action owned briefly so that receipt can complete its count.
        if(kind.equals("gather")&&++inactiveTicks<20)return;
        if(kind.equals("gather"))stop(c,held>initial?"partial":"blocked","sources_exhausted_or_unreachable");
        else if(kind.equals("explore"))stop(c,cached()==chunks.size()?"completed":"blocked",cached()==chunks.size()?"area_explored":"exploration_unreachable");
        else {
            boolean arrived=targets.stream().anyMatch(p->WorldActions.loaded(c,p)&&c.world.getBlockState(p).isOf(wantedBlock)
                &&new baritone.api.pathing.goals.GoalGetToBlock(p).isInGoal(c.player.getBlockPos()));
            stop(c,arrived?"completed":"blocked",arrived?"block_reached":"block_missing_or_unreachable");
        }
    }
    static void stop(MinecraftClient c,String terminal,String why) {
        if(!running())return;
        status=terminal;reason=why;
        if(process!=null)process.onLostControl();process=null;
        var engine=BaritoneNavigation.engine();engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().clearAllKeys();
        if(settings!=null){settings.close();settings=null;}
        if(c.interactionManager!=null)c.interactionManager.cancelBlockBreaking();
    }
    private static int cached() {
        var data=BaritoneNavigation.engine().getWorldProvider().getCurrentWorld();
        return data==null?0:(int)chunks.stream().filter(p->data.getCachedWorld().isCached(p.x<<4,p.z<<4)).count();
    }
    private static JsonElement snapshot(MinecraftClient c) {
        var r=new LinkedHashMap<String,Object>();r.put("id",id);r.put("status",status);r.put("reason",reason);r.put("backend","baritone");
        r.put("phase",preparing!=null?"preparing":running()&&inactiveTicks>0?"confirming_inventory":"executing");
        r.put("process",switch(kind){case "gather"->"MineProcess";case "explore"->"ExploreProcess";default->"GetToBlockProcess";});
        r.put("pos",c.player==null?null:new double[]{c.player.getX(),c.player.getY(),c.player.getZ()});r.put("targets",targets.stream().limit(32).map(BuildPlan::point).toList());
        if(!kind.equals("explore"))r.put("area",Map.of("min",BuildPlan.point(area.min()),"max",BuildPlan.point(area.max())));
        if(kind.equals("gather")){r.put("item",item);r.put("requested",requested);r.put("initial",initial);r.put("count",held);r.put("gained",Math.max(0,held-initial));r.put("remaining",Math.max(0,initial+requested-held));r.put("confirmation","server_inventory_updates");}
        if("inventory_full".equals(reason))r.put("explanation","Inventory has no room for "+item+". Free space, then retry the remaining count.");
        if(kind.equals("explore")){r.put("chunks",chunks.size());r.put("cached",cached());}
        return JSON.toJsonTree(r);
    }
    private static boolean prepareHarvestTool(MinecraftClient c,BlockOptionalMetaLookup filter) {
        boolean available=false;
        for(var source:filter.blocks()) {
            var state=source.getAnyBlockState();int best=-1;double speed=0;
            for(int slot=0;slot<36;slot++) {
                var stack=c.player.getInventory().getStack(slot);
                if(!validTool(stack,state))continue;
                double value=baritone.utils.ToolSet.calculateSpeedVsBlock(stack,state);
                if(value>speed){speed=value;best=slot;}
            }
            if(best<0)continue;
            available=true;
            if(best>=9) {
                // Native inventory behavior only auto-moves a pickaxe once
                // pathing has begun. Prepare each source's compatible tool.
                ((baritone.Baritone)BaritoneNavigation.engine()).getInventoryBehavior().attemptToPutOnHotbar(best,slot->false);
                return false;
            }
        }
        if(!available){stop(c,"blocked","missing_harvest_tool");return false;}
        return true;
    }

    public static boolean finding(CalculationContext context){return running()&&kind.equals("findBlock")&&context instanceof baritone.process.GetToBlockProcess.GetToBlockCalculationContext;}
    public static List<BlockPos> boundedScan(baritone.api.cache.IWorldScanner scanner,IPlayerContext context,BlockOptionalMetaLookup filter,int max,int yThreshold,int searchRadius) {
        if(!running()||kind.equals("explore"))return scanner.scanChunkRadius(context,filter,max,yThreshold,searchRadius);
        var region=area;var result=new ArrayList<BlockPos>();
        // Upstream scanChunkRadius can keep expanding until a Y-level quota is
        // filled. Enumerate only admitted chunks using its native chunk scanner.
        for(int x=region.min().getX()>>4;x<=region.max().getX()>>4;x++)for(int z=region.min().getZ()>>4;z<=region.max().getZ()>>4;z++) {
            for(var p:scanner.scanChunk(context,filter,new ChunkPos(x,z),Integer.MAX_VALUE,-1))if(region.contains(p))result.add(p);
        }
        return result;
    }
    public static List<BlockPos> filterTargets(CalculationContext context,List<BlockPos> positions,boolean drops) {
        if(!running()||kind.equals("explore"))return positions;
        var result=positions.stream().filter(area::contains).filter(p->!kind.equals("gather")||drops||mayBreak(context,p,context.get(p.getX(),p.getY(),p.getZ())))
            .map(BlockPos::toImmutable).toList();
        if(!drops)targets=result;
        return new ArrayList<>(result);
    }
    static boolean mayBreak(CalculationContext context,BlockPos p,BlockState state) {
        if(!running())return true;
        if(!kind.equals("gather")||!area.contains(p))return false;
        if(ResourceProtection.reason(context,p,state)!=null)return false;
        if(HarvestDrops.potential(state,item)&&equipment.stream().noneMatch(stack->validTool(stack,state)))return false;
        return true;
    }
    public static boolean validTool(ItemStack stack,BlockState state) {
        if(!running()||!kind.equals("gather")||!HarvestDrops.potential(state,item))return true;
        return (!state.isToolRequired()||stack.isSuitableFor(state))&&HarvestDrops.yields(state,item,HarvestDrops.silk(stack));
    }
    private static boolean hasPickupSpace(MinecraftClient c) {
        var wanted=Registries.ITEM.get(Identifier.of(item)).getDefaultStack();
        for(var stack:c.player.getInventory().main)if(stack.isEmpty()||canMerge(wanted,stack))return true;
        // Vanilla pickup can merge into an occupied offhand, but cannot fill an empty one.
        return canMerge(wanted,c.player.getOffHandStack());
    }
    private static boolean canMerge(ItemStack wanted,ItemStack held) {
        return ItemStack.areItemsAndComponentsEqual(wanted,held)&&held.getCount()<held.getMaxCount();
    }
    private static int count(MinecraftClient c){
        int total=c.player.getInventory().main.stream().filter(s->Registries.ITEM.getId(s.getItem()).toString().equals(item)).mapToInt(ItemStack::getCount).sum();
        var offhand=c.player.getOffHandStack();
        return total+(Registries.ITEM.getId(offhand.getItem()).toString().equals(item)?offhand.getCount():0);
    }
    private static List<ItemStack> inventory(MinecraftClient c){return c.player.getInventory().main.stream().map(ItemStack::copy).toList();}
    private static Vec3d vector(JsonArray a){return new Vec3d(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());}
    private static Block block(String name){var id=Identifier.tryParse(name);if(id==null||!Registries.BLOCK.containsId(id))throw new IllegalArgumentException("unknown_block");return Registries.BLOCK.get(id);}
}
