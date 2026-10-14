package ai.musecraft;

import com.google.gson.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Durable, world-scoped areas explicitly designated for protection. */
public final class StructureProtection {
    private static final Gson JSON=new Gson();
    record Area(BlockVolume bounds,String label) {}
    private static JsonObject document=new JsonObject();
    private static volatile List<Area> areas=List.of();
    private static Object loadedWorld;
    private static Path file;
    private static volatile String failure;

    static void load(MinecraftClient c) {
        if(loadedWorld==c.world)return;
        loadedWorld=c.world;document=new JsonObject();areas=List.of();failure=null;
        if(c.world==null)return;
        String server=c.getCurrentServerEntry()==null?"singleplayer:"+c.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize():c.getCurrentServerEntry().address;
        String world=server+"\n"+c.world.getRegistryKey().getValue();
        file=Path.of(System.getProperty("musecraft.home","/home/hatch/workspace/musecraft"),"protected-worlds",
            UUID.nameUUIDFromBytes(world.getBytes(StandardCharsets.UTF_8))+".json");
        try {
            if(!Files.exists(file))return;
            var doc=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            areas=readAreas(doc,world);
            // Preserve historical placement records for rollback, but they no
            // longer grant protection. Only the explicit areas are authoritative.
            document=doc;
        }catch(Exception e){failure="build_protection_unavailable";System.err.println("Musecraft protection load failed: "+e.getClass().getSimpleName());}
    }
    static List<Area> readAreas(JsonObject doc,String world) {
        if(doc.get("schema").getAsInt()!=1||!world.equals(doc.get("world").getAsString()))throw new IllegalStateException("protection_world_mismatch");
        var regions=new ArrayList<Area>();
        for(var row:doc.getAsJsonArray("areas")) {
            var a=row.getAsJsonObject();regions.add(new Area(bounds(a),a.get("label").getAsString()));
        }
        return List.copyOf(regions);
    }
    static String reason(List<Area> regions,BlockPos p) {
        for(var area:regions)if(area.bounds.contains(p))return "protected_area";
        return null;
    }
    static String reason(BlockPos p) {
        return failure!=null?failure:reason(areas,p);
    }
    static Map<String,Object> protect(MinecraftClient c,JsonObject request) {
        load(c);if(failure!=null)throw new IllegalStateException(failure);
        var region=bounds(request);String label=request.get("label").getAsString().strip();
        if(label.isEmpty()||label.length()>120)throw new IllegalArgumentException("invalid_protection_label");
        var next=new ArrayList<>(areas);
        var entry=new Area(region,label);if(!next.contains(entry)){next.add(entry);areas=List.copyOf(next);save(c);}
        return Map.of("protected",true,"min",BuildPlan.point(region.min()),"max",BuildPlan.point(region.max()),"label",label);
    }
    private static BlockPos point(JsonArray a) {
        if(a.size()!=3)throw new IllegalArgumentException("invalid_protection_position");
        int[] v=new int[3];for(int i=0;i<3;i++) {
            double n=a.get(i).getAsDouble();if(!Double.isFinite(n)||n!=Math.rint(n)||Math.abs(n)>30000000)throw new IllegalArgumentException("invalid_protection_position");v[i]=(int)n;
        }
        return new BlockPos(v[0],v[1],v[2]);
    }
    private static BlockVolume bounds(JsonObject p) {
        var min=point(p.getAsJsonArray("min"));var max=point(p.getAsJsonArray("max"));
        if(max.getX()<min.getX()||max.getY()<min.getY()||max.getZ()<min.getZ()
            ||(long)max.getX()-min.getX()>255||(long)max.getY()-min.getY()>383||(long)max.getZ()-min.getZ()>255)throw new IllegalArgumentException("invalid_protection_bounds");
        return new BlockVolume(min,max);
    }
    private static void save(MinecraftClient c) {
        String server=c.getCurrentServerEntry()==null?"singleplayer:"+c.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize():c.getCurrentServerEntry().address;
        var doc=document.deepCopy();doc.addProperty("schema",1);doc.addProperty("world",server+"\n"+c.world.getRegistryKey().getValue());
        doc.add("areas",JSON.toJsonTree(areas.stream().map(a->Map.of("min",BuildPlan.point(a.bounds.min()),"max",BuildPlan.point(a.bounds.max()),"label",a.label)).toList()));
        try {
            Files.createDirectories(file.getParent());var tmp=file.resolveSibling(file.getFileName()+".tmp");
            try(var out=FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var data=StandardCharsets.UTF_8.encode(JSON.toJson(doc));while(data.hasRemaining())out.write(data);out.force(true);
            }
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            document=doc;
        }catch(Exception e){failure="build_protection_unavailable";throw new IllegalStateException(failure,e);}
    }
}
