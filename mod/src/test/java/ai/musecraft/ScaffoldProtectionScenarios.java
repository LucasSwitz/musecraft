package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.util.math.BlockPos;
import static org.junit.jupiter.api.Assertions.*;

/** Existing world records retain explicit boundaries without accidental placement claims. */
public final class ScaffoldProtectionScenarios {
    @org.junit.jupiter.api.Test
    public void reconnectRetainsOnlyIntentionalAreas() {
        String world="server:25565\nminecraft:overworld";
        var doc=JsonParser.parseString("""
            {"schema":1,"world":"server:25565\\nminecraft:overworld",
             "placements":[[-180,50,-522],[-180,51,-522],[10,60,10]],
             "temporaryPlacements":[{"pos":[-180,50,-522],"blockState":"Block{minecraft:cobblestone}"}],
             "areas":[{"min":[10,60,10],"max":[12,64,12],"label":"home"},
                      {"min":[20,60,20],"max":[21,62,21],"label":"dock"}]}
            """).getAsJsonObject();
        var original=doc.deepCopy();
        var areas=StructureProtection.readAreas(doc,world);
        for(var p:List.of(new BlockPos(-180,50,-522),new BlockPos(-180,51,-522),new BlockPos(-179,50,-523),new BlockPos(9,60,10)))
            assertNull(StructureProtection.reason(areas,p));
        for(var p:List.of(new BlockPos(10,60,10),new BlockPos(12,64,12),new BlockPos(20,61,21)))
            assertEquals("protected_area",StructureProtection.reason(areas,p));
        // Parsing a persisted world never rewrites legacy records or broadens areas.
        assertEquals(original,doc);
        var reloaded=StructureProtection.readAreas(JsonParser.parseString(doc.toString()).getAsJsonObject(),world);
        assertEquals(areas,reloaded);
        assertThrows(IllegalStateException.class,()->StructureProtection.readAreas(doc,"other-server\nminecraft:overworld"));
        var corrupt=doc.deepCopy();
        corrupt.getAsJsonArray("areas").get(1).getAsJsonObject().add("max",JsonParser.parseString("[19,62,21]"));
        assertThrows(IllegalArgumentException.class,()->StructureProtection.readAreas(corrupt,world));
        assertEquals("protected_area",StructureProtection.reason(areas,new BlockPos(20,61,21)));
    }
}
