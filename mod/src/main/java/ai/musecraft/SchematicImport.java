package ai.musecraft;

import baritone.api.schematic.*;
import baritone.utils.schematic.SchematicSystem;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.*;

/** Native format parsers and transforms, materialized for durable verification and repair. */
final class SchematicImport {
    static JsonElement read(JsonObject p) throws Exception {
        Path root=Path.of(System.getProperty("musecraft.home","/home/hatch/workspace/musecraft"),"schematics");
        Files.createDirectories(root);root=root.toRealPath();
        Path file=root.resolve(p.get("file").getAsString()).normalize().toRealPath();
        if(!file.startsWith(root)||!Files.isRegularFile(file)||Files.size(file)>8*1024*1024)throw new IllegalArgumentException("invalid_schematic_file");
        var format=SchematicSystem.INSTANCE.getByFile(file.toFile()).orElseThrow(()->new IllegalArgumentException("unsupported_schematic_format"));
        ISchematic schematic;
        try(var stream=Files.newInputStream(file)){schematic=format.parse(stream);}
        int rotation=p.has("rotation")?p.get("rotation").getAsInt():0;
        var rotate=switch(rotation){case 0->BlockRotation.NONE;case 90->BlockRotation.CLOCKWISE_90;case 180->BlockRotation.CLOCKWISE_180;case 270->BlockRotation.COUNTERCLOCKWISE_90;default->throw new IllegalArgumentException("invalid_rotation");};
        var mirror=switch(p.has("mirror")?p.get("mirror").getAsString():"none") {case "none"->BlockMirror.NONE;case "x"->BlockMirror.FRONT_BACK;case "z"->BlockMirror.LEFT_RIGHT;default->throw new IllegalArgumentException("invalid_mirror");};
        schematic=new RotatedSchematic(new MirroredSchematic(schematic,mirror),rotate);
        if(p.has("repeat")) {
            var repeat=p.getAsJsonObject("repeat");int count=repeat.get("count").getAsInt();var offset=repeat.getAsJsonArray("offset");
            int dx=offset.get(0).getAsInt(),dy=offset.get(1).getAsInt(),dz=offset.get(2).getAsInt();
            if(count<1||count>64||dx<0||dy<0||dz<0||count>1&&dx<schematic.widthX()&&dy<schematic.heightY()&&dz<schematic.lengthZ())throw new IllegalArgumentException("repeat_requires_nonoverlapping_positive_offset");
            var repeated=new CompositeSchematic(0,0,0);
            for(int i=0;i<count;i++)repeated.put(schematic,Math.multiplyExact(i,dx),Math.multiplyExact(i,dy),Math.multiplyExact(i,dz));
            schematic=repeated;
        }
        long volume=(long)schematic.widthX()*schematic.heightY()*schematic.lengthZ();
        if(volume<1||volume>65536)throw new IllegalArgumentException("schematic_volume_exceeds_65536");
        var origin=p.getAsJsonArray("origin");int ox=origin.get(0).getAsInt(),oy=origin.get(1).getAsInt(),oz=origin.get(2).getAsInt();
        boolean air=p.has("includeAir")&&p.get("includeAir").getAsBoolean();var rows=new JsonArray();var empty=Blocks.AIR.getDefaultState();var gson=new Gson();
        for(int y=0;y<schematic.heightY();y++)for(int z=0;z<schematic.lengthZ();z++)for(int x=0;x<schematic.widthX();x++) {
            if(!schematic.inSchematic(x,y,z,empty))continue;
            var state=schematic.desiredState(x,y,z,empty,List.of());
            if(state==null||state.isAir()&&!air)continue;
            if(rows.size()>=4096)throw new IllegalArgumentException("schematic_exceeds_4096_cells");
            var row=new JsonObject();row.add("pos",gson.toJsonTree(new int[]{Math.addExact(ox,x),Math.addExact(oy,y),Math.addExact(oz,z)}));
            row.addProperty("block",Registries.BLOCK.getId(state.getBlock()).toString());row.add("state",gson.toJsonTree(WorldActions.properties(state)));
            if(state.isAir()) {
                var c=net.minecraft.client.MinecraftClient.getInstance();var pos=BuildPlan.position(row);
                if(!WorldActions.loaded(c,pos))throw new IllegalStateException("schematic_air_clearance_unloaded");
                var current=c.world.getBlockState(pos);
                if(!current.isAir())row.addProperty("replace",Registries.BLOCK.getId(current.getBlock()).toString());
            }
            rows.add(row);
        }
        if(rows.isEmpty())throw new IllegalArgumentException("empty_schematic");
        var result=new JsonObject();result.add("blocks",rows);result.addProperty("backend","baritone");
        result.add("dimensions",gson.toJsonTree(new int[]{schematic.widthX(),schematic.heightY(),schematic.lengthZ()}));return result;
    }
}
