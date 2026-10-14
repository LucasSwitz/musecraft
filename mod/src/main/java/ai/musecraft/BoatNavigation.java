package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.*;

/** Surface-only boat routing with vanilla paddle input and inertial braking. */
final class BoatNavigation {
    private static final Gson JSON=new Gson();
    private static String id,status="idle",reason,vehicle;
    private static Vec3d target,lastProgress;
    private static double tolerance;
    private static long leaseUntil,progressAt;
    private static List<Vec3d> path=new ArrayList<>();
    static JsonElement start(MinecraftClient c,JsonObject p) {
        var boat=(BoatEntity)c.player.getVehicle();
        if(boat.getControllingPassenger()!=c.player)throw new IllegalStateException("not_controlling_passenger");
        target=point(c,boat,p.get("x").getAsDouble(),p.get("z").getAsDouble());
        if(target==null)throw new IllegalStateException("boat_target_requires_open_water");
        tolerance=p.has("tolerance")?p.get("tolerance").getAsDouble():.7;
        if(!Double.isFinite(tolerance)||tolerance<.15||tolerance>2)throw new IllegalArgumentException("invalid_tolerance");
        tolerance=Math.max(.5,tolerance);
        id=UUID.randomUUID().toString();vehicle=boat.getUuidAsString();status="running";reason=null;path.clear();
        lastProgress=boat.getPos();progressAt=System.currentTimeMillis();leaseUntil=progressAt+5000;
        return snapshot(c);
    }
    static boolean owns(String value){return id!=null&&id.equals(value);}
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(!owns(p.get("id").getAsString()))throw new IllegalStateException("movement_identity_changed");
        if(status.equals("running"))leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static JsonElement snapshot(MinecraftClient c) {
        var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("status",status);out.put("reason",reason);out.put("mode","boat");
        if(c.player!=null)out.put("pos",List.of(c.player.getX(),c.player.getY(),c.player.getZ()));
        if(target!=null)out.put("target",List.of(target.x,target.y,target.z));
        return JSON.toJsonTree(out);
    }
    static void stop(MinecraftClient c,String terminal,String why) {
        if(!status.equals("running"))return;
        status=terminal;reason=why;path.clear();
        if(c.player!=null) {
            c.player.input.pressingForward=false;c.player.input.pressingBack=false;c.player.input.pressingLeft=false;c.player.input.pressingRight=false;
            if(c.player.getVehicle() instanceof BoatEntity boat)boat.setInputs(false,false,false,false);
        }
    }
    private static Vec3d point(MinecraftClient c,BoatEntity boat,double x,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(z))return null;
        int y0=(int)Math.floor(boat.getY());
        for(int y=y0+2;y>=y0-2;y--) {
            var p=BlockPos.ofFloored(x,y,z);var fluid=c.world.getFluidState(p);
            if(!fluid.isIn(FluidTags.WATER)||c.world.getFluidState(p.up()).isIn(FluidTags.WATER))continue;
            var feet=new Vec3d(x,y+fluid.getHeight(c.world,p)-.35,z);
            Box box=new Box(x-.71,feet.y+.02,z-.71,x+.71,feet.y+.65,z+.71);
            if(!c.world.isSpaceEmpty(boat,box))return null;
            for(double dx:new double[]{-.65,.65})for(double dz:new double[]{-.65,.65})
                if(!c.world.getFluidState(BlockPos.ofFloored(x+dx,y,z+dz)).isIn(FluidTags.WATER))return null;
            return feet;
        }
        return null;
    }
    private static double gap(Vec3d a,Vec3d b){return Math.hypot(a.x-b.x,a.z-b.z);}
    private static boolean clear(MinecraftClient c,BoatEntity boat,Vec3d a,Vec3d b) {
        int n=Math.max(1,(int)Math.ceil(gap(a,b)*5));
        for(int i=0;i<=n;i++){var p=a.lerp(b,(double)i/n);if(point(c,boat,p.x,p.z)==null)return false;}
        return true;
    }
    private record Node(Vec3d point,double cost,double score){}
    private static List<Vec3d> route(MinecraftClient c,BoatEntity boat,Vec3d from) {
        var open=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score));
        Map<Vec3d,Double> costs=new HashMap<>();Map<Vec3d,Vec3d> parents=new HashMap<>();
        costs.put(from,0.);open.add(new Node(from,0,gap(from,target)));Vec3d end=null;int visits=0;
        while(!open.isEmpty()&&visits++<8000) {
            var n=open.poll();if(n.cost>costs.getOrDefault(n.point,Double.MAX_VALUE))continue;
            if(gap(n.point,target)<1.5&&clear(c,boat,n.point,target)){end=n.point;break;}
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                double x=Math.round(n.point.x*2)/2.0+d[0]*.5,z=Math.round(n.point.z*2)/2.0+d[1]*.5;
                if(Math.abs(x-from.x)>24||Math.abs(z-from.z)>24)continue;
                var next=point(c,boat,x,z);if(next==null||!clear(c,boat,n.point,next))continue;
                double cost=n.cost+gap(n.point,next);if(cost>=costs.getOrDefault(next,Double.MAX_VALUE))continue;
                costs.put(next,cost);parents.put(next,n.point);open.add(new Node(next,cost,cost+gap(next,target)));
            }
        }
        if(end==null)return List.of();
        var result=new LinkedList<Vec3d>();result.add(target);
        for(var p=end;!p.equals(from);p=parents.get(p))result.addFirst(p);
        return result;
    }
    static void apply(MinecraftClient c,Input input) {
        if(!status.equals("running"))return;
        input.movementForward=0;input.movementSideways=0;input.jumping=false;input.sneaking=false;
        input.pressingForward=false;input.pressingBack=false;input.pressingLeft=false;input.pressingRight=false;
        if(!(c.player.getVehicle() instanceof BoatEntity boat)||!boat.getUuidAsString().equals(vehicle)){stop(c,"cancelled","vehicle_changed");return;}
        long now=System.currentTimeMillis();
        if(now>=leaseUntil){stop(c,"cancelled","movement_lease_expired");return;}
        var here=boat.getPos();double remaining=gap(here,target);
        if(remaining<=tolerance&&boat.getVelocity().horizontalLength()<.035){stop(c,"completed","arrived");return;}
        if(gap(here,lastProgress)>.25){lastProgress=here;progressAt=now;}
        if(now-progressAt>8000){stop(c,"blocked","boat_no_progress");return;}
        while(path.size()>1&&gap(here,path.getFirst())<.7)path.removeFirst();
        if(path.isEmpty()) {
            if(clear(c,boat,here,target))path.add(target);
            else path=new ArrayList<>(route(c,boat,here));
            if(path.isEmpty()){stop(c,"blocked","no_safe_boat_route");return;}
        }
        Vec3d next=path.getFirst(),delta=next.subtract(here);
        double desired=Math.toDegrees(Math.atan2(-delta.x,delta.z));
        double angle=MathHelper.wrapDegrees(desired-boat.getYaw());
        input.pressingLeft=angle<-4;input.pressingRight=angle>4;
        double radians=Math.toRadians(boat.getYaw());
        double forward=boat.getVelocity().x*-Math.sin(radians)+boat.getVelocity().z*Math.cos(radians);
        double speed=Math.min(.22,Math.max(0,remaining-tolerance*.6)*.13);
        input.pressingForward=Math.abs(angle)<35&&forward<speed-.015;
        input.pressingBack=forward>speed+.015;
        c.player.setYaw(boat.getYaw());c.player.setPitch(12);
    }
}
