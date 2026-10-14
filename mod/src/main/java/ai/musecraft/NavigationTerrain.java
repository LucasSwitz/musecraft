package ai.musecraft;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.ShapeContext;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.*;

/** Shared collision geometry for walking, routing and action standing positions. */
final class NavigationTerrain {
    private static final double EPS=.001;
    private static double halfWidth(MinecraftClient c) {
        return c.player!=null&&c.player.hasVehicle()?c.player.getVehicle().getWidth()/2.:.299;
    }
    static Box body(Vec3d feet) {
        var c=MinecraftClient.getInstance();
        double half=halfWidth(c),height=1.8;
        if(c.player!=null&&c.player.hasVehicle()) {
            var vehicle=c.player.getVehicle();
            height=Math.max(vehicle.getHeight(),c.player.getBoundingBox().maxY-vehicle.getY());
        }
        return new Box(feet.x-half,feet.y+EPS,feet.z-half,feet.x+half,feet.y+height-EPS,feet.z+half);
    }
    static boolean empty(MinecraftClient c,Vec3d feet,boolean doors) {
        return empty(c,feet,doors,false);
    }
    private static boolean empty(MinecraftClient c,Vec3d feet,boolean doors,boolean water) {
        return emptyBox(c,body(feet),doors,water);
    }
    private static boolean emptyBox(MinecraftClient c,Box body,boolean doors,boolean water) {
        // Fences and walls extend above their owning block, so include the row below.
        for(BlockPos p:BlockPos.iterate(BlockPos.ofFloored(body.minX,body.minY-1,body.minZ),BlockPos.ofFloored(body.maxX,body.maxY,body.maxZ))) {
            if(!WorldActions.loaded(c,p))return false;
            var state=c.world.getBlockState(p);
            if(new Box(p).intersects(body)&&!WorldActions.safeSupport(c,p))return false;
            var fluid=c.world.getFluidState(p);
            if(!fluid.isEmpty()&&!(water&&fluid.isIn(FluidTags.WATER))&&new Box(p.getX(),p.getY(),p.getZ(),p.getX()+1,p.getY()+fluid.getHeight(c.world,p),p.getZ()+1).intersects(body))return false;
            if(doors&&Navigation.door(c,p))continue;
            for(Box box:NavigationMap.shapes(c,p))
                if(box.offset(p).intersects(body))return false;
        }
        // Vanilla boats/shulkers have solid entity collision shapes. A route
        // through their body is just as blocked as a route through a block.
        for(var shape:c.world.getEntityCollisions(c.player,body))
            for(Box box:shape.getBoundingBoxes())if(box.intersects(body))return false;
        return true;
    }
    static List<Vec3d> surfaces(MinecraftClient c,double x,double z,double min,double max,boolean doors) {
        Set<Double> heights=new TreeSet<>();
        double half=halfWidth(c);
        for(BlockPos p:BlockPos.iterate(BlockPos.ofFloored(x-half,min-1.5,z-half),BlockPos.ofFloored(x+half,max,z+half))) {
            if(!WorldActions.loaded(c,p)||!WorldActions.safeSupport(c,p)||Navigation.door(c,p))continue;
            for(Box local:NavigationMap.shapes(c,p)) {
                Box b=local.offset(p);
                if(b.maxX<=x-half||b.minX>=x+half||b.maxZ<=z-half||b.minZ>=z+half)continue;
                if(b.maxY>=min-EPS&&b.maxY<=max+EPS)heights.add(b.maxY);
            }
        }
        // Dismounting can leave the player standing on the boat. Include its
        // top as a real support so the route can walk to its edge before falling
        // into water, instead of targeting an unreachable point inside it.
        Box search=new Box(x-half,min-1.5,z-half,x+half,max+EPS,z+half);
        for(var shape:c.world.getEntityCollisions(c.player,search)) {
            for(Box b:shape.getBoundingBoxes()) {
                if(b.maxX<=x-half||b.minX>=x+half||b.maxZ<=z-half||b.minZ>=z+half)continue;
                if(b.maxY>=min-EPS&&b.maxY<=max+EPS)heights.add(b.maxY);
            }
        }
        List<Vec3d> result=new ArrayList<>();
        for(double y:heights) {var feet=new Vec3d(x,y,z);if(empty(c,feet,doors))result.add(feet);}
        return result;
    }
    static boolean swimming(MinecraftClient c,Vec3d feet) {
        return c.world.getFluidState(BlockPos.ofFloored(feet.add(0,.15,0))).isIn(FluidTags.WATER);
    }
    static boolean climbing(MinecraftClient c,Vec3d feet) {
        return c.world.getBlockState(BlockPos.ofFloored(feet.add(0,.1,0))).isIn(BlockTags.CLIMBABLE)
            ||c.world.getBlockState(BlockPos.ofFloored(feet.add(0,-.05,0))).isIn(BlockTags.CLIMBABLE);
    }
    static boolean travelValid(MinecraftClient c,Vec3d feet,boolean doors) {
        return empty(c,feet,doors,true)&&(supported(c,feet,doors)||swimming(c,feet)||climbing(c,feet));
    }
    static List<Vec3d> travelSurfaces(MinecraftClient c,double x,double z,double min,double max,boolean doors) {
        var result=surfaces(c,x,z,min,max,doors);
        for(int y=(int)Math.floor(min);y<=Math.ceil(max);y++) {
            var p=BlockPos.ofFloored(x,y,z);
            var fluid=c.world.getFluidState(p);
            if(fluid.isIn(FluidTags.WATER)&&!c.world.getFluidState(p.up()).isIn(FluidTags.WATER)) {
                // Keep the player's head above the surface. Underwater expeditions
                // require an explicit route/breathing policy, not accidental dives.
                var feet=new Vec3d(x,y+fluid.getHeight(c.world,p)-.5,z);
                if(feet.y>=min&&feet.y<=max&&empty(c,feet,doors,true))result.add(feet);
            }
            var feet=new Vec3d(x,y,z);
            if(climbing(c,feet)&&empty(c,feet,doors,true))result.add(feet);
        }
        return result;
    }
    static boolean travelSweep(MinecraftClient c,Vec3d from,Vec3d to,boolean doors) {
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*10));
        for(int i=0;i<=steps;i++)if(!empty(c,from.lerp(to,(double)i/steps),doors,true))return false;
        return true;
    }
    static boolean travelLine(MinecraftClient c,Vec3d from,Vec3d to,boolean doors) {
        if(!travelSweep(c,from,to,doors))return false;
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*10));
        for(int i=0;i<=steps;i++)if(!travelValid(c,from.lerp(to,(double)i/steps),doors))return false;
        return true;
    }
    static boolean travelEdge(MinecraftClient c,Vec3d from,Vec3d to,boolean doors) {
        boolean climb=climbing(c,from)||climbing(c,to),swim=swimming(c,from)||swimming(c,to);
        if(!climb&&!swim)return edge(c,from,to);
        if(Math.abs(to.y-from.y)>1.25)return false;
        if(climb)return travelSweep(c,from,to,doors);
        if(swimming(c,from)||swimming(c,to)) {
            if(travelSweep(c,from,to,doors))return true;
            // Lift to the dry bank while pushing forward, as vanilla swimming does.
            double top=Math.max(from.y,to.y);
            return travelSweep(c,from,new Vec3d(from.x,top,from.z),doors)
                &&travelSweep(c,new Vec3d(from.x,top,from.z),new Vec3d(to.x,top,to.z),doors)
                &&travelSweep(c,new Vec3d(to.x,top,to.z),to,doors);
        }
        return edge(c,from,to);
    }
    static boolean supported(MinecraftClient c,Vec3d feet,boolean doors) {
        return fullFloor(c,feet,feet)||!surfaces(c,feet.x,feet.z,feet.y-.03,feet.y+.03,doors).isEmpty();
    }
    static Vec3d snap(MinecraftClient c,Vec3d point) {
        return travelSurfaces(c,point.x,point.z,point.y-1.25,point.y+1.25,true).stream()
            .min(Comparator.comparingDouble(v->Math.abs(v.y-point.y))).orElse(point);
    }
    static boolean sweep(MinecraftClient c,Vec3d from,Vec3d to,boolean doors) {
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)*10));
        for(int i=0;i<=steps;i++)if(!empty(c,from.lerp(to,(double)i/steps),doors))return false;
        return true;
    }
    static boolean fullFloor(MinecraftClient c,Vec3d from,Vec3d to) {
        if(Math.abs(from.y-to.y)>EPS||Math.abs(from.y-Math.rint(from.y))>EPS)return false;
        Box floor=body(from).union(body(to));
        int y=(int)Math.rint(from.y)-1;
        for(BlockPos p:BlockPos.iterate(BlockPos.ofFloored(floor.minX,y,floor.minZ),BlockPos.ofFloored(floor.maxX,y,floor.maxZ))) {
            if(!WorldActions.loaded(c,p)||!WorldActions.safeSupport(c,p))return false;
            var boxes=NavigationMap.shapes(c,p);
            if(boxes.size()!=1)return false;
            Box b=boxes.getFirst();
            if(b.minX!=0||b.minY!=0||b.minZ!=0||b.maxX!=1||b.maxY!=1||b.maxZ!=1)return false;
        }
        return true;
    }
    static boolean walk(MinecraftClient c,Vec3d from,Vec3d to,boolean doors) {
        // Common flat terrain needs one swept body check, not ten repeated
        // surface searches and nested sweeps per block. The enclosing box is
        // conservative for diagonals; specialised shapes retain the exact path.
        if(from.distanceTo(to)<=2.01&&fullFloor(c,from,to))
            return emptyBox(c,body(from).union(body(to)),doors,false);

        int steps=Math.max(1,(int)Math.ceil(Math.hypot(from.x-to.x,from.z-to.z)*10));
        Vec3d previous=from;
        for(int i=1;i<=steps;i++) {
            Vec3d point=from.lerp(to,(double)i/steps);
            final double y=previous.y;
            Vec3d next=surfaces(c,point.x,point.z,y-.6,y+.6,doors).stream()
                .min(Comparator.comparingDouble(v->Math.abs(v.y-y))).orElse(null);
            if(next==null)return false;
            double top=Math.max(previous.y,next.y);
            if(!sweep(c,previous,new Vec3d(previous.x,top,previous.z),doors)
                ||!sweep(c,new Vec3d(previous.x,top,previous.z),new Vec3d(next.x,top,next.z),doors))return false;
            previous=next;
        }
        return Math.abs(previous.y-to.y)<.04;
    }
    private static double jumpRise(MinecraftClient c,Vec3d feet) {
        if(c.player.hasVehicle())return 1.25;
        // Vanilla applies the current/underfoot block's jump multiplier. Honey
        // must not be planned as an ordinary full-height takeoff surface.
        float multiplier=c.world.getBlockState(BlockPos.ofFloored(feet)).getBlock().getJumpVelocityMultiplier();
        if(multiplier==1)multiplier=c.world.getBlockState(BlockPos.ofFloored(feet.add(0,-.500001,0))).getBlock().getJumpVelocityMultiplier();
        double velocity=c.player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_JUMP_STRENGTH)*multiplier
            +c.player.getJumpBoostVelocityModifier();
        double gravity=c.player.getFinalGravity(),rise=0;
        for(int tick=0;tick<40&&velocity>0;tick++){rise+=velocity;velocity=(velocity-gravity)*.98;}
        return Math.min(1.25,rise);
    }
    static boolean edge(MinecraftClient c,Vec3d from,Vec3d to) {
        if(to.y-from.y>1.25||from.y-to.y>3)return false;
        if(walk(c,from,to,true))return true;
        double rise=jumpRise(c,from);
        if(to.y-from.y>rise+EPS)return false;
        // Require clearance for the ascent, crossing and descent of this jump.
        double top=to.y<from.y-.6?from.y:Math.max(from.y+rise,to.y);
        var a=new Vec3d(from.x,top,from.z);var b=new Vec3d(to.x,top,to.z);
        return sweep(c,from,a,true)&&sweep(c,a,b,true)&&sweep(c,b,to,true);
    }
}
