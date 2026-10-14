package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Bounded tick-sliced inspection with immutable, paginated scan results. */
final class RegionInspection {
    private static final LinkedHashMap<String,RegionInspection> snapshots=new LinkedHashMap<>();
    private static RegionInspection active;
    private record Cell(BlockPos pos,BlockState state) {}
    private static final class Material {
        int count;BlockPos min,max;
        void add(BlockPos p) {
            count++;
            min=min==null?p:new BlockPos(Math.min(min.getX(),p.getX()),Math.min(min.getY(),p.getY()),Math.min(min.getZ(),p.getZ()));
            max=max==null?p:new BlockPos(Math.max(max.getX(),p.getX()),Math.max(max.getY(),p.getY()),Math.max(max.getZ(),p.getZ()));
        }
    }
    private final String id=UUID.randomUUID().toString();
    private final ClientWorld world;
    private final BlockPos min,max;
    private final Iterator<BlockPos> iterator;
    private final Predicate<BlockState> filter;
    private final int limit,volume;
    private final long started=System.nanoTime(),startTick;
    private final CompletableFuture<Map<String,Object>> result=new CompletableFuture<>();
    private final List<Cell> cells=new ArrayList<>();
    private final Map<String,Material> materials=new TreeMap<>();
    private int visited,unloaded,air,slices;
    private long cpuNs,maxSliceNs,endTick;
    private boolean changed;

    private RegionInspection(MinecraftClient c,JsonObject p) {
        world=c.world;startTick=world.getTime();min=position(p,"min");max=position(p,"max");
        long volume=1;
        for(int axis=0;axis<3;axis++) {
            long length=(long)coordinates(max).get(axis)-coordinates(min).get(axis)+1;
            if(length<1||length>64)throw new IllegalArgumentException("region_axis_requires_1_to_64_blocks: "
                +List.of("x","y","z").get(axis)+" spans "+length+" blocks (inclusive). Split this axis into boxes of at most 64 blocks; max must be between min and min + 63.");
            volume*=length;
        }
        if(volume>65536)throw new IllegalArgumentException("region_exceeds_65536_cells");
        this.volume=(int)volume;limit=BlockScan.bounded(p,"limit",64,128);
        filter=BlockScan.selector(p);iterator=BlockPos.iterate(min,max).iterator();
    }
    private static BlockPos position(JsonObject p,String key) {
        var a=p.getAsJsonArray(key);
        if(a==null||a.size()!=3)throw new IllegalArgumentException("invalid_"+key);
        for(var v:a)if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||v.getAsDouble()!=v.getAsInt())throw new IllegalArgumentException("invalid_"+key);
        var pos=new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt());
        if(Math.abs((long)pos.getX())>30000000||Math.abs((long)pos.getZ())>30000000||pos.getY()< -2048||pos.getY()>2048)throw new IllegalArgumentException("region_outside_world_bounds");
        return pos;
    }
    private static List<Integer> coordinates(BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
    static CompletableFuture<Map<String,Object>> start(MinecraftClient c,JsonObject p) {
        snapshots.values().removeIf(scan->scan.world!=c.world);
        if(p.has("scanId")) {
            var scan=snapshots.get(p.get("scanId").getAsString());
            if(scan==null)throw new IllegalArgumentException("scan_unavailable: start a new region inspection");
            int offset=p.has("offset")?p.get("offset").getAsInt():0;
            return CompletableFuture.completedFuture(scan.page(offset,BlockScan.bounded(p,"limit",64,128)));
        }
        if(active!=null&&!active.result.isDone())throw new IllegalStateException("region_scan_in_progress");
        active=new RegionInspection(c,p);return active.result;
    }
    static void changed(BlockPos pos) {
        var scan=active;
        if(scan!=null&&pos.getX()>=scan.min.getX()&&pos.getX()<=scan.max.getX()
            &&pos.getY()>=scan.min.getY()&&pos.getY()<=scan.max.getY()
            &&pos.getZ()>=scan.min.getZ()&&pos.getZ()<=scan.max.getZ())scan.changed=true;
    }
    static void tick(MinecraftClient c) {
        var scan=active;if(scan==null)return;
        if(scan.result.isDone()){active=null;return;}
        if(c.world!=scan.world||c.player==null){scan.result.completeExceptionally(new IllegalStateException("scan_world_changed"));active=null;return;}
        long begin=System.nanoTime();
        try {
            scan.slices++;
            do {scan.step(c);} while(scan.iterator.hasNext()&&System.nanoTime()-begin<2_000_000L);
            long work=System.nanoTime()-begin;scan.cpuNs+=work;scan.maxSliceNs=Math.max(scan.maxSliceNs,work);
            if(!scan.iterator.hasNext()) {
                scan.endTick=scan.world.getTime();snapshots.put(scan.id,scan);
                while(snapshots.size()>8)snapshots.remove(snapshots.keySet().iterator().next());
                scan.result.complete(scan.page(0,scan.limit));active=null;
            }
        } catch(Exception e){scan.result.completeExceptionally(e);active=null;}
    }
    private void step(MinecraftClient c) {
        var p=iterator.next().toImmutable();visited++;
        if(!WorldActions.loaded(c,p)){unloaded++;return;}
        var state=world.getBlockState(p);
        if(state.isAir())air++;
        if(!filter.test(state))return;
        cells.add(new Cell(p,state));
        materials.computeIfAbsent(Registries.BLOCK.getId(state.getBlock()).toString(),key->new Material()).add(p);
    }
    private Map<String,Object> page(int offset,int count) {
        if(offset<0||offset>cells.size())throw new IllegalArgumentException("invalid_scan_offset");
        int end=Math.min(cells.size(),offset+count);
        var blocks=new ArrayList<Map<String,Object>>();
        for(var cell:cells.subList(offset,end)) {
            Map<String,String> properties=new TreeMap<>();
            cell.state.getEntries().forEach((property,value)->properties.put(property.getName(),value.toString()));
            blocks.add(Map.of("pos",coordinates(cell.pos),"name",Registries.BLOCK.getId(cell.state.getBlock()).toString(),"properties",properties));
        }
        var summary=materials.entrySet().stream().limit(64).map(entry->{
            var m=entry.getValue();return Map.of("name",entry.getKey(),"count",m.count,"min",coordinates(m.min),"max",coordinates(m.max));
        }).toList();
        var out=new LinkedHashMap<String,Object>();
        out.put("scanId",id);out.put("min",coordinates(min));out.put("max",coordinates(max));
        out.put("blocks",blocks);out.put("materials",summary);out.put("omittedMaterialTypes",Math.max(0,materials.size()-summary.size()));
        out.put("offset",offset);out.put("nextOffset",end<cells.size()?end:null);out.put("totalMatches",cells.size());
        out.put("unloadedCells",unloaded);out.put("airCells",air);out.put("completeCoverage",unloaded==0&&visited==volume);
        out.put("changedDuringScan",changed);out.put("startTick",startTick);out.put("endTick",endTick);
        out.put("stats",Map.of("cellsVisited",visited,"cpuMs",cpuNs/1e6,"maxSliceMs",maxSliceNs/1e6,"slices",slices));
        return out;
    }
}
