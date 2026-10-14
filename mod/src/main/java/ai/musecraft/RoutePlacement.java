package ai.musecraft;

import baritone.api.BaritoneAPI;
import com.google.gson.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Budget and write-ahead cleanup record for upstream bridge/pillar placement. */
public final class RoutePlacement {
    private static final Gson JSON=new Gson();
    private static volatile boolean active;
    private static volatile int used,budget;
    private static volatile BlockVolume bounds;
    private static final Map<BlockPos,JsonObject> placements=new LinkedHashMap<>();
    private static final Set<BlockPos> confirmed=new HashSet<>();
    private static List<Item> materials=List.of();
    private static BaritoneSettingsScope settings;
    private static String id,world,refused;
    static void start(MinecraftClient c,JsonObject p,Vec3d target) {
        stop();id=null;placements.clear();confirmed.clear();used=0;refused=null;
        if(!p.has("allowPlace")||!p.get("allowPlace").getAsBoolean())return;
        budget=p.has("maxPlace")?p.get("maxPlace").getAsInt():16;
        if(budget<1||budget>128)throw new IllegalArgumentException("placement_budget_must_be_1_to_128");
        var names=p.has("placeBlock")?List.of(p.get("placeBlock").getAsString()):List.of("minecraft:dirt","minecraft:cobblestone","minecraft:netherrack");
        var items=new ArrayList<Item>();
        for(var name:names) {
            var key=Identifier.tryParse(name);
            if(key==null||!Registries.BLOCK.containsId(key))throw new IllegalArgumentException("unknown_route_material");
            var block=Registries.BLOCK.get(key);var state=block.getDefaultState();
            if(!(block.asItem() instanceof BlockItem)||block instanceof FallingBlock||state.hasBlockEntity()
                ||!state.isFullCube(c.world,c.player.getBlockPos())||state.getLuminance()>0)throw new IllegalArgumentException("route_material_requires_plain_full_block");
            items.add(block.asItem());
        }
        materials=List.copyOf(items);
        var a=c.player.getBlockPos();var b=BlockPos.ofFloored(target);
        bounds=new BlockVolume(new BlockPos(Math.min(a.getX(),b.getX())-4,Math.min(a.getY(),b.getY())-4,Math.min(a.getZ(),b.getZ())-4),
            new BlockPos(Math.max(a.getX(),b.getX())+4,Math.max(a.getY(),b.getY())+3,Math.max(a.getZ(),b.getZ())+4));
        id=UUID.randomUUID().toString();world=world(c);active=true;
        var s=BaritoneAPI.getSettings();settings=new BaritoneSettingsScope();
        settings.set(s.allowPlace,true);settings.set(s.allowInventory,true);settings.set(s.acceptableThrowawayItems,materials);
        settings.set(s.allowParkourPlace,false);settings.set(s.allowParkour,false);
        save();
    }
    static void stop(){active=false;if(settings!=null){settings.close();settings=null;}}
    static boolean pending(){return active&&placements.size()>confirmed.size();}
    static String refusal(){
        if(active&&placements.entrySet().stream().anyMatch(e->!confirmed.contains(e.getKey())&&System.currentTimeMillis()-e.getValue().get("sentAt").getAsLong()>5000))refused="route_placement_not_confirmed";
        return refused;
    }
    static boolean mayPlace(BlockPos p){return active&&bounds.contains(p)&&used<budget;}
    public static boolean admit(BlockPos p,ItemStack stack) {
        if(!BaritoneNavigation.running())return true;
        if(!active)return false;
        var c=MinecraftClient.getInstance();
        if(!materials.contains(stack.getItem())||!bounds.contains(p)||BaritoneProtection.protectedCell(p,c.world.getBlockState(p))) {
            refused="route_placement_outside_policy";return false;
        }
        if(placements.containsKey(p))return true;
        if(used>=budget){refused="route_placement_budget_exhausted";return false;}
        var before=c.world.getBlockState(p);
        if(!before.isAir()){refused="route_placement_requires_air";return false;}
        var row=new JsonObject();row.add("pos",JSON.toJsonTree(BuildPlan.point(p)));row.addProperty("block","minecraft:air");row.addProperty("sentAt",System.currentTimeMillis());
        row.addProperty("placedBlock",Registries.BLOCK.getId(((BlockItem)stack.getItem()).getBlock()).toString());
        placements.put(p.toImmutable(),row);used=placements.size();save();return true;
    }
    static void changed(BlockPos p,BlockState state) {
        var row=placements.get(p);
        if(row!=null&&row.get("placedBlock").getAsString().equals(Registries.BLOCK.getId(state.getBlock()).toString()))confirmed.add(p.toImmutable());
    }
    static Map<String,Object> snapshot() {
        return id==null?Map.of():Map.of("routeId",id,"budget",budget,"reserved",used,"placed",confirmed.size(),"cleanupRequired",!placements.isEmpty(),"placements",placements.keySet().stream().map(BuildPlan::point).toList());
    }
    static JsonElement cleanupPlan(MinecraftClient c,JsonObject p) throws Exception {
        var file=file(p.get("routeId").getAsString());var doc=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if(!world(c).equals(doc.get("world").getAsString()))throw new IllegalStateException("route_world_mismatch");
        StructureProtection.load(c);
        var items=new JsonArray();
        for(var entry:doc.getAsJsonArray("placements")) {
            var row=entry.getAsJsonObject();var pos=BuildPlan.position(row);
            if(!WorldActions.loaded(c,pos))throw new IllegalStateException("route_cleanup_unloaded");
            var state=c.world.getBlockState(pos);if(state.isAir())continue;
            if(StructureProtection.reason(pos)!=null)throw new IllegalStateException("route_cleanup_protected_structure");
            if(!row.get("placedBlock").getAsString().equals(Registries.BLOCK.getId(state.getBlock()).toString()))throw new IllegalStateException("route_cleanup_block_changed");
            var wanted=new JsonObject();wanted.add("pos",row.get("pos"));wanted.addProperty("block","minecraft:air");wanted.addProperty("replace",row.get("placedBlock").getAsString());items.add(wanted);
        }
        return items;
    }
    private static String world(MinecraftClient c){return (c.getCurrentServerEntry()==null?"singleplayer":c.getCurrentServerEntry().address)+"\n"+c.world.getRegistryKey().getValue();}
    private static Path file(String value){String key=UUID.fromString(value).toString();return Path.of(System.getProperty("musecraft.home","/home/hatch/workspace/musecraft"),"route-journals",key+".json");}
    private static void save() {
        try {
            var file=file(id);Files.createDirectories(file.getParent());var temp=file.resolveSibling(file.getFileName()+".tmp");
            var data=JSON.toJson(Map.of("world",world,"placements",placements.values())).getBytes(StandardCharsets.UTF_8);
            try(var out=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var buffer=java.nio.ByteBuffer.wrap(data);while(buffer.hasRemaining())out.write(buffer);out.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){active=false;throw new IllegalStateException("route_journal_unavailable",e);}
    }
}
