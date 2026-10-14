package ai.musecraft;

import ai.musecraft.mixin.MiningProgressAccessor;
import baritone.api.IBaritone;
import baritone.api.utils.input.Input;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

/** Physical progress survives replans; a fixed obstruction can be mined without aim churn. */
final class NavigationExecution {
    private static final int STALL_TICKS=100;
    private Vec3d anchor;
    private double lowY,highY;
    private final Map<BlockPos,Float> breakingBest=new HashMap<>();
    private final Map<BlockPos,Integer> breakResets=new HashMap<>();
    private final Map<BlockPos,Block> watched=new HashMap<>();
    private final Set<BlockPos> attempted=new HashSet<>();
    private BlockPos from,to,clearing;
    private BlockPos previousBreaking;
    private float previousProgress;
    private String miningId,recoveryFailure;
    private int ticks,progressTick,recoveries,cleared;
    private Map<String,Object> lastStep=Map.of();
    private List<Map<String,Object>> obstruction=List.of();

    void reset(MinecraftClient c) {
        breakingBest.clear();breakResets.clear();watched.clear();attempted.clear();
        from=null;to=null;clearing=null;miningId=null;recoveryFailure=null;
        ticks=0;progressTick=0;recoveries=0;cleared=0;lastStep=Map.of();obstruction=List.of();
        previousBreaking=null;previousProgress=0;
        anchor=c.player.getPos();lowY=highY=anchor.y;
    }
    private void progressed(){progressTick=ticks;}
    private void moved(MinecraftClient c) {
        var p=c.player.getPos();
        // Real horizontal travel (including backtracking) is progress. In one
        // column, only new vertical extent counts: swimming up/down works, but
        // repeating the same buoyancy oscillation cannot renew the action.
        if(Math.hypot(p.x-anchor.x,p.z-anchor.z)>=.25) {
            anchor=p;lowY=highY=p.y;progressed();
        }else if(p.y>highY+.25||p.y<lowY-.25) {
            lowY=Math.min(lowY,p.y);highY=Math.max(highY,p.y);progressed();
        }
    }
    void observe(MinecraftClient c,IBaritone engine) {
        ticks++;moved(c);
        var current=engine.getPathingBehavior().getCurrent();
        if(current!=null) {
            int index=current.getPosition();var path=current.getPath();
            if(index>=0&&index<path.movements().size()) {
                var step=path.movements().get(index);from=step.getSrc().toImmutable();to=step.getDest().toImmutable();
                lastStep=Map.of("pathIndex",index,"pathLength",path.length(),"from",xyz(from),"to",xyz(to),"stepCostTicks",step.getCost());
                for(var pos:clearanceCells())if(!BlockMining.clear(c.world.getBlockState(pos)))watched.putIfAbsent(pos,c.world.getBlockState(pos).getBlock());
            }
        }
        var mining=(MiningProgressAccessor)c.interactionManager;
        var pos=mining.musecraftBreakingPos();float value=mining.musecraftBreakingProgress();
        if(miningId==null&&previousBreaking!=null&&watched.containsKey(previousBreaking)&&previousProgress>0
            &&(!previousBreaking.equals(pos)||value<previousProgress-.000001f)
            &&!BlockMining.clear(c.world.getBlockState(previousBreaking)))
            breakResets.merge(previousBreaking,1,Integer::sum);
        previousBreaking=pos.toImmutable();previousProgress=value;
        if(watched.containsKey(pos)&&value>breakingBest.getOrDefault(pos,0f)+.000001f) {
            breakingBest.put(pos.toImmutable(),value);progressed();
        }
        if(watched.size()>64) {
            var retain=new HashSet<>(clearanceCells());if(clearing!=null)retain.add(clearing);
            watched.keySet().retainAll(retain);breakingBest.keySet().retainAll(retain);breakResets.keySet().retainAll(retain);
        }
    }
    void changed(BlockPos pos,BlockState state) {
        var original=watched.get(pos);
        if(original!=null&&original!=state.getBlock()&&BlockMining.clear(state)) {
            progressed();watched.remove(pos);breakingBest.remove(pos);breakResets.remove(pos);attempted.remove(pos);
        }
    }
    boolean stalled(){return from!=null&&ticks-progressTick>=STALL_TICKS;}
    boolean needsRecovery(){return stalled()||breakResets.values().stream().anyMatch(n->n>=3);}
    private List<BlockPos> clearanceCells() {
        if(from==null||to==null)return List.of();
        var cells=new ArrayList<BlockPos>();
        if(to.getY()>from.getY())cells.add(from.up(2));
        cells.add(to);cells.add(to.up());return cells;
    }
    BlockPos clearance(MinecraftClient c,Predicate<BlockPos> allowed) {
        var facts=new ArrayList<Map<String,Object>>();BlockPos chosen=null;
        for(var pos:clearanceCells()) {
            var state=c.world.getBlockState(pos);
            if(BlockMining.clear(state))continue;
            boolean permitted=allowed.test(pos)&&MiningAccess.removable(c,pos,c.player.getPos());
            boolean visible=!WorldActions.visibleFaces(c,pos,null,c.player.getEyePos(),true).isEmpty();
            facts.add(Map.of("position",xyz(pos),"block",Registries.BLOCK.getId(state.getBlock()).toString(),"clearancePermitted",permitted,"reachable",visible));
            if(chosen==null&&permitted&&visible&&!attempted.contains(pos))chosen=pos;
        }
        obstruction=List.copyOf(facts);
        return chosen;
    }
    void begin(MinecraftClient c,BlockPos pos,boolean useHands) {
        clearing=pos.toImmutable();attempted.add(clearing);recoveries++;
        var args=new JsonObject();args.addProperty("x",pos.getX());args.addProperty("y",pos.getY());args.addProperty("z",pos.getZ());args.addProperty("use_hands",useHands);
        WorldActions.dispatch(c,"selectTool",args);
        var hit=WorldActions.visibleFace(c,pos,null);WorldActions.aim(c,hit.getPos());
        miningId=(String)BlockMining.begin(c,hit).get("id");
        // The retry starts a fresh break, so its progress has a fresh high-water
        // mark. A failed block is attempted only once until server removal.
        breakingBest.remove(pos);breakResets.remove(pos);previousProgress=0;progressed();
    }
    String poll(MinecraftClient c) {
        var result=BlockMining.poll(c,miningId);
        if(result.get("status").equals("completed")){cleared++;miningId=null;clearing=null;progressed();return "completed";}
        if(result.get("status").equals("blocked")){recoveryFailure=(String)result.get("reason");return "blocked";}
        if(stalled()){recoveryFailure="mining_progress_stalled";return "blocked";}
        return "running";
    }
    void stop(MinecraftClient c) {
        if(miningId!=null)BlockMining.stop(c,"navigation_released");
        miningId=null;
    }
    void failed(String reason){recoveryFailure=reason;}
    Map<String,Object> blocker(MinecraftClient c) {
        var out=new LinkedHashMap<String,Object>();
        out.put("reason","movement_stalled");out.put("attemptedStep",lastStep);out.put("obstructions",obstruction);
        List<Map<String,Object>> entities=to==null?List.of():c.world.getOtherEntities(c.player,NavigationTerrain.body(Vec3d.ofBottomCenter(to)).expand(.05),e->e.isCollidable()).stream()
            .limit(4).map(WorldActions::entity).toList();
        out.put("entities",entities);
        out.put("noProgressTicks",ticks-progressTick);out.put("recoveryAttempts",recoveries);out.put("clearedObstructions",cleared);
        if(recoveryFailure!=null)out.put("recoveryFailure",recoveryFailure);
        String what=obstruction.isEmpty()?"No reachable, permitted obstruction was identified on that step.":
            "Step obstruction: "+obstruction.getFirst().get("block")+" at "+obstruction.getFirst().get("position")+".";
        out.put("explanation","Movement stopped making physical or mining progress from "+(from==null?"the current position":xyz(from))+" toward "+(to==null?"the destination":xyz(to))+" on the current step. "+what+
            (entities.isEmpty()?"":" The next standing space also intersects "+entities.getFirst().get("type")+" (entity "+entities.getFirst().get("id")+").")+
            (recoveries>0?" Stable clearance was attempted "+recoveries+" time(s); "+cleared+" block(s) were confirmed removed.":"")+
            (recoveryFailure==null?"":" Clearance stopped: "+recoveryFailure+".")+" Choose another approach, change the reported obstruction, or build a step, then retry.");
        return out;
    }
    Map<String,Object> snapshot(MinecraftClient c,IBaritone engine) {
        var out=new LinkedHashMap<String,Object>(lastStep);
        out.put("noProgressTicks",ticks-progressTick);out.put("recoveryAttempts",recoveries);out.put("clearedObstructions",cleared);
        if(clearing!=null)out.put("clearing",xyz(clearing));
        if(engine==null||c.player==null||c.world==null)return out;
        out.put("feet",xyz(engine.getPlayerContext().playerFeet()));out.put("touchingWater",c.player.isTouchingWater());out.put("onGround",c.player.isOnGround());
        out.put("input",Arrays.stream(Input.values()).filter(engine.getInputOverrideHandler()::isInputForcedDown).map(Enum::name).toList());
        var mining=(MiningProgressAccessor)c.interactionManager;
        out.put("breaking",Map.of("position",xyz(mining.musecraftBreakingPos()),"progress",mining.musecraftBreakingProgress()));
        if(c.crosshairTarget instanceof BlockHitResult hit) {
            out.put("lookingAt",xyz(hit.getBlockPos()));out.put("lookingAtBlock",Registries.BLOCK.getId(c.world.getBlockState(hit.getBlockPos()).getBlock()).toString());
        }
        return out;
    }
    private static List<Integer> xyz(BlockPos pos){return List.of(pos.getX(),pos.getY(),pos.getZ());}
}
