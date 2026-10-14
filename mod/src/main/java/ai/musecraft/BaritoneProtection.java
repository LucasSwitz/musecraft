package ai.musecraft;

import baritone.pathing.movement.CalculationContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Apply user/world boundaries both to native planning and to outbound mutations. */
public final class BaritoneProtection {
    private static volatile String refused;
    static void start(MinecraftClient c){ResourceProtection.start(c);refused=null;}
    static String refusal(){return refused;}
    public static boolean protectedCell(BlockPos pos,BlockState current) {
        return StructureProtection.reason(pos)!=null;
    }
    public static boolean breakDenied(CalculationContext context,int x,int y,int z,BlockState current) {
        var pos=new BlockPos(x,y,z);
        return ResourceProtection.reason(context,pos,current)!=null
            ||BaritoneTasks.running()&&!BaritoneTasks.mayBreak(context,pos,current);
    }
    public static boolean placeDenied(int x,int y,int z,BlockState current) {
        var pos=new BlockPos(x,y,z);
        return protectedCell(pos,current)||BaritoneTasks.running()
            ||BaritoneNavigation.running()&&!BaritoneNavigation.mayPlace(pos);
    }
    public static boolean rejectBreak(BlockPos pos) {
        var c=MinecraftClient.getInstance();var state=c.world.getBlockState(pos);
        if(ResourceProtection.reason(c,pos)!=null){refused="protected_structure";return true;}
        if(BaritoneTasks.running()&&!BaritoneTasks.mayBreak(new CalculationContext(BaritoneNavigation.engine()),pos,state)) {
            refused="resource_boundary_changed";return true;
        }
        return false;
    }
}
