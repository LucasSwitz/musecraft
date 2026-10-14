package ai.musecraft;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Local evidence, not ownership or world-generation provenance. */
final class BlockContext {
    private static ClientWorld world;
    private static long tick;
    private static final Map<BlockPos,Map<String,Object>> cache = new HashMap<>();

    static void invalidate() { cache.clear(); }

    static Map<String,Object> describe(MinecraftClient c, BlockPos origin) {
        if (!c.world.getBlockState(origin).isIn(BlockTags.LOGS)) return null;
        if (world != c.world || c.world.getTime() - tick >= 20 || c.world.getTime() < tick) {
            cache.clear(); world = c.world; tick = c.world.getTime();
        }
        if (cache.containsKey(origin)) return cache.get(origin);
        if (cache.size() > 1024) cache.clear();
        var logs = new LinkedHashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(origin.toImmutable()); logs.add(origin.toImmutable());
        boolean partial = false;
        while (!queue.isEmpty()) {
            var p = queue.removeFirst();
            for (var direction : Direction.values()) {
                var next = p.offset(direction);
                if (!WorldActions.loaded(c,next)) { partial = true; continue; }
                if (!c.world.getBlockState(next).isIn(BlockTags.LOGS) || logs.contains(next)) continue;
                if (logs.size() >= 64 || Math.abs(next.getX()-origin.getX()) > 8
                        || Math.abs(next.getZ()-origin.getZ()) > 8 || Math.abs(next.getY()-origin.getY()) > 16) {
                    partial = true; continue;
                }
                logs.add(next); queue.add(next);
            }
        }
        var visited = new HashSet<BlockPos>();
        var built = new TreeSet<String>();
        int leaves = 0, persistentLeaves = 0;
        boolean rooted = false;
        for (var log : logs) {
            var below = c.world.getBlockState(log.down());
            rooted |= below.isIn(BlockTags.DIRT) || below.isOf(Blocks.MUD)
                || below.isOf(Blocks.MUDDY_MANGROVE_ROOTS);
            for (var p : BlockPos.iterate(log.add(-2,-1,-2),log.add(2,2,2))) {
                if (!visited.add(p.toImmutable())) continue;
                if (!WorldActions.loaded(c,p)) { partial = true; continue; }
                var state = c.world.getBlockState(p);
                if (state.isIn(BlockTags.LEAVES)) {
                    if (state.contains(Properties.PERSISTENT) && state.get(Properties.PERSISTENT)) persistentLeaves++;
                    else leaves++;
                }
                if (state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.STAIRS)
                        || state.isIn(BlockTags.SLABS) || state.isIn(BlockTags.DOORS)
                        || state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS)
                        || state.isOf(Blocks.GLASS) || state.getBlock() instanceof StainedGlassBlock || state.getBlock() instanceof PaneBlock
                        || state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.CHEST)
                        || state.isOf(Blocks.BRICKS) || state.isOf(Blocks.STONE_BRICKS))
                    built.add(Registries.BLOCK.getId(state.getBlock()).toString());
            }
        }
        var evidence = new ArrayList<String>();
        if (!built.isEmpty()) evidence.add("nearby_built_blocks");
        if (leaves > 0) evidence.add("nonpersistent_leaves");
        if (persistentLeaves > 0) evidence.add("persistent_leaves");
        if (rooted) evidence.add("soil_at_log_base");
        String kind = !built.isEmpty() ? "likely_structure"
            : !partial && rooted && leaves >= 4 && persistentLeaves == 0 ? "likely_tree" : "unknown";
        Map<String,Object> result = Map.of("kind",kind,"basis","local_block_patterns",
            "evidence",evidence,"nearbyBuiltBlocks",built.stream().limit(6).toList(),
            "naturalLeafCount",leaves,"persistentLeafCount",persistentLeaves,
            "connectedLogs",logs.size(),"partial",partial);
        logs.forEach(p -> cache.put(p,result));
        return result;
    }
}
