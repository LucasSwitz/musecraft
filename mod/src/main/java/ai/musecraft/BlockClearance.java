package ai.musecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Shared safety check for ordinary pickup excavation. */
final class BlockClearance {
    static String reason(MinecraftClient c,BlockPos p) {
        if(!WorldActions.loaded(c,p))return "terrain_unloaded";
        if(ResourceProtection.reason(c,p)!=null)return "protected_structure";
        var state=c.world.getBlockState(p);
        if(!state.getFluidState().isEmpty())return "fluid_obstruction";
        if(state.getHardness(c.world,p)<0)return "unbreakable_obstruction";
        if(c.world.getBlockEntity(p) instanceof net.minecraft.inventory.Inventory)return "container_contents_unknown";
        if(!MiningAccess.removable(c,p,c.player.getPos()))return "unsafe_clearance";
        return null;
    }
}
