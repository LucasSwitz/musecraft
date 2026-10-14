package ai.musecraft;

import baritone.api.*;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.schematic.ISchematic;
import com.google.gson.*;
import java.util.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/** One native owner: upstream BuilderProcess does placement, excavation, inventory and routing. */
public final class BaritoneBuild {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static IBaritone engine;
    private static BuildPlan plan;
    private static BuildJournal journal;
    private static Object world,player;
    private static String id,status="idle",reason,phase;
    private static long leaseUntil,startedAt,lastMutation,ackRevision,resumedRevision,cleanupAckRevision;
    private static int ticks,settled,placed,cleared,cleanupRevision;
    private static final List<Runnable> restore=new ArrayList<>();
    private static final Map<BlockPos,BlockState> observed=new HashMap<>();
    private static final Map<BlockPos,Long> pending=new HashMap<>();
    private static final Map<BlockPos,Integer> transitions=new HashMap<>();
    private static final Set<BlockPos> accessCleared=new LinkedHashSet<>();
    private static Map<String,Object> result=Map.of();
    private static BlockPos refused;
    private static boolean routeCleanup;

    private static <T> void setting(Settings.Setting<T> setting,T value) {
        T before=setting.value;restore.add(()->setting.value=before);setting.value=value;
    }
    static JsonElement start(MinecraftClient c,JsonObject request) {
        MusecraftClient.release(c);
        plan=new BuildPlan(c,request);BuildFacingGoal.reset();
        if(request.has("creativeSupply")&&request.get("creativeSupply").getAsBoolean()) {
            if(!c.interactionManager.hasCreativeInventory())throw new IllegalStateException("creative_mode_required");
            for(var name:plan.missing(c).keySet()) {
                int slot=c.player.getInventory().getEmptySlot();
                if(slot<0)throw new IllegalStateException("inventory_full");
                var item=Registries.ITEM.get(net.minecraft.util.Identifier.of(name));
                var stack=new net.minecraft.item.ItemStack(item,item.getMaxCount());
                c.player.getInventory().setStack(slot,stack);
                c.interactionManager.clickCreativeStack(stack,slot<9?36+slot:slot);
            }
        }
        String server=c.getCurrentServerEntry()==null?"singleplayer":c.getCurrentServerEntry().address;
        journal=new BuildJournal(server+":"+c.world.getRegistryKey().getValue(),plan);
        engine=BaritoneNavigation.engine();BaritoneProtection.start(c);
        id=UUID.randomUUID().toString();world=c.world;player=c.player;status="running";reason=null;phase="building";
        startedAt=lastMutation=System.currentTimeMillis();leaseUntil=startedAt+5000;
        ticks=settled=placed=cleared=cleanupRevision=0;ackRevision=resumedRevision=0;refused=null;observed.clear();pending.clear();transitions.clear();accessCleared.clear();cleanupAckRevision=0;
        plan.cells.keySet().forEach(p->observed.put(p,c.world.getBlockState(p)));
        journal.originals.keySet().forEach(p->observed.put(p,c.world.getBlockState(p)));
        var s=BaritoneAPI.getSettings();
        // Construction needs repeatable face hits, including exposed slab edges.
        // Upstream's randomized/retained aim can alternate between neighboring
        // blocks and reset survival mining progress on every tick.
        setting(s.randomLooking,0d);setting(s.randomLooking113,0d);
        setting(s.remainWithExistingLookDirection,false);setting(s.smoothLook,false);
        routeCleanup=request.has("routeCleanup")&&request.get("routeCleanup").getAsBoolean();
        setting(s.allowBreak,true);setting(s.allowPlace,!routeCleanup);setting(s.allowInventory,true);setting(s.autoTool,true);
        setting(s.maxFallHeightNoWater,3);
        setting(s.allowBreakAnyway,List.of());setting(s.blocksToAvoidBreaking,List.of());
        setting(s.buildInLayers,true);setting(s.layerOrder,routeCleanup);setting(s.layerHeight,1);setting(s.startAtLayer,0);
        setting(s.breakCorrectBlockPenaltyMultiplier,1000d);
        setting(s.breakFromAbove,routeCleanup);setting(s.goalBreakFromAbove,routeCleanup);
        setting(s.skipFailedLayers,false);setting(s.buildIgnoreExisting,false);setting(s.buildIgnoreDirection,false);
        setting(s.buildIgnoreBlocks,List.of());setting(s.buildSkipBlocks,List.of());setting(s.okIfAir,List.of());
        setting(s.buildSubstitutes,Map.of());setting(s.buildValidSubstitutes,Map.of());
        setting(s.buildIgnoreProperties,new ArrayList<>(BuildPlan.TRANSIENT));
        setting(s.buildRepeat,new net.minecraft.util.math.Vec3i(0,0,0));setting(s.buildOnlySelection,false);
        setting(s.schematicOrientationX,false);setting(s.schematicOrientationY,false);setting(s.schematicOrientationZ,false);
        setting(s.buildSchematicRotation,net.minecraft.util.BlockRotation.NONE);setting(s.buildSchematicMirror,net.minecraft.util.BlockMirror.NONE);
        setting(s.mapArtMode,false);setting(s.okIfWater,false);
        // Temporary route blocks must be replaceable from ordinary survival inventory.
        setting(s.acceptableThrowawayItems,List.of(net.minecraft.item.Items.DIRT,net.minecraft.item.Items.COBBLESTONE,net.minecraft.item.Items.NETHERRACK));
        plan.refreshPairs(c);
        if(routeCleanup&&!c.player.isOnGround()) {
            stop(c,"blocked","cleanup_requires_safe_footing");return snapshot();
        }
        engine.getBuilderProcess().build(plan.id,schematic(),plan.min);
        update(c);return snapshot();
    }
    private static ISchematic schematic() {
        var blueprint=plan;var supports=phase.equals("building")?Map.copyOf(plan.supports):Map.<BlockPos,JsonObject>of();
        var cleanup=phase.equals("cleanup")?Map.copyOf(journal.originals):Map.<BlockPos,JsonObject>of();
        return new ISchematic() {
            private JsonObject wanted(int x,int y,int z,BlockState current) {
                var p=blueprint.min.add(x,y,z);
                if(blueprint.blocked.containsKey(p))return null;
                var item=blueprint.cells.get(p);
                // A door top or bed head is created by placing its primary half.
                // Never give upstream a standalone placement goal for that half.
                if(item!=null&&blueprint.pendingSecondaries.contains(p)) {
                    if(current==null||current.isReplaceable())return null;
                    var air=new JsonObject();air.addProperty("block","minecraft:air");return air;
                }
                return item!=null?item:supports.containsKey(p)?supports.get(p):cleanup.get(p);
            }
            @Override public boolean inSchematic(int x,int y,int z,BlockState current){return wanted(x,y,z,current)!=null;}
            @Override public BlockState desiredState(int x,int y,int z,BlockState current,List<BlockState> available) {
                var item=wanted(x,y,z,current);if(item==null)return current;
                // Preserve unspecified and operational properties; only the design's constraints matter.
                if(current!=null&&BuildPlan.matches(current,item))return current;
                for(var candidate:available)if(BuildPlan.matches(candidate,item))return candidate;
                return BuildPlan.desired(item);
            }
            @Override public int widthX(){return blueprint.max.getX()-blueprint.min.getX()+1;}
            @Override public int heightY(){return blueprint.max.getY()-blueprint.min.getY()+1;}
            @Override public int lengthZ(){return blueprint.max.getZ()-blueprint.min.getZ()+1;}
        };
    }
    public static boolean allowPlacement(BlockPos pos,net.minecraft.item.ItemStack stack) {
        // Navigation right-clicks (for example a door behind foliage) can miss
        // and fall through to the held BlockItem. Reserve design materials for
        // blueprint cells; off-plan placements use only configured scaffolding.
        return !running()||plan.cells.containsKey(pos)||BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(stack.getItem());
    }
    public static boolean preserve(BlockPos pos,BlockState current) {
        var wanted=wanted(pos);
        return wanted!=null&&!current.isAir()&&BuildPlan.matches(current,wanted);
    }
    public static boolean unsafeSupportRemoval(BlockPos pos) {
        if(!running())return false;
        var c=MinecraftClient.getInstance();var player=c.player;
        if(player==null||c.world==null)return true;
        var box=player.getBoundingBox();
        if(pos.getY()+1<box.minY-.05||pos.getY()>=box.minY||pos.getX()+1<=box.minX||pos.getX()>=box.maxX||pos.getZ()+1<=box.minZ||pos.getZ()>=box.maxZ)return false;
        for(int depth=1;depth<=3;depth++) {
            var below=pos.down(depth);var shape=c.world.getBlockState(below).getCollisionShape(c.world,below);
            if(!shape.isEmpty()&&box.minY-(below.getY()+shape.getMax(net.minecraft.util.math.Direction.Axis.Y))<=3)return false;
        }
        return true;
    }
    public static long geometryRevision(){return ackRevision;}
    public static boolean matchesPlacement(BlockState actual,JsonObject wanted){return BuildPlan.matches(actual,wanted);}
    public static JsonObject wanted(BlockPos pos){return running()?plan.cells.get(pos):null;}
    public static boolean running(){return status.equals("running");}
    /** Upstream handles reachable mining; a cleanup never admits a new walk. */
    public static PathingCommand constrainCleanup(PathingCommand command) {
        if(running()&&routeCleanup&&command!=null&&command.goal!=null) {
            stop(MinecraftClient.getInstance(),"blocked","cleanup_requires_reposition");
            return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return command;
    }
    /** Called at the actual Minecraft mutation boundary, before Baritone changes the world. */
    public static boolean rejectMutation(BlockPos position,boolean breaking) {
        if(!running())return false;
        var c=MinecraftClient.getInstance();var pos=position.toImmutable();
        String error=null;
        if(c.player!=player||c.world!=world||c.player==null||c.player.isDead())error="player_died";
        else if(!plan.contains(pos))error="build_work_area_exceeded";
        else if(!WorldActions.loaded(c,pos))error="terrain_unloaded";
        else if(StructureProtection.reason(pos)!=null)error="protected_structure";
        else if(plan.blocked.containsKey(pos))error=plan.blocked.get(pos);
        else if(routeCleanup&&(!breaking||!plan.cells.containsKey(pos)))error="cleanup_requires_reposition";
        else if(routeCleanup&&breaking&&c.player.getBoundingBox().offset(0,-.05,0)
            .intersects(new net.minecraft.util.math.Box(pos)))error="cleanup_requires_safe_footing";
        else if(breaking&&unsafeSupportRemoval(pos))error="unsafe_support_removal";
        else if(c.world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory)error="container_contents_unknown";
        if(error!=null){refused=pos;stop(c,"blocked",error);return true;}
        var before=c.world.getBlockState(pos);
        try {
            if(!plan.cells.containsKey(pos)) {
                if(!breaking)journal.remember(pos,Blocks.AIR.getDefaultState());

            }
        }catch(RuntimeException e){refused=pos;stop(c,"blocked","build_journal_unavailable");return true;}
        observed.putIfAbsent(pos,before);pending.putIfAbsent(pos,System.currentTimeMillis());
        return false;
    }
    static void changed(BlockPos pos,BlockState state) {
        if(!running()||!observed.containsKey(pos))return;
        pending.remove(pos);
        var before=observed.put(pos.toImmutable(),state);
        if(!before.equals(state))ackRevision++;
        if(before.getBlock()==state.getBlock())return;
        if(!before.isAir()) {
            cleared++;
            if(state.isAir()&&!plan.cells.containsKey(pos)&&!journal.originals.containsKey(pos))accessCleared.add(pos.toImmutable());
        }
        if(!state.isAir())placed++;
        lastMutation=System.currentTimeMillis();
        if(transitions.merge(pos.toImmutable(),1,Integer::sum)>=12) {
            refused=pos;stop(MinecraftClient.getInstance(),"blocked","build_repeated_changes");
        }
    }
    static JsonElement poll(MinecraftClient c,JsonObject request) {
        if(id==null||!id.equals(request.get("id").getAsString()))throw new IllegalStateException("build_identity_changed");
        if(running())leaseUntil=System.currentTimeMillis()+5000;
        return snapshot();
    }
    static JsonElement snapshot(){return JSON.toJsonTree(result);}
    static void tick(MinecraftClient c) {
        if(!running())return;
        try {
            if(c.player==null||c.world!=world){stop(c,"cancelled","client_session_changed");return;}
            if(c.player!=player||c.player.isDead()||c.player.getHealth()<=0){stop(c,"blocked","player_died");return;}
            if(c.currentScreen!=null){stop(c,"blocked","screen_open");return;}
            long now=System.currentTimeMillis();
            if(now>=leaseUntil){stop(c,"cancelled","build_lease_expired");return;}
            plan.refreshPairs(c);
            if(++ticks%5!=0)return;
            // A server acknowledgement is required for attempted changes. An unaccepted click
            // must not prevent later placements forever, nor count as verified construction.
            pending.entrySet().removeIf(e->now-e.getValue()>5000&&c.world.getBlockState(e.getKey()).equals(observed.get(e.getKey())));
            boolean finished=true;
            for(var e:plan.cells.entrySet())if(!plan.blocked.containsKey(e.getKey())&&!BuildPlan.matches(c.world.getBlockState(e.getKey()),e.getValue())){finished=false;break;}
            if(finished&&phase.equals("building")&&pending.isEmpty()) {
                phase="cleanup";beginCleanup();settled=0;
            }
            if(phase.equals("cleanup"))for(var e:journal.originals.entrySet())if(!BuildPlan.matches(c.world.getBlockState(e.getKey()),e.getValue())){finished=false;break;}
            if(phase.equals("cleanup")&&!finished&&pending.isEmpty()
                &&(journal.originals.size()>cleanupRevision||ackRevision>cleanupAckRevision)
                &&(!engine.getBuilderProcess().isActive()||engine.getBuilderProcess().isPaused())) {
                beginCleanup();
                update(c);return;
            }
            if(finished&&pending.isEmpty()) {
                if(++settled>=3){stop(c,plan.blocked.isEmpty()?"completed":"blocked",plan.blocked.isEmpty()?"build_complete":plan.blocked.values().iterator().next());return;}
            }else settled=0;
            // Upstream pauses immediately when its cached snapshot cannot see a
            // just-placed paired block. Reconcile once per fresh server update;
            // unchanged missing-material/access failures still return to the caller.
            if(engine.getBuilderProcess().isPaused()&&ackRevision>resumedRevision) {
                resumedRevision=ackRevision;engine.getBuilderProcess().resume();update(c);return;
            }
            var goal=engine.getPathingBehavior().getGoal();
            if(now-lastMutation>10000&&pending.isEmpty()&&c.player.isOnGround()
                &&!engine.getPathingBehavior().isPathing()&&engine.getPathingBehavior().getInProgress().isEmpty()
                &&goal!=null&&goal.isInGoal(c.player.getBlockPos())) {
                stop(c,"blocked","placement_unavailable");return;
            }
            if(engine.getBuilderProcess().isPaused()&&pending.isEmpty()&&now-lastMutation>1500) {
                String why=phase.equals("cleanup")?"cleanup_pending":!plan.missing(c).isEmpty()?"missing_materials":"builder_paused";
                stop(c,"blocked",why);return;
            }
            if(!engine.getBuilderProcess().isActive()&&!finished&&pending.isEmpty()){stop(c,"blocked",phase.equals("cleanup")?"cleanup_pending":"builder_stopped");return;}
            update(c);
        }catch(RuntimeException error){System.err.println("Musecraft build failed: "+error);stop(c,"blocked","build_error");}
    }
    private static void beginCleanup() {
        // A repeated remainder can become reachable after other world changes.
        // The caller admits another pass only after a new server acknowledgement
        // or a newly recorded support, so an unchanged pause cannot retry forever.
        cleanupAckRevision=ackRevision;
        // Expose all supports together: a lower support can hide an upper one.
        // Baritone selects reachable removals, while the route constraint keeps
        // finished cells intact and the mining guard prevents unsafe falls.
        // Include new route supports on a subsequent upstream cleanup pass.
        BaritoneAPI.getSettings().buildInLayers.value=false;
        BaritoneAPI.getSettings().breakFromAbove.value=true;
        BaritoneAPI.getSettings().goalBreakFromAbove.value=false;
        cleanupRevision=journal.originals.size();
        engine.getBuilderProcess().build(plan.id+" cleanup",schematic(),plan.min);
    }
    static void stop(MinecraftClient c,String terminal,String cause) {
        if(!running())return;
        if(terminal.equals("completed"))journal.clear();
        status=terminal;reason=cause;
        engine.getBuilderProcess().onLostControl();engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().clearAllKeys();
        for(int i=restore.size()-1;i>=0;i--)restore.get(i).run();restore.clear();
        update(c);
    }
    private static String explanation(String code) {
        return switch(code) {
            case "player_died" -> "The player died during construction. Completed blocks are retained; repair this plan after respawning.";
            case "missing_materials" -> "Inventory is missing the listed construction materials. Supply them, then repair this plan.";
            case "target_occupied" -> "This cell contains a different solid block. Name it in replace or clear it explicitly.";
            case "unbreakable_obstruction" -> "An unbreakable block occupies this requested cell.";
            case "container_contents_unknown" -> "A container cannot be removed automatically. Empty and remove it explicitly before repair.";
            case "cleanup_pending" -> "The requested structure has been attempted, but temporary supports remain to be removed. Inspect cleanup targets before repair.";
            case "cleanup_requires_reposition" -> "Reachable route blocks were attempted without walking. Remaining blocks need another safe work position; leave them in place if reaching them would undo the exit.";
            case "cleanup_requires_safe_footing" -> "Route cleanup preserves the player's support. Stand on solid footing outside the route before retrying.";
            case "unsafe_support_removal" -> "Removing this support would drop the player more than three blocks. Move to a safe work position, then repair the plan.";
            case "build_work_area_exceeded" -> "Baritone attempted a world change outside this build's work area; it was stopped before that change.";
            case "build_repeated_changes" -> "The same cell changed twelve times during this attempt. Construction stopped to avoid a placement/removal loop.";
            case "placement_unavailable" -> "Baritone reached a work location but could not place a remaining block there. No placement or mining is pending. Inspect the reported cells and access before repairing.";
            case "builder_paused" -> "Baritone paused with unfinished cells. No missing construction material was identified. Inspect the remaining cells and access; this is not proof they are unreachable.";
            case "builder_stopped" -> "Baritone stopped, but world verification found unfinished cells. The build is incomplete.";
            default -> "Construction stopped: "+code+". Completed work is retained; repair the saved plan when the cause is resolved.";
        };
    }
    private static void update(MinecraftClient c) {
        boolean readable=c.world==world&&c.player!=null;
        var remaining=new ArrayList<BlockPos>();int verified=0;
        for(var e:plan.requested.entrySet()) {
            var state=readable?c.world.getBlockState(e.getKey()):observed.get(e.getKey());
            if(state!=null&&BuildPlan.matches(state,e.getValue()))verified++;else remaining.add(e.getKey());
        }
        remaining.sort(Comparator.comparingInt(BlockPos::getY).thenComparingDouble(p->readable?p.getSquaredDistance(c.player.getPos()):0));
        var cleanup=new ArrayList<List<Integer>>();
        for(var e:journal.originals.entrySet())if(!readable||!BuildPlan.matches(c.world.getBlockState(e.getKey()),e.getValue()))cleanup.add(BuildPlan.point(e.getKey()));
        String reported=status;
        if(status.equals("blocked")&&verified>0)reported="partial";
        var blockers=new ArrayList<Map<String,Object>>();
        for(var pos:remaining) {
            String why=plan.blocked.getOrDefault(pos,reason==null?"builder_running":reason);
            var row=new LinkedHashMap<String,Object>();row.put("target",BuildPlan.point(pos));row.put("reason",why);row.put("explanation",explanation(why));
            if(readable)row.put("actual",Registries.BLOCK.getId(c.world.getBlockState(pos).getBlock()).toString());
            if(refused!=null)row.put("obstruction",BuildPlan.point(refused));
            if(blockers.size()<8)blockers.add(row);
        }
        var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("planId",plan.id);out.put("backend","baritone");out.put("backendVersion","1.11.3");
        out.put("status",reported);out.put("reason",reason);out.put("phase",phase);out.put("complete",status.equals("completed"));
        out.put("total",plan.requested.size());out.put("verified",verified);out.put("remaining",remaining.size());
        out.put("accessCleared",Map.of("count",accessCleared.size(),"targets",accessCleared.stream().limit(8).map(BuildPlan::point).toList()));
        out.put("changed",Map.of("placed",placed,"cleared",cleared));out.put("missing",readable?plan.missing(c):Map.of());
        out.put("blockers",running()||status.equals("cancelled")?List.of():blockers);out.put("omittedBlockers",running()||status.equals("cancelled")?0:Math.max(0,remaining.size()-8));
        out.put("cleanup",Map.of("remaining",cleanup.size(),"targets",cleanup.stream().limit(8).toList()));
        out.put("workArea",Map.of("min",BuildPlan.point(plan.min),"max",BuildPlan.point(plan.max)));
        if(running())out.put("navigationGoal",String.valueOf(engine.getPathingBehavior().getGoal()).substring(0,Math.min(400,String.valueOf(engine.getPathingBehavior().getGoal()).length())));
        if(running()) {
            out.put("execution",Map.of("builderActive",engine.getBuilderProcess().isActive(),"builderPaused",engine.getBuilderProcess().isPaused(),
                "pathing",engine.getPathingBehavior().isPathing(),"calculating",engine.getPathingBehavior().getInProgress().isPresent(),
                "pendingMutations",pending.size(),"pendingTargets",pending.keySet().stream().limit(8).map(BuildPlan::point).toList(),
                "leftClick",engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_LEFT),
                "rightClick",engine.getInputOverrideHandler().isInputForcedDown(baritone.api.utils.input.Input.CLICK_RIGHT),
                "sinceMutationMs",System.currentTimeMillis()-lastMutation));
            var mining=(ai.musecraft.mixin.MiningProgressAccessor)c.interactionManager;
            out.put("breaking",Map.of("target",BuildPlan.point(mining.musecraftBreakingPos()),"progress",mining.musecraftBreakingProgress()));
            var nearby=new ArrayList<Map<String,Object>>();
            for(var p:remaining)if(p.isWithinDistance(c.player.getPos(),4)&&nearby.size()<6) {
                var desired=BuildPlan.desired(plan.cells.get(p));
                nearby.add(Map.of("pos",BuildPlan.point(p),"block",Registries.BLOCK.getId(desired.getBlock()).toString(),
                    "canSurvive",desired.canPlaceAt(c.world,p),"collisionFree",c.world.doesNotIntersectEntities(null,desired.getCollisionShape(c.world,p).offset(p.getX(),p.getY(),p.getZ()))));
            }
            out.put("placementChecks",nearby);
        }
        out.put("timingsMs",Map.of("total",System.currentTimeMillis()-startedAt));result=out;
    }
}
