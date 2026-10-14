package ai.musecraft;

import java.util.*;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

/** Resolve the next real block on an excavation ray, re-evaluated after each removal. */
final class MiningAccess {
    static boolean removable(MinecraftClient c,BlockPos target,Vec3d feet) {
        if(!WorldActions.loaded(c,target))return false;
        var state=c.world.getBlockState(target);
        if(state.isAir()||state.getHardness(c.world,target)<0||!state.getFluidState().isEmpty())return false;
        return ResourceProtection.reason(c,target)==null&&AccessDiagnostics.safeRemoval(c,target,feet);
    }

    static List<List<Double>> stances(MinecraftClient c,JsonObject request) {
        var target=new BlockPos(request.get("x").getAsInt(),request.get("y").getAsInt(),request.get("z").getAsInt());
        var choices=new ArrayList<Vec3d>();
        // Distinct sides and heights, rather than repeatedly selecting the same
        // nearest endpoint. Navigation still proves each route before movement.
        for(double radius:new double[]{1.5,2.5,3.5})for(int side=0;side<8;side++) {
            double angle=side*Math.PI/4;
            double x=Math.floor(target.getX()+.5+radius*Math.cos(angle))+.5;
            double z=Math.floor(target.getZ()+.5+radius*Math.sin(angle))+.5;
            for(var feet:NavigationTerrain.travelSurfaces(c,x,z,target.getY()-4,target.getY()+1,false)) {
                if(feet.distanceTo(c.player.getPos())<.6||!Navigation.valid(c,feet,false,false))continue;
                var hit=c.world.raycast(new RaycastContext(feet.add(0,c.player.getStandingEyeHeight(),0),
                    Vec3d.ofCenter(target),RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,c.player));
                if(hit.getType()==HitResult.Type.BLOCK&&removable(c,hit.getBlockPos(),feet))choices.add(feet);
            }
        }
        choices.sort(Comparator.comparingDouble(p->p.distanceTo(c.player.getPos())));
        var distinct=new ArrayList<Vec3d>();
        for(var feet:choices)if(distinct.stream().noneMatch(p->p.distanceTo(feet)<1.5))distinct.add(feet);
        return distinct.stream().limit(8).map(p->List.of(p.x,p.y,p.z)).toList();
    }
    static Map<String,Object> evidence(MinecraftClient c,BlockPos target) {
        Vec3d eye=c.player.getEyePos();
        var evidence=new LinkedHashMap<String,Object>();
        var support=AccessDiagnostics.supportRemoval(c,target);
        if(support!=null)evidence.put("supportRemoval",support);
        evidence.put("target",List.of(target.getX(),target.getY(),target.getZ()));
        evidence.put("playerPosition",List.of(c.player.getX(),c.player.getY(),c.player.getZ()));
        evidence.put("heightAboveFeet",target.getY()-c.player.getY());
        evidence.put("reach",c.player.getBlockInteractionRange());
        evidence.put("aboveReach",target.getY()>eye.y+c.player.getBlockInteractionRange());
        evidence.put("minimumRise",Math.max(0,(int)Math.ceil(target.getY()-eye.y-c.player.getBlockInteractionRange())));
        evidence.put("onGround",c.player.isOnGround());
        evidence.put("eyePosition",List.of(eye.x,eye.y,eye.z));
        var ray=c.world.raycast(new RaycastContext(eye,Vec3d.ofCenter(target),
            RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,c.player));
        if(ray.getType()==HitResult.Type.BLOCK&&!ray.getBlockPos().equals(target)) {
            var p=ray.getBlockPos();evidence.put("obstruction",Map.of("position",List.of(p.getX(),p.getY(),p.getZ()),
                "block",net.minecraft.registry.Registries.BLOCK.getId(c.world.getBlockState(p).getBlock()).toString()));
        }
        return evidence;
    }
    static Map<String,Object> next(MinecraftClient c,JsonObject request) {
        BlockPos target=new BlockPos(request.get("x").getAsInt(),request.get("y").getAsInt(),request.get("z").getAsInt());
        if(!WorldActions.loaded(c,target))throw new IllegalStateException("terrain_unloaded");
        if(BlockMining.clear(c.world.getBlockState(target)))return Map.of("done",true);
        ResourceProtection.start(c);
        Vec3d eye=c.player.getEyePos();
        var evidence=evidence(c,target);
        var protection=ResourceProtection.reason(c,target);
        if(protection!=null)return Map.of("done",false,"reason","protected_structure","evidence",Map.of("target",BuildPlan.point(target),"protection",protection),
            "explanation","Preserve this structure. Gather natural resources elsewhere and craft the materials you need.");
        if(c.world.getBlockState(target).getHardness(c.world,target)<0)
            return Map.of("done",false,"reason","block_not_mineable","evidence",evidence,
                "explanation",AccessDiagnostics.name(c,target)+" at "+AccessDiagnostics.pos(target)+" cannot be broken in this game mode. Choose a route around it.");
        boolean enclosed=true;
        for(var direction:Direction.values()) {
            var neighbour=target.offset(direction);var state=c.world.getBlockState(neighbour);
            if(!WorldActions.loaded(c,neighbour)||state.getHardness(c.world,neighbour)>=0||!state.isFullCube(c.world,neighbour)) {enclosed=false;break;}
        }
        if(enclosed)return Map.of("done",false,"reason","mining_target_enclosed","evidence",evidence,
            "explanation","All six faces of the requested block are covered by full, unbreakable blocks. No mining approach can expose it in this game mode. Choose another source.");
        // Navigation and actual digging admit exposed faces, including a thin
        // edge under a roof. Interior samples alone can hit an adjacent wall
        // even when the target has a legal face; prefer the execution predicate.
        if(removable(c,target,c.player.getPos()))try {
            WorldActions.visibleFace(c,target,null);
            return Map.of("done",false,"target",List.of(target.getX(),target.getY(),target.getZ()),
                "obstruction",false,"reachable",true,"evidence",evidence);
        } catch(IllegalStateException absent) { /* Resolve a real obstruction below. */ }
        BlockPos best=null;double score=Double.POSITIVE_INFINITY;
        // Sample all sides so a visible target beats clearing an unnecessary
        // neighbour. Any breakable block type may be an access obstruction.
        for(double x:new double[]{.2,.5,.8})for(double y:new double[]{.2,.5,.8})for(double z:new double[]{.2,.5,.8}) {
            var end=new Vec3d(target.getX()+x,target.getY()+y,target.getZ()+z);
            var hit=c.world.raycast(new RaycastContext(eye,end,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,c.player));
            if(hit.getType()!=HitResult.Type.BLOCK)continue;
            var p=hit.getBlockPos();
            if(!removable(c,p,c.player.getPos()))continue;
            double cost=(p.equals(target)?0:1000)+hit.getPos().squaredDistanceTo(eye);
            if(cost<score){best=p.toImmutable();score=cost;}
        }
        // A ray can see through a one-block opening that a player's body cannot
        // traverse. Offer a reachable body/headroom obstruction when the ray's
        // next block is itself out of reach.
        if(best==null||WorldActions.visibleFaces(c,best,null,eye,true).isEmpty()) {
            var clearance=MiningClearance.next(c,target);
            if(clearance!=null) {
                if(clearance.block()!=null)best=clearance.block();
                else return Map.of("done",false,"approach",List.of(clearance.approach().x,clearance.approach().y,clearance.approach().z),"evidence",evidence);
            }
        }
        if(best==null)return Map.of("done",false,"reason",evidence.containsKey("supportRemoval")?"unsafe_support_removal":"no_safe_mining_access","evidence",evidence);
        boolean reachable;
        try {WorldActions.visibleFace(c,best,null);reachable=true;}
        catch(IllegalStateException absent){reachable=false;}
        return Map.of("done",false,"target",List.of(best.getX(),best.getY(),best.getZ()),
            "obstruction",!best.equals(target),"reachable",reachable,"evidence",evidence,
            "localPathCost",reachable&&!best.equals(target)?MiningClearance.pathCost(c,best,target):0);
    }
}
