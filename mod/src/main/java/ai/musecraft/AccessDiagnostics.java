package ai.musecraft;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.*;

/** Bounded physical evidence, not an inferred explanation of Baritone's search. */
final class AccessDiagnostics {
    static final int SAFE_DROP=3;
    private static final int LOOK_DOWN=16;
    record Landing(String status,BlockPos block,double height) {}
    static List<Integer> pos(BlockPos p){return List.of(p.getX(),p.getY(),p.getZ());}
    static String name(MinecraftClient c,BlockPos p){return Registries.BLOCK.getId(c.world.getBlockState(p).getBlock()).toString();}
    private static double rounded(double v){return Math.round(v*100)/100.;}
    static boolean underfoot(BlockPos target,Vec3d feet) {
        return new Box(target).intersects(new Box(feet.x-.299,feet.y-.03,feet.z-.299,feet.x+.299,feet.y,feet.z+.299));
    }
    static Landing landing(MinecraftClient c,Vec3d feet,BlockPos removed,int depthLimit) {
        var column=BlockPos.ofFloored(feet.x,feet.y-.05,feet.z);
        for(int depth=0;depth<=depthLimit;depth++) {
            var p=column.down(depth);
            if(p.equals(removed))continue;
            if(!WorldActions.loaded(c,p))return new Landing("unloaded",p,Double.NaN);
            if(!WorldActions.safeSupport(c,p))return new Landing("hazard",p,Double.NaN);
            if(!c.world.getFluidState(p).isEmpty())return new Landing("fluid",p,Double.NaN);
            var boxes=NavigationMap.shapes(c,p);
            if(!boxes.isEmpty())return new Landing("surface",p,p.getY()+boxes.stream().mapToDouble(b->b.maxY).max().orElse(0));
        }
        return new Landing("unknown",column.down(depthLimit),Double.NaN);
    }
    static boolean safeRemoval(MinecraftClient c,BlockPos target,Vec3d feet) {
        if(!underfoot(target,feet))return true;
        var floor=landing(c,feet,target,SAFE_DROP);
        return floor.status.equals("surface")&&feet.y-floor.height<=SAFE_DROP;
    }
    static Map<String,Object> supportRemoval(MinecraftClient c,BlockPos target) {
        var feet=c.player.getPos();
        if(!underfoot(target,feet)||safeRemoval(c,target,feet))return null;
        var floor=landing(c,feet,target,LOOK_DOWN);
        var result=new LinkedHashMap<String,Object>();
        result.put("reason","unsafe_support_removal");
        String detail;
        if(floor.status.equals("surface")) {
            double drop=rounded(feet.y-floor.height);
            detail="Removing "+name(c,target)+" at "+pos(target)+" under your feet would expose a "+drop+"-block drop to "+pos(floor.block)+" (landing feet Y="+rounded(floor.height)+"). The mining safety limit is "+SAFE_DROP+" blocks.";
            result.put("fallDistance",drop);result.put("landingSupport",pos(floor.block));result.put("landingFeetY",floor.height);
        }else if(floor.status.equals("hazard")||floor.status.equals("fluid")) {
            detail="Removing "+name(c,target)+" at "+pos(target)+" under your feet exposes "+name(c,floor.block)+" at "+pos(floor.block)+". A safe dry landing has not been established.";
            result.put("landingBlock",name(c,floor.block));result.put("landingSupport",pos(floor.block));
        }else {
            detail="Removing "+name(c,target)+" at "+pos(target)+" under your feet has no confirmed landing: "+(floor.status.equals("unloaded")?"terrain is unloaded at "+pos(floor.block):"no support found through "+pos(floor.block))+".";
        }
        result.put("explanation",detail+" Mine from different supported footing or make a shallower staircase, then retry.");
        result.put("safeDropLimit",SAFE_DROP);result.put("target",pos(target));result.put("landingStatus",floor.status);
        return result;
    }
    private static List<Map<String,Object>> collisions(MinecraftClient c,Box volume) {
        var blocks=new ArrayList<Map<String,Object>>();
        for(var p:BlockPos.iterate(BlockPos.ofFloored(volume.minX,volume.minY-1,volume.minZ),BlockPos.ofFloored(volume.maxX,volume.maxY,volume.maxZ))) {
            if(!WorldActions.loaded(c,p))continue;
            if(NavigationMap.shapes(c,p).stream().anyMatch(b->b.offset(p).intersects(volume)))
                blocks.add(Map.of("position",pos(p),"block",name(c,p)));
            if(blocks.size()==4)break;
        }
        return blocks;
    }
    static Map<String,Object> nearby(MinecraftClient c,Vec3d here) {
        var directions=new ArrayList<Map<String,Object>>();
        var facts=new ArrayList<String>();
        String[] names={"east","west","south","north"};
        int[][] offsets={{1,0},{-1,0},{0,1},{0,-1}};
        for(int i=0;i<offsets.length;i++) {
            var feet=new Vec3d(Math.floor(here.x)+.5+offsets[i][0],here.y,Math.floor(here.z)+.5+offsets[i][1]);
            var body=NavigationTerrain.body(feet);
            var blocked=collisions(c,body);
            var overhead=collisions(c,new Box(body.minX,body.maxY,body.minZ,body.maxX,here.y+3.05,body.maxZ));
            var floor=landing(c,feet,null,LOOK_DOWN);
            var row=new LinkedHashMap<String,Object>();
            row.put("direction",names[i]);row.put("feet",List.of(feet.x,feet.y,feet.z));
            row.put("bodyObstructions",blocked);row.put("overheadBlocks",overhead);
            row.put("floorStatus",floor.status);
            if(floor.status.equals("surface")) {
                double drop=rounded(here.y-floor.height);
                row.put("drop",drop);row.put("support",pos(floor.block));
                if(drop>SAFE_DROP)facts.add(names[i]+" has a "+drop+"-block floor drop to "+pos(floor.block));
            }else if(!floor.status.equals("unknown")) {
                row.put("floorPosition",pos(floor.block));
                facts.add(names[i]+" floor check encountered "+floor.status+" at "+pos(floor.block));
            }else facts.add(names[i]+" has no confirmed floor within "+LOOK_DOWN+" blocks below");
            if(!blocked.isEmpty())facts.add(names[i]+" body space is blocked by "+blocked.getFirst().get("block")+" at "+blocked.getFirst().get("position"));
            else if(!overhead.isEmpty())facts.add(names[i]+" overhead block: "+overhead.getFirst().get("block")+" at "+overhead.getFirst().get("position"));
            directions.add(row);
        }
        return Map.of("scope","Four adjacent block centers at current feet height; floor checked down 16 blocks; overhead checked to feet + 3.05. These observations do not identify the exact rejected path edge.",
            "directions",directions,"summary",String.join("; ",facts));
    }
}
