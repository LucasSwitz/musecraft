package ai.musecraft;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/** Write-ahead record of temporary off-blueprint placements; repair survives controller/client restarts. */
final class BuildJournal {
    final Map<BlockPos,JsonObject> originals=new LinkedHashMap<>();
    private final Path file;
    BuildJournal(String world,BuildPlan plan) {
        String key=UUID.nameUUIDFromBytes((world+"\n"+plan.id).getBytes(StandardCharsets.UTF_8)).toString();
        file=Path.of(System.getProperty("musecraft.home","/home/hatch/workspace/musecraft"),"build-journals",key+".json");
        try {
            if(Files.exists(file))for(var item:JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                var row=item.getAsJsonObject();var pos=BuildPlan.position(row);
                if(plan.cells.containsKey(pos))throw new IOException("journal footprint changed");
                originals.put(pos,row);
            }
            plan.includeCleanup(net.minecraft.client.MinecraftClient.getInstance(),originals.keySet());
        }catch(IOException|RuntimeException e){throw new IllegalStateException("build_journal_unavailable",e);}
    }
    void remember(BlockPos pos,BlockState before) {
        if(originals.containsKey(pos))return;
        var row=new JsonObject();row.add("pos",new Gson().toJsonTree(BuildPlan.point(pos)));
        row.addProperty("block",Registries.BLOCK.getId(before.getBlock()).toString());
        row.add("state",new Gson().toJsonTree(WorldActions.properties(before)));originals.put(pos.toImmutable(),row);
        save();
    }
    void clear() { originals.clear();save(); }
    private void save() {
        try {
            Files.createDirectories(file.getParent());var temp=file.resolveSibling(file.getFileName()+".tmp");
            byte[] data=new Gson().toJson(originals.values()).getBytes(StandardCharsets.UTF_8);
            try(var out=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {var buffer=java.nio.ByteBuffer.wrap(data);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(IOException e){throw new IllegalStateException("build_journal_unavailable",e);}
    }
}
