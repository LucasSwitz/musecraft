package ai.musecraft;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.*;

/** Local body-clearance recovery after ordinary Baritone pickup routing fails. */
final class PickupAccess {
    private record Approach(Vec3d feet,List<BlockPos> blocks) {}
    static Map<String,Object> next(MinecraftClient c,JsonObject request) {
        var entity=c.world.getEntityById(request.get("entityId").getAsInt());
        if(!(entity instanceof ItemEntity item)||!item.isAlive()
            ||!item.getUuidAsString().equals(request.get("uuid").getAsString()))
            return Map.of("found",false,"reason","target_gone");
        var from=c.player.getPos();var target=item.getPos();
        if(c.player.hasVehicle()||Math.hypot(from.x-target.x,from.z-target.z)>4.5
            ||from.y-target.y>3.5||target.y-from.y>2.5)
            return Map.of("found",false,"reason","pickup_clearance_out_of_reach");
        var area=request.getAsJsonObject("area");var near=area.getAsJsonArray("near");
        var volume=BlockVolume.around(new Vec3d(near.get(0).getAsDouble(),near.get(1).getAsDouble(),near.get(2).getAsDouble()),area.get("radius").getAsInt());
        var approaches=new ArrayList<Approach>();
        var center=BlockPos.ofFloored(target);
        // Sample real support surfaces, allowing head/body obstructions above
        // them. A free ray to the item alone does not prove a player can enter.
        for(int x=center.getX()-1;x<=center.getX()+1;x++)for(int z=center.getZ()-1;z<=center.getZ()+1;z++)
            for(int y=center.getY()-3;y<=center.getY();y++) {
                var floor=new BlockPos(x,y,z);
                if(!WorldActions.loaded(c,floor)||!WorldActions.safe(c,floor))continue;
                for(var shape:NavigationMap.shapes(c,floor)) {
                    var feet=new Vec3d(x+.5,y+shape.maxY,z+.5);
                    if(feet.y>from.y+.01||from.y-feet.y>3||!NavigationTerrain.fullFloor(c,feet,feet)
                        ||!NavigationTerrain.body(feet).expand(1,.5,1).intersects(item.getBoundingBox()))continue;
                    var blocks=corridor(c,from,feet);
                    if(blocks==null)continue;
                    // A clear local corridor with a failed route needs navigation
                    // diagnosis, not speculative demolition of another approach.
                    if(blocks.isEmpty())return Map.of("found",false,"reason","pickup_route_unresolved");
                    if(blocks.size()<=8)approaches.add(new Approach(feet,blocks));
                }
            }
        approaches.sort(Comparator.comparingDouble(a->a.blocks.size()*10+a.feet.distanceTo(from)));
        Map<String,Object> refusal=null;
        for(var approach:approaches) {
            var evidence=new ArrayList<Map<String,Object>>();String reason=null;
            for(var pos:approach.blocks) {
                String why=!volume.contains(pos)?"access_outside_gather_area":BlockClearance.reason(c,pos);
                // Do not expose a liquid source or release falling material onto
                // this small access corridor. The agent can handle those cases.
                if(why==null)for(var direction:Direction.values()) {
                    var adjacent=pos.offset(direction);
                    if(!WorldActions.loaded(c,adjacent)){why="terrain_unloaded";break;}
                    if(!c.world.getFluidState(adjacent).isEmpty()){why="fluid_obstruction";break;}
                }
                if(why==null&&c.world.getBlockState(pos.up()).getBlock() instanceof net.minecraft.block.FallingBlock)
                    why="unsafe_clearance";
                evidence.add(Map.of("pos",BuildPlan.point(pos),
                    "block",Registries.BLOCK.getId(c.world.getBlockState(pos).getBlock()).toString(),
                    "role",pos.getY()>=from.y+1?"head_clearance":"body_clearance"));
                if(why!=null&&reason==null)reason=why;
            }
            var out=new LinkedHashMap<String,Object>();out.put("found",false);
            out.put("destination",List.of(approach.feet.x,approach.feet.y,approach.feet.z));
            out.put("obstructions",evidence);out.put("reason",reason==null?"pickup_clearance_out_of_reach":reason);
            if(reason==null)for(var pos:approach.blocks)try {
                WorldActions.visibleFace(c,pos,null);
                out.put("found",true);out.put("reason","obstructed");
                out.put("target",BuildPlan.point(pos));
                out.put("block",Registries.BLOCK.getId(c.world.getBlockState(pos).getBlock()).toString());
                return out;
            }catch(IllegalStateException absent){ /* Try the next exposed obstruction. */ }
            if(refusal==null)refusal=out;
        }
        return refusal==null?Map.of("found",false,"reason","no_safe_pickup_access"):refusal;
    }
    /** Walk to the lip, then descend: never a diagonal ray through the floor. */
    private static List<BlockPos> corridor(MinecraftClient c,Vec3d from,Vec3d to) {
        var corner=new Vec3d(to.x,from.y,to.z);
        var blocks=new LinkedHashSet<BlockPos>();
        for(var segment:List.of(new Vec3d[]{from,corner},new Vec3d[]{corner,to})) {
            int steps=Math.max(1,(int)Math.ceil(segment[0].distanceTo(segment[1])*4));
            for(int n=0;n<=steps;n++) {
                var body=NavigationTerrain.body(segment[0].lerp(segment[1],(double)n/steps));
                if(!c.world.getEntityCollisions(c.player,body).isEmpty())return null;
                for(var p:BlockPos.iterate(BlockPos.ofFloored(body.minX,body.minY-1,body.minZ),BlockPos.ofFloored(body.maxX,body.maxY,body.maxZ))) {
                    if(!WorldActions.loaded(c,p))return null;
                    if(!c.world.getFluidState(p).isEmpty()&&new Box(p).intersects(body))return null;
                    for(var shape:NavigationMap.shapes(c,p))if(shape.offset(p).intersects(body))blocks.add(p.toImmutable());
                }
            }
        }
        return blocks.stream().sorted(Comparator.comparingInt(BlockPos::getY).reversed()).toList();
    }
}
