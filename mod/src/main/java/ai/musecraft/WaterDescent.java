package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.*;

/** Resolve an explicitly requested shaft descent to observed water, then let Baritone fall. */
public final class WaterDescent {
    private static volatile BlockPos landing;
    private static BlockPos opening;
    static void clear(){landing=null;opening=null;}
    public static boolean permitsFlow(int x,int y,int z){var p=landing;return p!=null&&p.getX()==x&&p.getY()==y&&p.getZ()==z;}
    static void resolve(MinecraftClient c,JsonObject request) {
        clear();
        if(!request.has("descend")||!request.get("descend").getAsBoolean())return;
        if(request.has("entityId")||request.has("distance"))throw new IllegalArgumentException("descent_requires_hole_coordinate_without_distance");
        opening=BlockPos.ofFloored(request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble());
        for(int y=opening.getY();y>=c.world.getBottomY();y--) {
            var p=new BlockPos(opening.getX(),y,opening.getZ());
            if(!WorldActions.loaded(c,p))throw new IllegalStateException("descent_terrain_unloaded");
            var state=c.world.getBlockState(p);
            if(state.getFluidState().isIn(FluidTags.WATER)) {
                if(!waterSafe(c,p))throw new IllegalStateException("descent_water_landing_unverified");
                landing=p;
                request.add("descentFrom",new com.google.gson.Gson().toJsonTree(new double[]{request.get("x").getAsDouble(),request.get("y").getAsDouble(),request.get("z").getAsDouble()}));
                request.addProperty("x",p.getX()+.5);request.addProperty("y",p.getY()+.25);request.addProperty("z",p.getZ()+.5);
                request.addProperty("distance",.8);
                return;
            }
            if(!state.getCollisionShape(c.world,p).isEmpty()||!state.getFluidState().isEmpty()||!WorldActions.safeSupport(c,p))
                throw new IllegalStateException("descent_shaft_obstructed");
        }
        throw new IllegalStateException("descent_water_landing_missing");
    }
    private static boolean waterSafe(MinecraftClient c,BlockPos p) {
        var state=c.world.getBlockState(p);var fluid=state.getFluidState();
        if(!WorldActions.loaded(c,p)||!fluid.isIn(FluidTags.WATER)||fluid.getHeight(c.world,p)<.75
            ||!state.getCollisionShape(c.world,p).isEmpty())return false;
        for(int depth=1;depth<=4;depth++) {
            var below=p.down(depth);
            if(!WorldActions.loaded(c,below)||!WorldActions.safeSupport(c,below))return false;
            var block=c.world.getBlockState(below);
            if(block.getFluidState().isIn(FluidTags.WATER)&&block.getCollisionShape(c.world,below).isEmpty())continue;
            return block.isSideSolidFullSquare(c.world,below,Direction.UP);
        }
        return false;
    }
    static boolean valid(MinecraftClient c){return landing==null||waterSafe(c,landing);}
}
