package ai.musecraft;

import java.util.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;

/** Main-thread-only geometry and movement cache. Execution still checks live collision. */
final class NavigationMap {
    private record Shape(BlockState state, List<Box> boxes) {}
    private record Column(double x,double z,int band) {}
    private record Edge(Vec3d from,Vec3d to) {}
    private static final Map<BlockPos,Shape> shapes=new HashMap<>();
    private static Object world;
    private static NavigationMap shared;
    private final boolean flying;
    private final Object vehicle;
    private final double jump,gravity;
    private final Map<Column,List<Vec3d>> columns=new HashMap<>();
    private final Map<Vec3d,Boolean> valid=new HashMap<>();
    private final Map<Edge,NavigationStep> edges=new HashMap<>();
    private Vec3d center;
    private int entitiesHash, chunksHash;
    private final Set<BlockPos> dirty=new HashSet<>();
    private boolean clearDirty;
    long revision,hits,misses;

    private NavigationMap(MinecraftClient c,boolean fly,Vec3d origin) {
        flying=fly;center=origin;vehicle=c.player.getVehicle();
        jump=c.player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_JUMP_STRENGTH)+c.player.getJumpBoostVelocityModifier();
        gravity=c.player.getFinalGravity();
    }
    static NavigationMap acquire(MinecraftClient c,boolean fly,Vec3d origin) {
        ensureWorld(c);
        double jump=c.player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_JUMP_STRENGTH)+c.player.getJumpBoostVelocityModifier();
        if(shared==null||shared.flying!=fly||shared.vehicle!=c.player.getVehicle()
            ||shared.jump!=jump||shared.gravity!=c.player.getFinalGravity()||shared.center.distanceTo(origin)>8)
            shared=new NavigationMap(c,fly,origin);
        return shared;
    }
    static void chunkChanged(int x,int z) {
        shapes.keySet().removeIf(p->(p.getX()>>4)==x&&(p.getZ()>>4)==z);
        // A view-distance chunk arriving far away cannot change this local
        // search. Include the search window, map reuse margin and body width.
        if(shared!=null&&Math.abs(x*16+7.5-shared.center.x)<=42&&Math.abs(z*16+7.5-shared.center.z)<=42) {
            shared.clearDirty=true;shared.revision++;
        }
    }
    static boolean current(NavigationMap map,MinecraftClient c,boolean fly) {
        return shared==map&&world==c.world&&map.flying==fly&&map.vehicle==c.player.getVehicle()
            &&map.jump==c.player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_JUMP_STRENGTH)+c.player.getJumpBoostVelocityModifier()
            &&map.gravity==c.player.getFinalGravity();
    }
    private static void ensureWorld(MinecraftClient c) {
        if(world!=c.world){world=c.world;shapes.clear();shared=null;}
    }
    static List<Box> shapes(MinecraftClient c,BlockPos pos) {
        ensureWorld(c);
        var state=c.world.getBlockState(pos);
        if(state.isAir())return List.of();
        var cached=shapes.get(pos);
        if(cached!=null&&cached.state==state)return cached.boxes;
        // Context-sensitive shapes (e.g. scaffolding) must follow current player state.
        var shape=state.getCollisionShape(c.world,pos,ShapeContext.of(c.player));
        var boxes=shape.getBoundingBoxes();
        if(shapes.size()>8192)shapes.clear();
        if(!state.isOf(net.minecraft.block.Blocks.SCAFFOLDING)&&!state.isOf(net.minecraft.block.Blocks.POWDER_SNOW))
            shapes.put(pos.toImmutable(),new Shape(state,boxes));
        return boxes;
    }
    static void changed(BlockPos p) {
        // Neighbour-dependent shapes and transitions can cross their cell boundary.
        for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)shapes.remove(p.add(x,y,z));
        if(shared==null||Math.abs(p.getX()-shared.center.x)>34||Math.abs(p.getZ()-shared.center.z)>34
            ||Math.abs(p.getY()-shared.center.y)>28)return;
        if(shared.dirty.size()<32)shared.dirty.add(p.toImmutable());else shared.clearDirty=true;
        shared.revision++;
    }

    private static boolean near(Vec3d q,BlockPos p) {
        return Math.abs(q.x-p.getX())<=2&&Math.abs(q.z-p.getZ())<=2&&Math.abs(q.y-p.getY())<=5;
    }
    boolean affects(Vec3d here,List<NavigationStep> route,Vec3d target) {
        if(clearDirty)return true;
        return dirty.stream().anyMatch(p->near(here,p)||near(target,p)||route.stream().anyMatch(e->near(e.from(),p)||near(e.to(),p)));
    }
    boolean affects(NavigationStep step) {
        return clearDirty||dirty.stream().anyMatch(p->near(step.from(),p)||near(step.to(),p));
    }
    void refresh(MinecraftClient c) {
        if(clearDirty){columns.clear();valid.clear();edges.clear();}
        else if(!dirty.isEmpty()) {
            columns.keySet().removeIf(k->dirty.stream().anyMatch(p->Math.abs(k.x-p.getX())<=2&&Math.abs(k.z-p.getZ())<=2&&Math.abs(k.band-p.getY())<=5));
            valid.keySet().removeIf(q->dirty.stream().anyMatch(p->near(q,p)));
            edges.keySet().removeIf(e->dirty.stream().anyMatch(p->near(e.from,p)||near(e.to,p)));
        }
        dirty.clear();clearDirty=false;
        // Boat/shulker movement and chunk load/unload invalidate cached admission.
        int entities=1;
        for(var shape:c.world.getEntityCollisions(c.player,new Box(center,center).expand(28,14,28)))
            for(var box:shape.getBoundingBoxes())entities=31*entities+Objects.hash(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ);
        int chunks=1;
        int cx=BlockPos.ofFloored(center).getX()>>4,cz=BlockPos.ofFloored(center).getZ()>>4;
        for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++)chunks=31*chunks+(WorldActions.loadedChunk(c,x,z)?1:0);
        if(entities!=entitiesHash||chunks!=chunksHash||edges.size()>24000){columns.clear();valid.clear();edges.clear();revision++;}
        entitiesHash=entities;chunksHash=chunks;
    }
    List<Vec3d> column(MinecraftClient c,double x,double z,int band) {
        var key=new Column(x,z,band);var found=columns.get(key);
        if(found!=null){hits++;return found;}
        misses++;var feet=new Vec3d(x,band,z);
        // A clear full-cube floor rules out reachable lower surfaces in this
        // column: the floor itself would block every descent to them.
        var result=NavigationTerrain.fullFloor(c,feet,feet)&&NavigationTerrain.empty(c,feet,true)
            ?List.of(feet):NavigationTerrain.travelSurfaces(c,x,z,band-3,band+1.25,true);
        columns.put(key,result);return result;
    }
    boolean valid(MinecraftClient c,Vec3d p) {
        var found=valid.get(p);if(found!=null){hits++;return found;}
        misses++;boolean result=Navigation.valid(c,p,flying,true);valid.put(p,result);return result;
    }
    NavigationStep step(MinecraftClient c,Vec3d from,Vec3d to) {
        var key=new Edge(from,to);
        if(edges.containsKey(key)){hits++;return edges.get(key);}
        misses++;var result=NavigationStep.create(c,from,to,flying);edges.put(key,result);return result;
    }
    boolean edge(MinecraftClient c,Vec3d from,Vec3d to) {return step(c,from,to)!=null;}
}
