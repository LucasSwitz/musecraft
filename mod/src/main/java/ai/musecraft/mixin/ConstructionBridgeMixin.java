package ai.musecraft.mixin;

import ai.musecraft.BaritoneBuild;
import baritone.api.pathing.movement.ActionCosts;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Upstream's backward bridge fallback assumes a full-block backing face. */
@Mixin(value=MovementTraverse.class,remap=false)
public abstract class ConstructionBridgeMixin {
    @Inject(method="cost",at=@At("RETURN"),cancellable=true,remap=false)
    private static void rejectStairBackplace(CalculationContext context,int x,int y,int z,int destX,int destZ,CallbackInfoReturnable<Double> out) {
        if(!BaritoneBuild.running()||out.getReturnValue()>=ActionCosts.COST_INF
            ||!(context.get(x,y-1,z).getBlock() instanceof StairsBlock)
            ||MovementHelper.canWalkOn(context,destX,y-1,destZ))return;
        // A side or lower full-block face still permits the ordinary placement.
        // Only reject the fallback that must place against the starting stair.
        for(var side:Direction.values()) {
            if(side==Direction.UP)continue;
            int ax=destX+side.getOffsetX(),ay=y-1+side.getOffsetY(),az=destZ+side.getOffsetZ();
            if(ax==x&&az==z)continue;
            if(MovementHelper.canPlaceAgainst(context.bsi,ax,ay,az))return;
        }
        out.setReturnValue(ActionCosts.COST_INF);
    }
}
