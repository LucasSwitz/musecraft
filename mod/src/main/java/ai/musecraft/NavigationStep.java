package ai.musecraft;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;

/** The executor retains the movement transition validated by the search. */
record NavigationStep(Vec3d from,Vec3d to,Kind kind,List<BlockPos> doors) {
    enum Kind { WALK, STEP, JUMP, DROP, SWIM, CLIMB, FLY }
    static NavigationStep create(MinecraftClient c,Vec3d from,Vec3d to,boolean flying) {
        if(!Navigation.valid(c,to,flying,true))return null;
        Kind kind;
        if(flying){if(!NavigationTerrain.sweep(c,from,to,true))return null;kind=Kind.FLY;}
        else if(NavigationTerrain.climbing(c,from)||NavigationTerrain.climbing(c,to)) {
            if(!NavigationTerrain.travelEdge(c,from,to,true))return null;kind=Kind.CLIMB;
        } else if(NavigationTerrain.swimming(c,from)||NavigationTerrain.swimming(c,to)) {
            if(!NavigationTerrain.travelEdge(c,from,to,true))return null;kind=Kind.SWIM;
        } else if(NavigationTerrain.walk(c,from,to,true))kind=Math.abs(from.y-to.y)>.04?Kind.STEP:Kind.WALK;
        else {if(!NavigationTerrain.edge(c,from,to))return null;kind=to.y<from.y-.6?Kind.DROP:Kind.JUMP;}
        var doors=new ArrayList<BlockPos>();
        Box region=NavigationTerrain.body(from).union(NavigationTerrain.body(to));
        for(BlockPos p:BlockPos.iterate(BlockPos.ofFloored(region.minX,region.minY,region.minZ),BlockPos.ofFloored(region.maxX,region.maxY,region.maxZ)))
            if(Navigation.door(c,p))doors.add(p.toImmutable());
        return new NavigationStep(from,to,kind,doors);
    }
    double cost(){return from.distanceTo(to)+Math.abs(to.y-from.y)*.2+(kind==Kind.JUMP?.5:kind==Kind.SWIM?1:0);}
}
