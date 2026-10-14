package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Desired world state and validation only. Baritone owns all construction planning. */
final class BuildPlan {
    static final Set<String> TRANSIENT=Set.of("open","powered","lit","occupied","triggered");
    final LinkedHashMap<BlockPos,JsonObject> requested=new LinkedHashMap<>();
    final LinkedHashMap<BlockPos,JsonObject> cells=new LinkedHashMap<>();
    final Map<BlockPos,JsonObject> supports=new LinkedHashMap<>();
    final Map<BlockPos,String> blocked=new LinkedHashMap<>();
    final Map<BlockPos,BlockPos> primaryFor=new LinkedHashMap<>();
    volatile Set<BlockPos> pendingSecondaries=Set.of();
    BlockPos min,max;
    final String id;
    BuildPlan(MinecraftClient c,JsonObject p) {
        StructureProtection.load(c);
        id=p.get("planId").getAsString();
        var items=p.getAsJsonArray("items");
        if(items.isEmpty()||items.size()>4096)throw new IllegalArgumentException("invalid_build_size");
        for(var element:items) {
            var item=element.getAsJsonObject().deepCopy();var pos=position(item);
            if(requested.put(pos,item)!=null)throw new IllegalArgumentException("duplicate_build_position");
            desired(item);
        }
        cells.putAll(requested);
        for(var entry:requested.entrySet()) {
            var pos=entry.getKey();var item=entry.getValue();var state=desired(item);
            BlockPos other=null;JsonObject paired=item.deepCopy();
            if(state.getBlock() instanceof DoorBlock) {
                boolean upper=WorldActions.properties(state).get("half").equals("upper");
                other=upper?pos.down():pos.up();props(paired).addProperty("half",upper?"lower":"upper");
            }else if(state.getBlock() instanceof BedBlock) {
                var properties=WorldActions.properties(state);boolean head=properties.get("part").equals("head");
                var direction=Direction.byName(properties.get("facing"));
                other=pos.offset(head?direction.getOpposite():direction);props(paired).addProperty("part",head?"foot":"head");
            }
            if(other!=null) {
                paired.add("pos",new Gson().toJsonTree(point(other)));
                var existing=cells.get(other);
                if(existing!=null&&!matches(desired(existing),paired))throw new IllegalArgumentException("plan_footprint_conflict");
                cells.putIfAbsent(other,paired);
                if(secondary(item))primaryFor.put(pos,other);else primaryFor.put(other,pos);
            }
        }
        // Directional items need a clickable backing face from the requested
        // side. Temporary backing cells are part of the upstream schematic,
        // not a separate placement/navigation routine. Never replace final cells.
        String support=null;
        for(var item:List.of(net.minecraft.item.Items.DIRT,net.minecraft.item.Items.COBBLESTONE,net.minecraft.item.Items.NETHERRACK))
            if(c.player.getInventory().contains(new net.minecraft.item.ItemStack(item))){support=Registries.ITEM.getId(item).toString();break;}
        if(support!=null)for(var entry:cells.entrySet()) {
            var wanted=entry.getValue();var below=entry.getKey().down();
            if(!secondary(wanted)&&wanted.has("state")&&wanted.getAsJsonObject("state").has("facing")
                &&!cells.containsKey(below)&&c.world.getBlockState(below).isReplaceable()) {
                var block=new JsonObject();block.add("pos",new Gson().toJsonTree(point(below)));block.addProperty("block",support);supports.put(below,block);
            }
        }
        int x=Integer.MAX_VALUE,y=x,z=x,X=Integer.MIN_VALUE,Y=X,Z=X;
        for(var pos:cells.keySet()){x=Math.min(x,pos.getX());y=Math.min(y,pos.getY());z=Math.min(z,pos.getZ());X=Math.max(X,pos.getX());Y=Math.max(Y,pos.getY());Z=Math.max(Z,pos.getZ());}
        // Elevated blueprints still need a route from the player's starting
        // level, including a block underfoot for the first scaffold placement.
        int feet=c.player.getBlockY();
        min=new BlockPos(x-8,Math.max(c.world.getBottomY(),Math.min(y-4,feet-1)),z-8);
        max=new BlockPos(X+8,Math.min(c.world.getTopY()-1,Math.max(Y+4,feet+1)),Z+8);
        validateVolume();
        for(var entry:cells.entrySet()) {
            var pos=entry.getKey();var wanted=entry.getValue();
            if(!WorldActions.loaded(c,pos)){blocked.put(pos,"terrain_unloaded");continue;}
            var actual=c.world.getBlockState(pos);
            if(matches(actual,wanted))continue;
            var name=Registries.BLOCK.getId(actual.getBlock()).toString();
            if(StructureProtection.reason(pos)!=null)blocked.put(pos,"protected_structure");
            else if(actual.getHardness(c.world,pos)<0)blocked.put(pos,"unbreakable_obstruction");
            else if(c.world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory)blocked.put(pos,"container_contents_unknown");
            else if(!actual.isReplaceable()&&(!wanted.has("replace")||!wanted.get("replace").getAsString().equals(name)))blocked.put(pos,"target_occupied");
        }
    }
    void includeCleanup(MinecraftClient c,Collection<BlockPos> positions) {
        int bottom=min.getY(),top=max.getY();
        // Repair can start on a scaffold above the original starting point.
        // Retain every recorded support without widening the horizontal area.
        for(var pos:positions) {
            if(pos.getX()<min.getX()||pos.getX()>max.getX()||pos.getZ()<min.getZ()||pos.getZ()>max.getZ()
                ||pos.getY()<c.world.getBottomY()||pos.getY()>=c.world.getTopY())
                throw new IllegalArgumentException("journal footprint changed");
            bottom=Math.min(bottom,pos.getY());top=Math.max(top,pos.getY());
        }
        min=new BlockPos(min.getX(),bottom,min.getZ());max=new BlockPos(max.getX(),top,max.getZ());
        validateVolume();
    }
    private void validateVolume() {
        if((long)(max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1)>262144)
            throw new IllegalArgumentException("build_volume_too_large");
    }
    void refreshPairs(MinecraftClient c) {
        var pending=new HashSet<BlockPos>();
        for(var pair:primaryFor.entrySet())if(!matches(c.world.getBlockState(pair.getValue()),cells.get(pair.getValue())))pending.add(pair.getKey());
        pendingSecondaries=Set.copyOf(pending);
    }
    boolean contains(BlockPos p){return p.getX()>=min.getX()&&p.getX()<=max.getX()&&p.getY()>=min.getY()&&p.getY()<=max.getY()&&p.getZ()>=min.getZ()&&p.getZ()<=max.getZ();}
    static JsonObject props(JsonObject item){if(!item.has("state"))item.add("state",new JsonObject());return item.getAsJsonObject("state");}
    static List<Integer> point(BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
    static BlockPos position(JsonObject p){var a=p.getAsJsonArray("pos");return new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());}
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> key,String value) {
        return state.with(key,key.parse(value).orElseThrow(()->new IllegalArgumentException("invalid_block_state")));
    }
    static BlockState desired(JsonObject item) {
        var id=Identifier.of(item.get("block").getAsString());
        if(!Registries.BLOCK.containsId(id))throw new IllegalArgumentException("unsupported_build_block");
        var state=Registries.BLOCK.get(id).getDefaultState();
        if(item.has("state"))for(var e:item.getAsJsonObject("state").entrySet()) {
            var key=state.getBlock().getStateManager().getProperty(e.getKey());
            if(key==null)throw new IllegalArgumentException("invalid_block_state");
            state=property(state,key,e.getValue().getAsString());
        }
        return state;
    }
    static boolean matches(BlockState actual,JsonObject wanted) {
        if(!Registries.BLOCK.getId(actual.getBlock()).toString().equals(wanted.get("block").getAsString()))return false;
        var properties=WorldActions.properties(actual);
        if(wanted.has("state"))for(var e:wanted.getAsJsonObject("state").entrySet())
            if(!TRANSIENT.contains(e.getKey())&&!e.getValue().getAsString().equals(properties.get(e.getKey())))return false;
        return true;
    }
    static boolean secondary(JsonObject item) {
        var state=desired(item);var props=WorldActions.properties(state);
        return state.getBlock() instanceof DoorBlock&&props.get("half").equals("upper")
            ||state.getBlock() instanceof BedBlock&&props.get("part").equals("head");
    }
    Map<String,Integer> missing(MinecraftClient c) {
        var needed=new LinkedHashMap<String,Integer>();
        for(var e:cells.entrySet())if(!blocked.containsKey(e.getKey())&&!matches(c.world.getBlockState(e.getKey()),e.getValue())&&!secondary(e.getValue())) {
            var item=desired(e.getValue()).getBlock().asItem();
            if(item!=net.minecraft.item.Items.AIR)needed.merge(Registries.ITEM.getId(item).toString(),1,Integer::sum);
        }
        for(int i=0;i<36;i++){var stack=c.player.getInventory().getStack(i);needed.computeIfPresent(Registries.ITEM.getId(stack.getItem()).toString(),(k,n)->Math.max(0,n-stack.getCount()));}
        needed.values().removeIf(n->n==0);return needed;
    }
}
