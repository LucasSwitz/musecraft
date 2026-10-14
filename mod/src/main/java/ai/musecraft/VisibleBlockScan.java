package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.ChunkStatus;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** All-direction visibility queries, serviced on game ticks with bounded work. */
final class VisibleBlockScan {
    private static VisibleBlockScan active;
    private static final long SLICE_NS = 2_000_000, DEADLINE_NS = 4_000_000_000L;
    private final CompletableFuture<Map<String,Object>> result = new CompletableFuture<>();
    private final ClientWorld world;
    private final Vec3d eye;
    private final BlockPos center;
    private final int radius, limit;
    private final BlockVolume volume;
    private final Predicate<BlockState> matches;
    private final PriorityQueue<BlockPos> nearest;
    private final List<Section> sections = new ArrayList<>();
    private final long started = System.nanoTime();
    private int sectionIndex, cellIndex, candidates, raycasts, slices;
    private final Set<Long> unloadedChunks=new HashSet<>();
    private long cpuNs, maxSliceNs;
    private boolean limited;
    private record Section(int x,int y,int z,double distance) {}

    private VisibleBlockScan(MinecraftClient c,JsonObject p) {
        world=c.world; eye=c.player.getEyePos(); center=c.player.getBlockPos();
        radius=BlockScan.bounded(p,"radius",128,128);
        volume=BlockVolume.around(c.player.getPos(),radius);
        limit=BlockScan.bounded(p,"limit",32,64);
        matches=BlockScan.selector(p);
        nearest=new PriorityQueue<>(Comparator.comparingDouble((BlockPos pos)->distance(pos))
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).reversed());
        // Only enumerate section coordinates here. Chunk palettes and block state
        // reads are inspected incrementally on the client thread, never off-thread.
        int bottom=Math.max(world.getBottomY(),volume.min().getY())>>4;
        int top=Math.min(world.getTopY()-1,volume.max().getY())>>4;
        for(int x=volume.min().getX()>>4;x<=volume.max().getX()>>4;x++)
            for(int z=volume.min().getZ()>>4;z<=volume.max().getZ()>>4;z++) {
                if(!WorldActions.loadedChunk(c,x,z)) {unloadedChunks.add(chunkKey(x,z));continue;}
                for(int y=bottom;y<=top;y++) {
                    double d=boxDistance(new Box(x*16,y*16,z*16,x*16+16,y*16+16,z*16+16));
                    sections.add(new Section(x,y,z,d));
                }
            }
        sections.sort(Comparator.comparingDouble(Section::distance)
            .thenComparingInt(Section::x).thenComparingInt(Section::y).thenComparingInt(Section::z));
    }

    static CompletableFuture<Map<String,Object>> start(MinecraftClient c,JsonObject p) {
        if(active!=null&&!active.result.isDone())throw new IllegalStateException("scan_in_progress");
        active=new VisibleBlockScan(c,p);
        return active.result;
    }

    static void tick(MinecraftClient c) {
        var scan=active;
        if(scan==null)return;
        if(scan.result.isDone()){active=null;return;}
        if(c.world!=scan.world||c.player==null) {
            scan.result.completeExceptionally(new IllegalStateException("scan_world_changed"));active=null;return;
        }
        long begin=System.nanoTime();
        try {
            scan.slices++;
            String stop=null;
            do {
                if(System.nanoTime()-scan.started>=DEADLINE_NS){stop="work_budget";break;}
                if(scan.sectionIndex>=scan.sections.size()){stop="exhausted";break;}
                if(!scan.step(c)){stop="result_limit";break;}
            } while(System.nanoTime()-begin<SLICE_NS);
            long work=System.nanoTime()-begin;
            scan.cpuNs+=work;scan.maxSliceNs=Math.max(scan.maxSliceNs,work);
            if(stop!=null)scan.finish(c,stop);
        } catch(Exception e) {scan.result.completeExceptionally(e);}
    }

    private static long chunkKey(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}

    private double boxDistance(Box b) {
        double x=Math.max(b.minX-eye.x,Math.max(0,eye.x-b.maxX));
        double y=Math.max(b.minY-eye.y,Math.max(0,eye.y-b.maxY));
        double z=Math.max(b.minZ-eye.z,Math.max(0,eye.z-b.maxZ));
        return x*x+y*y+z*z;
    }
    private double distance(BlockPos pos) {
        return boxDistance(new Box(pos));
    }

    private boolean step(MinecraftClient c) {
        var s=sections.get(sectionIndex);
        if(nearest.size()==limit&&s.distance()>distance(nearest.peek()))return false;
        var chunk=world.getChunkManager().getChunk(s.x(),s.z(),ChunkStatus.FULL,false);
        if(chunk==null){sectionIndex++;cellIndex=0;unloadedChunks.add(chunkKey(s.x(),s.z()));return true;}
        var section=chunk.getSection(world.getSectionIndex(s.y()*16));
        // A named search skips whole sections whose palette cannot match. This
        // avoids scanning millions of air/stone cells for a distant hay bale.
        if(cellIndex==0&&(section.isEmpty()||!section.hasAny(matches))) {sectionIndex++;return true;}
        int i=cellIndex++;
        if(cellIndex==4096){sectionIndex++;cellIndex=0;}
        var state=section.getBlockState(i&15,(i>>8)&15,(i>>4)&15);
        if(!matches.test(state))return true;
        var pos=new BlockPos(s.x()*16+(i&15),s.y()*16+((i>>8)&15),s.z()*16+((i>>4)&15));
        double d=distance(pos);
        if(!volume.contains(pos))return true;
        if(nearest.size()==limit&&d>distance(nearest.peek())){limited=true;return true;}
        candidates++;
        if(visible(c,pos,state)) {
            nearest.add(pos);
            if(nearest.size()>limit){nearest.remove();limited=true;}
        }
        return true;
    }

    private boolean visible(MinecraftClient c,BlockPos pos,BlockState state) {
        var shape=state.getOutlineShape(world,pos);
        if(shape.isEmpty())return false;
        Box b=shape.getBoundingBox().offset(pos);
        if(hit(c,pos,b.getCenter()))return true;
        // Aim at the center and inset corners of the faces toward the eye.
        // A target need not present its center: half blocks and narrow openings
        // can expose just an edge. Actual outline raycasts resolve occlusion.
        double[] low={b.minX,b.minY,b.minZ}, high={b.maxX,b.maxY,b.maxZ};
        double[] origin={eye.x,eye.y,eye.z};
        for(int axis=0;axis<3;axis++) {
            if(origin[axis]>=low[axis]&&origin[axis]<=high[axis])continue;
            double face=origin[axis]<low[axis]?low[axis]+.001:high[axis]-.001;
            int u=(axis+1)%3,v=(axis+2)%3;
            for(int sample=0;sample<5;sample++) {
                double[] point=new double[3];point[axis]=face;
                double a=sample==0?.5:((sample-1)&1)==0?.05:.95;
                double z=sample==0?.5:((sample-1)&2)==0?.05:.95;
                point[u]=low[u]+(high[u]-low[u])*a;point[v]=low[v]+(high[v]-low[v])*z;
                if(hit(c,pos,new Vec3d(point[0],point[1],point[2])))return true;
            }
        }
        return false;
    }

    private boolean hit(MinecraftClient c,BlockPos target,Vec3d point) {
        // Unknown chunks may not be treated as transparent air by the raycast.
        int steps=Math.max(1,(int)Math.ceil(eye.distanceTo(point)));
        for(int i=0;i<=steps;i++)if(!WorldActions.loaded(c,BlockPos.ofFloored(eye.lerp(point,(double)i/steps))))return false;
        raycasts++;
        var hit=world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,c.player));
        return hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(target);
    }

    private void finish(MinecraftClient c,String stop) {
        var blocks=nearest.stream().sorted(nearest.comparator().reversed()).map(pos->BlockScan.block(c,pos)).toList();
        boolean complete=stop.equals("exhausted")&&!limited&&unloadedChunks.isEmpty();
        var out=new LinkedHashMap<String,Object>();
        out.put("center",List.of(center.getX(),center.getY(),center.getZ()));
        out.put("origin",List.of(eye.x,eye.y,eye.z));out.put("radius",radius);out.put("visibility","outline_raycast");
        out.put("blocks",blocks);out.put("complete",complete);out.put("truncated",!complete);
        out.put("omitted",complete?0:null);out.put("unloadedChunks",unloadedChunks.size());
        out.put("stopReason",stop.equals("exhausted")&&limited?"result_limit":stop);
        out.put("stats",Map.of("sectionsVisited",sectionIndex,"candidatesTested",candidates,"raycasts",raycasts,
            "slices",slices,"cpuMs",cpuNs/1e6,"maxSliceMs",maxSliceNs/1e6,"elapsedMs",(System.nanoTime()-started)/1e6));
        result.complete(out);
    }
}
