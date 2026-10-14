package ai.musecraft;

import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.*;

/** One desired location or action predicate; never an agent-authored waypoint plan. */
final class NavigationGoal {
    final Vec3d requested;
    final String kind;
    final boolean flying;
    private final Double distance;
    private final JsonObject action;
    private final List<Vec3d> probes=new ArrayList<>();
    private int probe;
    private Vec3d anchor;
    private String failure;
    private boolean ready;
    private boolean projected;
    private boolean baritoneDestination;
    private final Map<Vec3d,Boolean> acceptance=new HashMap<>();
    private Map<String,Object> terminalBlocker;
    NavigationGoal(Vec3d target,boolean fly,JsonObject params) {
        requested=target;flying=fly;
        kind=params.has("goal")?params.get("goal").getAsString():"exact";
        if(!Set.of("near","exact","action","placement","entity").contains(kind))throw new IllegalArgumentException("invalid_navigation_goal");
        distance=kind.equals("near")&&params.has("distance")?params.get("distance").getAsDouble():null;
        if(distance!=null&&(!Double.isFinite(distance)||distance<.25||distance>8))throw new IllegalArgumentException("invalid_navigation_distance");
        action=params.has("action")?params.getAsJsonObject("action").deepCopy():new JsonObject();
        action.addProperty("x",target.x);action.addProperty("y",target.y);action.addProperty("z",target.z);
        if(!kind.equals("near")){ready=true;anchor=target;return;}
        probes.add(target);
        probes.add(new Vec3d(Math.floor(target.x)+.5,target.y,Math.floor(target.z)+.5));
        double region=distance==null?2.5:distance,extent=Math.ceil(region);
        for(double x=Math.floor(target.x)-extent+.5;x<=Math.floor(target.x)+extent;x+=.5)
            for(double z=Math.floor(target.z)-extent+.5;z<=Math.floor(target.z)+extent;z+=.5)
                if(Math.hypot(x-target.x,z-target.z)<=region)probes.add(new Vec3d(x,target.y,z));
        probes.subList(2,probes.size()).sort(Comparator.comparingDouble(p->p.squaredDistanceTo(target)));
    }
    boolean ready(){return ready;}
    boolean excavatingAction(){return kind.equals("action") &&
        (action.has("mining")&&action.get("mining").getAsBoolean() || action.has("pickup")&&action.get("pickup").getAsBoolean());}
    boolean volumeGoal(){return kind.equals("near")||excavatingAction();}
    String failure(){return failure;}
    Vec3d anchor(){return anchor==null?requested:anchor;}
    void invalidateGeometry(){acceptance.clear();terminalBlocker=null;}
    void resolveStep(MinecraftClient c) {
        if(ready)return;
        if(!WorldActions.loaded(c,BlockPos.ofFloored(requested))){failure="terrain_unloaded";ready=true;return;}
        if(probe>=probes.size()) {
            if(distance==null&&!projected&&!flying&&c.world.getBlockState(BlockPos.ofFloored(requested)).isAir()) {projected=true;probe=0;}
            else {failure="no_acceptable_destination";ready=true;return;}
        }
        Vec3d p=probes.get(probe++);
        double vertical=distance==null?1.25:distance;
        var surfaces=flying?(Navigation.valid(c,p,true,true)?List.of(p):List.<Vec3d>of()):NavigationTerrain.travelSurfaces(c,p.x,p.z,requested.y-(projected?16:vertical),requested.y+vertical,true);
        if(distance!=null)surfaces=surfaces.stream().filter(v->v.distanceTo(requested)<=distance).toList();
        if(projected)surfaces=surfaces.stream().filter(v->c.world.raycast(new net.minecraft.world.RaycastContext(
            new Vec3d(v.x,requested.y+.1,v.z),v.add(0,.1,0),net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,c.player)).getType()==net.minecraft.util.hit.HitResult.Type.MISS).toList();
        anchor=surfaces.stream().min(Comparator.comparingDouble(v->Math.abs(v.y-requested.y))).orElse(null);
        if(anchor!=null)ready=true;
    }
    boolean prepareBaritone(MinecraftClient c) {
        baritoneDestination=true;
        if(!WorldActions.loaded(c,BlockPos.ofFloored(requested))){failure="terrain_unloaded";ready=true;return true;}
        if(excavatingAction()){ready=true;anchor=requested;return true;}
        if(distance!=null){ready=true;anchor=requested;return true;}
        // Preserve floor/air projection for approximate places, but an absent
        // current stance cannot veto a destination Baritone may excavate.
        resolveStep(c);
        if(ready&&"no_acceptable_destination".equals(failure)){failure=null;anchor=requested;}
        return ready;
    }
    Vec3d regionCenter(){return excavatingAction()?requested.add(action.has("mining")?.5:0,-.5,action.has("mining")?.5:0):projected?new Vec3d(requested.x,anchor().y,requested.z):requested;}
    double verticalRadius(){return excavatingAction()?1.5:distance==null?1.25:distance;}
    boolean inRegion(Vec3d p,boolean inset) {
        if(excavatingAction())return Math.hypot(p.x-regionCenter().x,p.z-regionCenter().z)<=2.5
            &&Math.abs(p.y-regionCenter().y)<=verticalRadius();
        return distance!=null?p.distanceTo(requested)<=distance-(inset?.1:0):
            Math.hypot(p.x-requested.x,p.z-requested.z)<=2.5&&Math.abs(p.y-(projected?anchor().y:requested.y))<=1.25;
    }
    boolean visible(MinecraftClient c,Vec3d p) {
        return c.world.raycast(new net.minecraft.world.RaycastContext(
            p.add(0,c.player.getStandingEyeHeight(),0),anchor().add(0,c.player.getStandingEyeHeight(),0),
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,c.player))
            .getType()==net.minecraft.util.hit.HitResult.Type.MISS;
    }
    boolean targetGone(MinecraftClient c) {
        if(!action.has("entityId"))return false;
        var entity=c.world.getEntityById(action.get("entityId").getAsInt());
        return entity==null||!entity.isAlive()||!entity.getUuidAsString().equals(action.get("uuid").getAsString());
    }
    boolean tracksEntity(){return kind.equals("entity");}
    Vec3d entityPosition(MinecraftClient c){return c.world.getEntityById(action.get("entityId").getAsInt()).getPos();}
    int entityId(){return action.get("entityId").getAsInt();}
    String entityUuid(){return action.get("uuid").getAsString();}
    double radius(){return tracksEntity()?action.get("range").getAsDouble():kind.equals("near")?distance==null?2.5:distance:kind.equals("exact")?0:5;}
    double heuristic(Vec3d p){return Math.max(0,p.distanceTo(distance==null?anchor():requested)-radius());}
    double penalty(Vec3d p){return kind.equals("near")&&distance==null?2*p.distanceTo(anchor()):0;}
    Map<String,Object> blocker(MinecraftClient c,Vec3d here,String cause) {
        if(terminalBlocker!=null)return terminalBlocker;
        var out=new LinkedHashMap<String,Object>();
        out.put("cause",cause);out.put("position",List.of(here.x,here.y,here.z));
        Vec3d destination=tracksEntity()&&!targetGone(c)?entityPosition(c):requested;
        out.put("target",List.of(destination.x,destination.y,destination.z));out.put("goal",kind);
        double low=destination.y,high=destination.y;
        if(kind.equals("near")) {
            low=distance==null?(projected?anchor().y:destination.y)-1.25:destination.y-distance;
            high=distance==null?(projected?anchor().y:destination.y)+1.25:destination.y+distance;
        } else if(tracksEntity()){low-=radius();high+=radius();}
        else if(kind.equals("action")||kind.equals("placement")) {
            if(action.has("pickup")&&action.get("pickup").getAsBoolean()) {
                var entity=c.world.getEntityById(action.get("entityId").getAsInt());
                if(entity!=null){low=entity.getBoundingBox().minY-2.3;high=entity.getBoundingBox().maxY+.5;}
            }else{low-=c.player.getStandingEyeHeight()+c.player.getBlockInteractionRange();high+=c.player.getBlockInteractionRange();}
        }
        String reason=cause,explanation="Walking did not find a route. Choose another approach or inspect the local terrain.";
        boolean searched=!Set.of("no_acceptable_destination","terrain_unloaded","destination_alignment_unavailable").contains(cause);
        if(cause.equals("destination_alignment_unavailable"))explanation="Baritone reached the candidate destination cells, but none had a usable final position inside the requested arrival region. Inspect the target coordinates or choose a wider radius.";
        else if(!searched)explanation="The destination has no usable arrival position under the requested geometry. This is not a failed path search. Inspect the target height, footing and clearance, or request a broader arrival radius.";
        if(!flying&&searched&&!cause.equals("search_incomplete")&&here.y<low-.05) {
            reason="requires_elevation";
            explanation="The target requires a higher action position and walking found no route. Inspect clearance and a landing, then choose mc.pillar or another approach. No access blocks were placed.";
            out.put("minimumRise",Math.max(1,(int)Math.ceil(low-here.y)));out.put("suggestedTool","mc.pillar");
        }else if(!flying&&searched&&!cause.equals("search_incomplete")&&here.y>high+.05) {
            reason="descent_unreachable";
            explanation="The target is below the accessible action height and walking found no descent. Choose a lower approach or safely remove owned support blocks. No access blocks were placed.";
        }else if(cause.equals("search_incomplete"))explanation="The bounded search did not establish a route; this does not prove the target is unreachable or needs a pillar. Choose a different approach.";
        if(kind.equals("near")&&distance!=null&&distance<=1&&!cause.equals("terrain_unloaded")) {
            var standings=new ArrayList<Vec3d>();
            standings.addAll(NavigationTerrain.travelSurfaces(c,requested.x,requested.z,requested.y-4,requested.y+4,true));
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)
                standings.addAll(NavigationTerrain.travelSurfaces(c,Math.floor(requested.x)+x+.5,Math.floor(requested.z)+z+.5,requested.y-4,requested.y+4,true));
            var nearest=standings.stream().min(Comparator.comparingDouble(requested::squaredDistanceTo)).orElse(null);
            if(nearest!=null&&nearest.distanceTo(requested)>distance+.01) {
                reason="destination_not_standable";
                explanation="The requested arrival radius contains no observed standing surface. The nearest observed footing is outside that radius; use its position or a wider radius. This does not prove that the surrounding area is unreachable.";
                out.put("nearestStandingPosition",List.of(nearest.x,nearest.y,nearest.z));out.put("arrivalRadius",distance);
                out.remove("minimumRise");out.remove("suggestedTool");
            }
        }
        var terrain=AccessDiagnostics.nearby(c,here);
        var result=new LinkedHashMap<String,Object>();
        result.put("reason",reason);result.put("explanation",explanation);
        result.putAll(out);result.put("heightDifference",destination.y-here.y);
        result.put("localTerrain",terrain);
        result.put("observedAt",java.time.Instant.now().toString());
        terminalBlocker=result;
        return result;
    }
    boolean accepts(MinecraftClient c,Vec3d p) {
        if(!ready||failure!=null)return false;
        // Goal instances are replaced on relevant world revisions. Entity goals
        // remain live; static block visibility need not be raycast for every visit.
        if(action.has("entityId"))return acceptsUncached(c,p);
        return acceptance.computeIfAbsent(p,key->acceptsUncached(c,key));
    }
    private boolean acceptsUncached(MinecraftClient c,Vec3d p) {return acceptsUncached(c,p,false);}
    private boolean acceptsUncached(MinecraftClient c,Vec3d p,boolean arrival) {
        if(!ready||failure!=null)return false;
        // Route endpoints sit inside the public radius; settled physics can
        // stop millimeters short without turning arrival into a retry loop.
        if(tracksEntity()) {
            if(targetGone(c)||!Navigation.valid(c,p,flying,true))return false;
            var entity=c.world.getEntityById(entityId());
            return p.distanceTo(entity.getPos())<=radius()-(arrival?0:.1)&&c.world.raycast(new net.minecraft.world.RaycastContext(
                p.add(0,c.player.getStandingEyeHeight(),0),entity.getBoundingBox().getCenter(),
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,net.minecraft.world.RaycastContext.FluidHandling.NONE,c.player))
                .getType()==net.minecraft.util.hit.HitResult.Type.MISS;
        }
        if(kind.equals("near")&&!inRegion(p,!arrival))return false;
        if(kind.equals("exact")&&p.distanceTo(requested)>=.01)return false;
        if((kind.equals("action")||kind.equals("placement"))&&p.distanceTo(requested)>6)return false;
        if(!Navigation.valid(c,p,flying,true))return false;
        if(kind.equals("exact"))return p.distanceTo(requested)<.01;
        if(kind.equals("near")) {
            // A coordinate radius describes spatial proximity. Visibility to an
            // abstract point inside a block is not a valid arrival requirement.
            // Entity and interaction goals own their live visibility checks.
            if(baritoneDestination)return true;
            if(distance!=null)return visible(c,p);
            // Keep the endpoint in the anchor's local free space. In particular,
            // proximity through a wall or from a pit rim is not arrival.
            return p.distanceTo(anchor)<.01||(flying?NavigationTerrain.sweep(c,anchor,p,true):NavigationTerrain.travelLine(c,anchor,p,true));
        }
        return WorldActions.actionVisible(c,action,BlockPos.ofFloored(requested),p,kind.equals("placement"),flying,
            action.has("range")?action.get("range").getAsDouble():c.player.getBlockInteractionRange()-.2);
    }
    boolean satisfied(MinecraftClient c,Vec3d p,Vec3d resolved,double tolerance) {
        if(tracksEntity())return acceptsUncached(c,p,true)&&Navigation.valid(c,p,flying,false);
        if(resolved==null||p.distanceTo(resolved)>tolerance||!Navigation.valid(c,p,flying,false))return false;
        return kind.equals("exact")?p.distanceTo(requested)<=tolerance:acceptsUncached(c,p,true);
    }
    boolean reached(MinecraftClient c,Vec3d p,double tolerance) {
        return Navigation.valid(c,p,flying,false)&&(kind.equals("exact")
            ?p.distanceTo(requested)<=tolerance:acceptsUncached(c,p,true));
    }
}
