package ai.musecraft;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;

/** Read-only recovery choices. Never moves the player or changes the requested plan. */
final class PlacementSuggestion {
    static Map<String,Object> find(MinecraftClient c,JsonObject input) {
        var request=input.deepCopy();
        var target=new BlockPos(input.get("x").getAsInt(),input.get("y").getAsInt(),input.get("z").getAsInt());
        if(!request.has("block")) {
            if(!request.has("item")||!(Registries.ITEM.get(Identifier.of(request.get("item").getAsString())) instanceof BlockItem item))return Map.of();
            request.addProperty("block",Registries.BLOCK.getId(item.getBlock()).toString());
            if(!c.world.getBlockState(target).isReplaceable()&&request.has("face")) {
                var side=Direction.byName(request.get("face").getAsString());
                if(side!=null)target=target.offset(side);
            }
        }
        var here=c.player.getPos();
        var stances=new ArrayList<Vec3d>();stances.add(here);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)
            stances.addAll(NavigationTerrain.travelSurfaces(c,Math.floor(here.x)+x+.5,Math.floor(here.z)+z+.5,here.y-1,here.y+1,true));
        stances.sort(Comparator.comparingDouble(here::squaredDistanceTo));
        // Preserve the requested block position when a step aside is sufficient.
        for(var stance:stances)if(valid(c,request,target,stance))return suggestion(request,target,stance);
        // Otherwise offer a nearby empty site usable from the current position.
        var choices=new ArrayList<BlockPos>();
        for(var pos:BlockPos.iterate(target.add(-2,-1,-2),target.add(2,1,2)))choices.add(pos.toImmutable());
        final var desired=target;
        choices.sort(Comparator.comparingDouble(p->p.getSquaredDistance(desired)));
        for(var pos:choices)if(valid(c,request,pos,here))return suggestion(request,pos,here);
        return Map.of();
    }
    private static boolean valid(MinecraftClient c,JsonObject request,BlockPos pos,Vec3d stance) {
        if(!WorldActions.loaded(c,pos)||!c.world.getBlockState(pos).isReplaceable()
            ||!Navigation.valid(c,stance,false,false)||NavigationTerrain.body(stance).intersects(new Box(pos)))return false;
        var eye=stance.add(0,c.player.getStandingEyeHeight(),0);
        if(eye.distanceTo(pos.toCenterPos())>c.player.getBlockInteractionRange()+.5)return false;
        request.addProperty("x",pos.getX());request.addProperty("y",pos.getY());request.addProperty("z",pos.getZ());
        return WorldActions.placement(c,request,eye,true)!=null;
    }
    private static Map<String,Object> suggestion(JsonObject request,BlockPos pos,Vec3d stance) {
        return Map.of("position",List.of(pos.getX(),pos.getY(),pos.getZ()),"block",request.get("block").getAsString(),
            "standingPosition",List.of(stance.x,stance.y,stance.z),"standingDistance",.25,
            "validation","current_world_placement_geometry","routeChecked",false);
    }
}
