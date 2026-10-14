package ai.musecraft;

import baritone.api.pathing.goals.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

/** Blueprint-facing constraints for upstream placement goals. Baritone owns routes and clicks. */
public final class BuildFacingGoal {
    private record Cached(long revision,Goal goal) {}
    private static final Map<BlockPos,Cached> cache=new HashMap<>();
    static void reset(){cache.clear();}
    public static Goal constrain(BlockPos pos,Goal original) {
        var wanted=BaritoneBuild.wanted(pos);
        if(wanted==null||!wanted.has("state"))return original;
        var properties=wanted.getAsJsonObject("state");
        String axis=properties.has("axis")?properties.get("axis").getAsString():null;
        boolean horizontalAxis="x".equals(axis)||"z".equals(axis);
        if(!properties.has("facing")&&!horizontalAxis)return original;
        var cached=cache.get(pos);long revision=BaritoneBuild.geometryRevision();
        if(cached!=null&&cached.revision==revision)return cached.goal;
        var c=MinecraftClient.getInstance();var world=c.world;
        var desired=BuildPlan.desired(wanted);var allowed=EnumSet.noneOf(Direction.class);
        String facing=properties.has("facing")?properties.get("facing").getAsString():null;
        for(var look:Direction.Type.HORIZONTAL) {
            var context=new AutomaticItemPlacementContext(world,pos,look,new ItemStack(desired.getBlock().asItem()),Direction.UP);
            var placed=desired.getBlock().getPlacementState(context);
            if(placed!=null&&(facing==null||facing.equals(WorldActions.properties(placed).get("facing"))))allowed.add(look);
        }
        if(allowed.isEmpty())return original;
        var faces=new ArrayList<BlockHitResult>();
        for(var side:Direction.values()) {
            if(horizontalAxis&&!side.getAxis().asString().equals(axis))continue;
            var anchor=pos.offset(side);var state=world.getBlockState(anchor);
            var shape=state.getOutlineShape(world,anchor);
            if(state.isReplaceable()||shape.isEmpty())continue;
            var box=shape.getBoundingBox();
            for(double a:new double[]{.25,.5,.75})for(double b:new double[]{.25,.5,.75}) {
                double x=side.getAxis()==Direction.Axis.X?(side==Direction.EAST?box.minX:box.maxX):box.minX+(box.maxX-box.minX)*a;
                double y=side.getAxis()==Direction.Axis.Y?(side==Direction.UP?box.minY:box.maxY):box.minY+(box.maxY-box.minY)*b;
                double z=side.getAxis()==Direction.Axis.Z?(side==Direction.SOUTH?box.minZ:box.maxZ):box.minZ+(box.maxZ-box.minZ)*(side.getAxis()==Direction.Axis.X?a:b);
                faces.add(new BlockHitResult(new Vec3d(anchor.getX()+x,anchor.getY()+y,anchor.getZ()+z),side.getOpposite(),anchor,false));
            }
        }
        var goals=new ArrayList<Goal>();int minY=world.getBlockState(pos.up()).isAir()?pos.getY():pos.getY()-1;
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=minY;y<=pos.getY()+2;y++) {
            int dy=y-pos.getY();if(x*x+z*z+dy*dy>9)continue;
            boolean facingSide=horizontalAxis;
            for(var look:allowed)if(-x*look.getOffsetX()-z*look.getOffsetZ()>=Math.abs(x*look.getOffsetZ()-z*look.getOffsetX())+2)facingSide=true;
            if(!facingSide)continue;
            // A goal is a whole arrival cell, not a precise center. Keep its
            // corners visible so entering the cell is enough to perform a click.
            boolean visible=true;
            for(double dx:new double[]{.2,.8})for(double dz:new double[]{.2,.8}) {
                var eye=new Vec3d(pos.getX()+x+dx,y+1.27,pos.getZ()+z+dz);
                boolean corner=false;
                for(var face:faces) {
                    var point=face.getPos();if(eye.distanceTo(point)>4.35)continue;
                    var end=point.add(point.subtract(eye).normalize().multiply(.02));
                    var hit=world.raycast(new RaycastContext(eye,end,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,c.player));
                    if(hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(face.getBlockPos())&&hit.getSide()==face.getSide()){corner=true;break;}
                }
                if(!corner)visible=false;
            }
            if(visible)goals.add(new GoalBlock(pos.getX()+x,y,pos.getZ()+z));
        }
        // No real clickable face yet: retain upstream's scaffolding goal.
        // Horizontal logs require a side face on their axis. Do not let a
        // satisfied but unusable goal prevent a neighboring blueprint block
        // from being placed first; its acknowledgement refreshes this cache.
        Goal result=goals.isEmpty()&&!horizontalAxis?original:new GoalComposite(goals.toArray(new Goal[0]));
        cache.put(pos.toImmutable(),new Cached(revision,result));return result;
    }
}
