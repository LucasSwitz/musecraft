package ai.musecraft;

import baritone.pathing.movement.CalculationContext;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Only intentionally designated areas restrict gathering and excavation. */
final class ResourceProtection {
    static void start(MinecraftClient c){StructureProtection.load(c);}
    static String reason(MinecraftClient c,BlockPos p) {
        StructureProtection.load(c);
        if(!WorldActions.loaded(c,p))return "terrain_unloaded";
        return StructureProtection.reason(p);
    }
    static String reason(CalculationContext context,BlockPos p,BlockState state) {
        return StructureProtection.reason(p);
    }
    static Map<String,Object> describe(MinecraftClient c,BlockPos p) {
        String reason=reason(c,p);
        return reason==null?Map.of("allowed",true,"basis","outside_protected_areas"):
            Map.of("allowed",false,"reason",reason,"explanation","This position is explicitly protected or its protection data is unavailable. Choose a position outside protected areas.");
    }
}
