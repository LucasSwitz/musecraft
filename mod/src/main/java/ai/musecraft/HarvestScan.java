package ai.musecraft;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Fixed whole-block cube, loaded chunks only, bounded work per game tick. */
final class HarvestScan {
    private static HarvestScan active;
    private final ClientWorld world;
    private final Vec3d center,player;
    private final int radius;
    private final String item;
    private final boolean structures;
    private final Set<net.minecraft.block.Block> sourceBlocks=new HashSet<>();
    private final Set<BlockPos> excluded=new HashSet<>();
    private final Iterator<BlockPos> cells;
    private final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();
    private record Candidate(BlockPos pos,String name,double score) {}
    private final PriorityQueue<Candidate> nearest=new PriorityQueue<>(Comparator.comparingDouble(Candidate::score).reversed());
    private final Map<net.minecraft.block.BlockState,Integer> tools=new HashMap<>();
    private int visited,unloaded,protectedLogs,missingTools,matches,slices;
    private long cpu,maxSlice;
    private HarvestScan(MinecraftClient c,JsonObject p) {
        world=c.world;player=c.player.getPos();item=p.get("item").getAsString();
        if(!Registries.ITEM.containsId(Identifier.of(item)))throw new IllegalArgumentException("unknown_item:"+item);
        for(var block:Registries.BLOCK)if(HarvestDrops.potential(block.getDefaultState(),item))sourceBlocks.add(block);
        var near=p.getAsJsonArray("near");center=new Vec3d(near.get(0).getAsDouble(),near.get(1).getAsDouble(),near.get(2).getAsDouble());
        radius=BlockScan.bounded(p,"radius",16,32);structures=p.has("allowStructures")&&p.get("allowStructures").getAsBoolean();
        if(p.has("exclude"))for(var e:p.getAsJsonArray("exclude")) {
            var a=e.getAsJsonArray();excluded.add(new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()));
            if(excluded.size()>16384)throw new IllegalArgumentException("too_many_excluded_sources");
        }
        cells=BlockVolume.around(center,radius).cells().iterator();
    }
    static CompletableFuture<Map<String,Object>> start(MinecraftClient c,JsonObject p) {
        if(active!=null&&!active.result.isDone())throw new IllegalStateException("harvest_scan_in_progress");
        active=new HarvestScan(c,p);return active.result;
    }
    static void tick(MinecraftClient c) {
        var scan=active;if(scan==null)return;
        if(c.world!=scan.world||c.player==null){scan.result.completeExceptionally(new IllegalStateException("scan_world_changed"));active=null;return;}
        long begin=System.nanoTime();
        try {
            scan.slices++;
            do {scan.step(c);}while(scan.cells.hasNext()&&System.nanoTime()-begin<2_000_000L);
            long work=System.nanoTime()-begin;scan.cpu+=work;scan.maxSlice=Math.max(scan.maxSlice,work);
            if(!scan.cells.hasNext()) {
                var out=new LinkedHashMap<String,Object>();
                out.put("sources",scan.nearest.stream().sorted(Comparator.comparingDouble(Candidate::score)).map(x->Map.of(
                    "pos",List.of(x.pos.getX(),x.pos.getY(),x.pos.getZ()),"name",x.name)).toList());
                out.put("available",scan.matches);out.put("sourceTypes",scan.sourceBlocks.size());out.put("unloadedCells",scan.unloaded);
                out.put("protectedLogs",scan.protectedLogs);out.put("missingTools",scan.missingTools);
                out.put("stats",Map.of("cells",scan.visited,"slices",scan.slices,"cpuMs",scan.cpu/1e6,"maxSliceMs",scan.maxSlice/1e6));
                scan.result.complete(out);active=null;
            }
        } catch(Exception e){scan.result.completeExceptionally(e);active=null;}
    }
    private void step(MinecraftClient c) {
        var pos=cells.next();
        visited++;if(!WorldActions.loaded(c,pos)){unloaded++;return;}
        if(excluded.contains(pos))return;
        var state=world.getBlockState(pos);
        if(state.isAir()||state.getHardness(world,pos)<0||!sourceBlocks.contains(state.getBlock()))return;
        if(!structures) {
            var context=BlockContext.describe(c,pos);
            if(context!=null&&context.get("kind").equals("likely_structure")){protectedLogs++;return;}
        }
        if(tools.computeIfAbsent(state,key->HarvestDrops.tool(c,key,item))<0){missingTools++;return;}
        matches++;
        boolean exposed=false;
        for(var direction:Direction.values())if(!world.getBlockState(pos.offset(direction)).isFullCube(world,pos.offset(direction))){exposed=true;break;}
        double score=Vec3d.ofCenter(pos).distanceTo(player)+(exposed?0:16)+Math.max(0,pos.getY()-player.y-2)*2;
        nearest.add(new Candidate(pos.toImmutable(),Registries.BLOCK.getId(state.getBlock()).toString(),score));
        if(nearest.size()>64)nearest.remove();
    }
}
