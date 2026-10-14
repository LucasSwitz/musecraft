package ai.musecraft;

import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Candidate orientation without mutating the player's selected item or view. */
final class ConstructionContext extends ItemPlacementContext {
    private final float yaw,pitch;
    ConstructionContext(MinecraftClient c,ItemStack stack,BlockHitResult hit,float yaw,float pitch) {
        super(c.player,Hand.MAIN_HAND,stack,hit);this.yaw=yaw;this.pitch=pitch;
    }
    @Override public float getPlayerYaw(){return yaw;}
    @Override public Direction getHorizontalPlayerFacing(){return Direction.fromRotation(yaw);}
    @Override public Direction getVerticalPlayerLookDirection(){return pitch>0?Direction.DOWN:Direction.UP;}
    @Override public Direction getPlayerLookDirection(){return directions()[0];}
    private Direction[] directions() {
        Vec3d look=Vec3d.fromPolar(pitch,yaw);
        Direction[] order=Direction.values();
        Arrays.sort(order,Comparator.comparingDouble(d->-look.dotProduct(Vec3d.of(d.getVector()))));
        return order;
    }
    @Override public Direction[] getPlacementDirections(){
        Direction[] order=directions();
        if(!canReplaceExisting()) {
            Direction first=getSide().getOpposite();int i=0;while(order[i]!=first)i++;
            System.arraycopy(order,0,order,1,i);order[0]=first;
        }
        return order;
    }
}
