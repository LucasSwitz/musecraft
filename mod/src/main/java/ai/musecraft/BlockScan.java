package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Predicate;

/** Bounded, loaded-world block reads. Samples never imply a complete map. */
final class BlockScan {
    static Predicate<BlockState> selector(String query) {
        if (query == null) return state -> !state.isAir();
        boolean tagOnly = query.startsWith("#");
        String name = tagOnly ? query.substring(1) : query;
        Identifier id = Identifier.tryParse(name);
        if (id == null) throw new IllegalArgumentException("invalid_block_or_tag:" + query);
        if (!tagOnly && Registries.BLOCK.containsId(id)) {
            Block block = Registries.BLOCK.get(id);
            return state -> state.isOf(block);
        }
        // Registry tags cover every variant, including modded members. A missing
        // block name may name a category such as bed/beds or log/logs.
        for (Identifier candidate : List.of(id, Identifier.of(id.getNamespace(), id.getPath() + "s"))) {
            TagKey<Block> tag = TagKey.of(RegistryKeys.BLOCK, candidate);
            if (Registries.BLOCK.getEntryList(tag).isPresent()) return state -> state.isIn(tag);
            if (tagOnly) break;
        }
        throw new IllegalArgumentException("unknown_block_or_tag:" + query);
    }

    static Predicate<BlockState> selector(JsonObject p) {
        if(!p.has("names"))return selector(p.has("name")?p.get("name").getAsString():null);
        if(p.has("name"))throw new IllegalArgumentException("use_name_or_names");
        var names=p.getAsJsonArray("names");
        if(names.isEmpty()||names.size()>16)throw new IllegalArgumentException("names_requires_1_to_16_filters");
        var predicates=new ArrayList<Predicate<BlockState>>();
        for(var name:names)predicates.add(selector(name.getAsString()));
        return state->predicates.stream().anyMatch(test->test.test(state));
    }

    static Map<String,Object> findBatch(MinecraftClient c,JsonObject p) {
        var names=p.getAsJsonArray("names");
        if(names==null||names.isEmpty()||names.size()>16)throw new IllegalArgumentException("names_requires_1_to_16_filters");
        int radius=bounded(p,"radius",8,16),limit=bounded(p,"limit",64,64);
        if(limit<names.size())throw new IllegalArgumentException("limit_must_cover_each_filter");
        int quota=limit/names.size();
        var filters=new LinkedHashMap<String,Predicate<BlockState>>();
        var found=new LinkedHashMap<String,PriorityQueue<BlockPos>>();
        var totals=new HashMap<String,Integer>();
        var center=c.player.getBlockPos();
        for(var name:names) {
            String value=name.getAsString();
            if(filters.putIfAbsent(value,selector(value))!=null)throw new IllegalArgumentException("duplicate_filter");
            found.put(value,new PriorityQueue<>(order(center).reversed()));totals.put(value,0);
        }
        long start=System.nanoTime();int unloaded=0,cells=0;
        for(BlockPos pos:BlockVolume.around(center.toCenterPos(),radius).cells()) {
            cells++;
            if(!WorldActions.loaded(c,pos)){unloaded++;continue;}
            var state=c.world.getBlockState(pos);
            for(var filter:filters.entrySet())if(filter.getValue().test(state)) {
                String name=filter.getKey();totals.merge(name,1,Integer::sum);
                var queue=found.get(name);queue.add(pos.toImmutable());if(queue.size()>quota)queue.remove();
            }
        }
        var results=new ArrayList<Map<String,Object>>();
        for(String name:filters.keySet()) {
            var blocks=found.get(name).stream().sorted(order(center)).map(pos->block(c,pos)).toList();
            results.add(Map.of("name",name,"blocks",blocks,"total",totals.get(name),"omitted",totals.get(name)-blocks.size()));
        }
        return Map.of("center",List.of(center.getX(),center.getY(),center.getZ()),"radius",radius,
            "results",results,"unloadedCells",unloaded,"completeCoverage",unloaded==0,
            "stats",Map.of("cellsVisited",cells,"elapsedMs",(System.nanoTime()-start)/1e6));
    }

    static int bounded(JsonObject p, String key, int fallback, int maximum) {
        int value = p.has(key) ? p.get(key).getAsInt() : fallback;
        if (value < 1 || value > maximum) throw new IllegalArgumentException("invalid_" + key);
        return value;
    }

    private static Comparator<BlockPos> order(BlockPos center) {
        return Comparator.comparingDouble((BlockPos p) -> p.getSquaredDistance(center))
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ);
    }

    static Map<String,Object> block(MinecraftClient c, BlockPos p) {
        var state = c.world.getBlockState(p);
        Map<String,String> properties = new TreeMap<>();
        state.getEntries().forEach((property, value) -> properties.put(property.getName(), value.toString()));
        var result = new LinkedHashMap<String,Object>();
        result.put("name", Registries.BLOCK.getId(state.getBlock()).toString());
        result.put("pos", List.of(p.getX(),p.getY(),p.getZ()));
        result.put("properties", properties);
        result.put("resource",ResourceProtection.describe(c,p));
        var context = BlockContext.describe(c,p);
        if (context != null) result.put("context",context);
        return result;
    }

    static Map<String,Object> scan(MinecraftClient c, JsonObject p) {
        int radius = bounded(p,"radius",8,16), limit = bounded(p,"limit",32,64);
        var matches = selector(p.has("name") ? p.get("name").getAsString() : null);
        BlockPos center = c.player.getBlockPos();
        var nearest = new PriorityQueue<BlockPos>(order(center).reversed());
        int total = 0, unloaded = 0;
        for (BlockPos pos : BlockVolume.around(center.toCenterPos(),radius).cells()) {
            if (!WorldActions.loaded(c,pos)) { unloaded++; continue; }
            if (!matches.test(c.world.getBlockState(pos))) continue;
            total++;
            nearest.add(pos.toImmutable());
            if (nearest.size() > limit) nearest.remove();
        }
        var blocks = nearest.stream().sorted(order(center)).map(pos -> block(c,pos)).toList();
        return Map.of("center",List.of(center.getX(),center.getY(),center.getZ()),"radius",radius,
            "blocks",blocks,"omitted",total-blocks.size(),"truncated",total>blocks.size(),"unloadedCells",unloaded);
    }

    static Map<String,Object> nearby(MinecraftClient c) {
        BlockPos center = c.player.getBlockPos();
        Map<String,BlockPos> nearest = new HashMap<>();
        Map<String,Integer> counts = new HashMap<>();
        var order = order(center);
        int unloaded = 0;
        for (BlockPos pos : BlockVolume.around(center.toCenterPos(),4).cells()) {
            if (!WorldActions.loaded(c,pos)) { unloaded++; continue; }
            var state = c.world.getBlockState(pos);
            if (state.isAir()) continue;
            String name = Registries.BLOCK.getId(state.getBlock()).toString();
            counts.merge(name,1,Integer::sum);
            if (!nearest.containsKey(name) || order.compare(pos,nearest.get(name)) < 0) nearest.put(name,pos.toImmutable());
        }
        var blocks = nearest.values().stream().sorted(order).limit(12).map(pos -> {
            var entry = new LinkedHashMap<String,Object>(block(c,pos));
            entry.put("count",counts.get(entry.get("name")));
            return entry;
        }).toList();
        return Map.of("radius",4,"atFeet",block(c,center),"belowFeet",block(c,center.down()),
            "blocks",blocks,"omittedTypes",Math.max(0,nearest.size()-12),"truncated",nearest.size()>12,"unloadedCells",unloaded);
    }
}
