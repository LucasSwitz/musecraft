package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

/** Main-thread primitives. Plans and job lifetimes belong to the JS controller. */
final class WorldActions {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    static Map<String,Object> entity(Entity e) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id",e.getId()); out.put("uuid",e.getUuidAsString());
        out.put("type",Registries.ENTITY_TYPE.getId(e.getType()).toString());
        out.put("name",e.getName().getString()); out.put("alive",e.isAlive());
        out.put("pos",List.of(e.getX(),e.getY(),e.getZ()));
        out.put("height",e.getHeight()); out.put("width",e.getWidth());
        if(e instanceof LivingEntity l) out.put("health",l.getHealth());
        if(e instanceof net.minecraft.entity.passive.PassiveEntity animal)out.put("baby",animal.isBaby());
        if(e instanceof net.minecraft.entity.ItemEntity item)out.put("item",ItemActions.stack(item.getStack()));
        out.put("hasPassengers",e.hasPassengers());
        if(e instanceof net.minecraft.entity.passive.AbstractHorseEntity horse)
            out.put("mount",Map.of("tame",horse.isTame(),"saddled",horse.isSaddled(),"baby",horse.isBaby(),
                "steerable",!(horse instanceof net.minecraft.entity.passive.LlamaEntity)));
        return out;
    }
    private static BlockPos pos(JsonObject p) {
        double x=p.get("x").getAsDouble(), y=p.get("y").getAsDouble(), z=p.get("z").getAsDouble();
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)) throw new IllegalArgumentException("finite_position_required");
        return BlockPos.ofFloored(x,y,z);
    }
    static void aim(MinecraftClient c, Vec3d target) {
        Vec3d d=target.subtract(c.player.getEyePos());
        c.player.setYaw((float)Math.toDegrees(Math.atan2(-d.x,d.z)));
        c.player.setPitch((float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
    }
    static net.minecraft.util.ActionResult placeAgainst(MinecraftClient c, BlockHitResult hit) {
        boolean wasSneaking=c.player.input.sneaking;
        try {
            c.player.input.sneaking=true;
            if(!wasSneaking)c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(c.player,net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
            return c.interactionManager.interactBlock(c.player,Hand.MAIN_HAND,hit);
        } finally {
            c.player.input.sneaking=wasSneaking;
            if(!wasSneaking)c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(c.player,net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
        }
    }
    static Map<String,String> properties(net.minecraft.block.BlockState state) {
        Map<String,String> result=new TreeMap<>();
        state.getEntries().forEach((k,v)->result.put(k.getName(),v.toString().toLowerCase(Locale.ROOT)));
        return result;
    }
    private static List<BlockHitResult> visibleFaces(MinecraftClient c, BlockPos target, Direction required) {
        return visibleFaces(c,target,required,c.player.getEyePos());
    }
    private static List<BlockHitResult> visibleFaces(MinecraftClient c,BlockPos target,Direction required,Vec3d eye) {
        return visibleFaces(c,target,required,eye,false);
    }
    static List<BlockHitResult> visibleFaces(MinecraftClient c,BlockPos target,Direction required,Vec3d eye,boolean first) {
        List<BlockHitResult> result=new ArrayList<>();
        var shape=c.world.getBlockState(target).getOutlineShape(c.world,target);
        for(var box:shape.getBoundingBoxes()) for(Direction side:Direction.values()) {
            if(required!=null && side!=required)continue;
            for(double u:new double[]{.5,.2,.8})for(double v:new double[]{.5,.2,.8}) {
                double x=(box.minX+box.maxX)/2, y=(box.minY+box.maxY)/2,z=(box.minZ+box.maxZ)/2;
                switch(side) {
                    case UP,DOWN -> {x=box.minX+u*(box.maxX-box.minX);z=box.minZ+v*(box.maxZ-box.minZ);y=side==Direction.UP?box.maxY-.001:box.minY+.001;}
                    case EAST,WEST -> {y=box.minY+u*(box.maxY-box.minY);z=box.minZ+v*(box.maxZ-box.minZ);x=side==Direction.EAST?box.maxX-.001:box.minX+.001;}
                    case NORTH,SOUTH -> {x=box.minX+u*(box.maxX-box.minX);y=box.minY+v*(box.maxY-box.minY);z=side==Direction.SOUTH?box.maxZ-.001:box.minZ+.001;}
                }
                Vec3d point=new Vec3d(target.getX()+x,target.getY()+y,target.getZ()+z);
                if(eye.distanceTo(point)>c.player.getBlockInteractionRange()-.05)continue;
                var hit=c.world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,c.player));
                if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(target) && (required==null||hit.getSide()==required)){result.add(hit);if(first)return result;}
            }
        }
        return result;
    }
    static BlockHitResult visibleFace(MinecraftClient c,BlockPos target,Direction required) {
        var faces=visibleFaces(c,target,required);
        if(faces.isEmpty())throw new IllegalStateException("no_reachable_visible_face");
        return faces.getFirst();
    }
    private static boolean matches(net.minecraft.block.BlockState state,JsonObject wanted) {
        if(state==null)return false;
        var actual=properties(state);
        for(var entry:wanted.entrySet())if(!Set.of("open","powered","lit","occupied","triggered").contains(entry.getKey())&&!entry.getValue().getAsString().equals(actual.get(entry.getKey())))return false;
        return true;
    }
    record Placement(BlockHitResult hit,float yaw,float pitch) {}
    private static Direction.Axis doorwayAxis(MinecraftClient c,BlockPos target) {
        boolean xWall=false,zWall=false;
        for(Direction side:Direction.Type.HORIZONTAL) {
            BlockPos neighbor=target.offset(side);
            if(c.world.getBlockState(neighbor).isSideSolidFullSquare(c.world,neighbor,side.getOpposite())) {
                if(side.getAxis()==Direction.Axis.X)xWall=true;else zWall=true;
            }
        }
        return xWall==zWall?null:xWall?Direction.Axis.Z:Direction.Axis.X;
    }
    static void orientDoor(MinecraftClient c,net.minecraft.item.ItemStack held,BlockHitResult hit) {
        if(!(held.getItem() instanceof BlockItem item)||!(item.getBlock() instanceof net.minecraft.block.DoorBlock))return;
        var context=item.getPlacementContext(new ItemPlacementContext(c.player,Hand.MAIN_HAND,held,hit));
        if(context==null)return;
        BlockPos target=context.getBlockPos();var axis=doorwayAxis(c,target);
        if(axis==null)return;
        c.player.setYaw(axis==Direction.Axis.Z?(c.player.getZ()<target.getZ()+.5?0:180)
            :(c.player.getX()<target.getX()+.5?-90:90));
    }
    static net.minecraft.item.ItemStack buildingStack(JsonObject p) {
        var block=Registries.BLOCK.get(net.minecraft.util.Identifier.of(p.get("block").getAsString()));
        if(block==net.minecraft.block.Blocks.AIR||!(block.asItem() instanceof BlockItem))throw new IllegalArgumentException("unsupported_build_block");
        return new net.minecraft.item.ItemStack(block.asItem());
    }
    static boolean placementState(MinecraftClient c,JsonObject p,BlockHitResult hit,Vec3d eye,float yaw,boolean planning) {
        var stack=planning?buildingStack(p):c.player.getMainHandStack();
        if(!(stack.getItem() instanceof BlockItem item))return false;
        Vec3d delta=hit.getPos().subtract(eye);
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        var context=item.getPlacementContext(new ConstructionContext(c,stack,hit,yaw,pitch));
        if(context==null||!context.getBlockPos().equals(pos(p)))return false;
        var state=planning?item.getBlock().getPlacementState(context):((ai.musecraft.mixin.BlockItemInvoker)item).musecraftPlacementState(context);
        if(state==null||!state.canPlaceAt(c.world,pos(p)))return false;
        var wanted=p.has("state")?p.getAsJsonObject("state"):new JsonObject();
        var passage=item.getBlock() instanceof net.minecraft.block.DoorBlock&&!wanted.has("facing")?doorwayAxis(c,pos(p)):null;
        return (passage==null||state.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING).getAxis()==passage)
            &&Registries.BLOCK.getId(state.getBlock()).toString().equals(p.get("block").getAsString())&&matches(state,wanted);
    }
    static boolean acceptsHit(MinecraftClient c,JsonObject p,BlockHitResult hit,Vec3d eye) {
        for(float yaw:new float[]{0,90,180,270})if(placementState(c,p,hit,eye,yaw,true))return true;
        return false;
    }
    static Placement placement(MinecraftClient c,JsonObject p,Vec3d eye,boolean planning) {
        BlockPos target=pos(p);
        var hits=new ArrayList<BlockHitResult>();
        // Vanilla can replace the clicked vegetation directly. Raycasting only
        // at the ground underneath incorrectly treats its outline as a wall.
        if(!c.world.isAir(target)&&c.world.getBlockState(target).isReplaceable())
            hits.addAll(visibleFaces(c,target,null,eye,false));
        for(Direction side:Direction.values()) {
            BlockPos support=target.offset(side.getOpposite());
            if(!c.world.getBlockState(support).isReplaceable())hits.addAll(visibleFaces(c,support,side,eye,false));
        }
        for(var hit:hits) {
            Vec3d delta=hit.getPos().subtract(eye);
            float baseYaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
            float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
            for(float yaw:new float[]{baseYaw,0,90,180,270})
                if(placementState(c,p,hit,eye,yaw,planning))return new Placement(hit,yaw,pitch);
        }
        return null;
    }
    static boolean loaded(MinecraftClient c,BlockPos pos) {
        return loadedChunk(c,pos.getX()>>4,pos.getZ()>>4);
    }
    static boolean loadedChunk(MinecraftClient c,int x,int z) {
        // ClientWorld.isChunkLoaded always returns true in 1.21.1; ask the
        // client cache without allowing its empty fallback chunk instead.
        return c.world.getChunkManager().getChunk(x,z,net.minecraft.world.chunk.ChunkStatus.FULL,false)!=null;
    }
    static boolean actionVisible(MinecraftClient c,JsonObject p,BlockPos target,Vec3d feet,boolean placing,boolean fly,double range) {
        if(p.has("entityId")) {
            var entity=c.world.getEntityById(p.get("entityId").getAsInt());
            if(entity==null||!entity.getUuidAsString().equals(p.get("uuid").getAsString()))return false;
            if(p.has("pickup")&&p.get("pickup").getAsBoolean())return NavigationTerrain.body(feet).expand(1,.5,1).intersects(entity.getBoundingBox());
            var eye=feet.add(0,c.player.getStandingEyeHeight(),0);var point=entity.getBoundingBox().getCenter();
            return eye.distanceTo(point)<=Math.min(range,c.player.getEntityInteractionRange()-.1)
                &&c.world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,c.player)).getType()==HitResult.Type.MISS;
        }
        if(p.has("useItem")&&p.get("useItem").getAsBoolean())
            return ItemUseGeometry.accepts(c,p,target,feet);
        if(!placing&&!fly&&new Box(target).intersects(new Box(feet.x-.299,feet.y-.03,feet.z-.299,feet.x+.299,feet.y,feet.z+.299))
            &&!(p.has("mining")&&p.get("mining").getAsBoolean()&&MiningAccess.removable(c,target,feet)))return false;
        if(placing&&p.has("jumping")&&p.get("jumping").getAsBoolean()) {
            var raised=feet.add(0,1.1,0);
            if(fly||!NavigationTerrain.sweep(c,feet,raised,false))return false;
            feet=raised;
        }
        if(placing&&NavigationTerrain.body(feet).intersects(new Box(target)))return false;
        Vec3d eye=feet.add(0,c.player.getStandingEyeHeight(),0);
        if(placing)return placement(c,p,eye,true)!=null;
        if(c.world.isAir(target))return feet.distanceTo(Vec3d.ofCenter(target))<=range && c.world.raycast(new RaycastContext(eye,Vec3d.ofCenter(target),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,c.player)).getType()==HitResult.Type.MISS;
        return !visibleFaces(c,target,null,eye,true).isEmpty();
    }
    static JsonElement positions(MinecraftClient c,JsonObject p,boolean placing) {
        BlockPos target=pos(p);if(!WorldActions.loaded(c,target))throw new IllegalStateException("chunk_not_loaded");boolean fly=p.has("flying")&&p.get("flying").getAsBoolean();
        double range=p.has("range")?p.get("range").getAsDouble():c.player.getBlockInteractionRange()-.2;
        if(!Double.isFinite(range)||range<.5||range>8)throw new IllegalArgumentException("invalid_action_range");
        Vec3d current=c.player.getPos();
        if(Navigation.valid(c,current,fly,false)&&actionVisible(c,p,target,current,placing,fly,range))return JSON.toJsonTree(List.of(List.of(current.x,current.y,current.z)));
        List<Vec3d> candidates=new ArrayList<>();
        int radius=(int)Math.ceil(range);
        for(double x=target.getX()-radius+.5;x<=target.getX()+radius+.5;x+=.5)
            for(double z=target.getZ()-radius+.5;z<=target.getZ()+radius+.5;z+=.5) {
                if(fly)for(int y=target.getY()-radius;y<=target.getY()+radius;y++) {
                    Vec3d feet=new Vec3d(x,y,z);if(Navigation.valid(c,feet,true,false))candidates.add(feet);
                }
                else candidates.addAll(p.has("entityId")?NavigationTerrain.travelSurfaces(c,x,z,target.getY()-radius,target.getY()+radius,false):NavigationTerrain.surfaces(c,x,z,target.getY()-radius,target.getY()+radius,false));
            }
        candidates.sort(Comparator.comparingDouble(v->v.distanceTo(current)));
        List<List<Double>> result=new ArrayList<>();
        for(Vec3d feet:candidates) {
            if(!actionVisible(c,p,target,feet,placing,fly,range))continue;
            // Navigation validates the bounded candidate list. Running A* for
            // every visible candidate here can freeze the client main thread
            // when an entire ledge is unreachable.
            result.add(List.of(feet.x,feet.y,feet.z));
            if(feet.distanceTo(current)<.05||result.size()>=4)break;
        }
        return JSON.toJsonTree(result);
    }
    static JsonElement viewBounds(MinecraftClient c,JsonObject p) {
        var min=p.getAsJsonArray("min");var max=p.getAsJsonArray("max");int visible=0;
        var bounds=new Box(min.get(0).getAsDouble(),min.get(1).getAsDouble(),min.get(2).getAsDouble(),max.get(0).getAsDouble(),max.get(1).getAsDouble(),max.get(2).getAsDouble());
        Vec3d eye=p.has("eye")?new Vec3d(p.getAsJsonArray("eye").get(0).getAsDouble(),p.getAsJsonArray("eye").get(1).getAsDouble(),p.getAsJsonArray("eye").get(2).getAsDouble()):c.player.getEyePos();
        for(var item:p.getAsJsonArray("points")) {
            var a=item.getAsJsonArray();var point=new Vec3d(a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());
            var hit=c.world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,c.player));
            if(hit.getType()==HitResult.Type.MISS || bounds.expand(.01).contains(hit.getPos()))visible++;
        }
        boolean valid=Navigation.valid(c,eye.subtract(0,c.player.getStandingEyeHeight(),0),p.has("flying")&&p.get("flying").getAsBoolean(),false);
        return JSON.toJsonTree(Map.of("visible",visible,"valid",valid));
    }
    static JsonElement dispatch(MinecraftClient c,String method,JsonObject p) {
        if(method.equals("selectMaterial")) {
            int to=p.get("slot").getAsInt();
            if(to<0||to>8)throw new IllegalArgumentException("invalid_hotbar_slot");
            String name=p.get("name").getAsString(); int from=-1;
            for(int i=0;i<36;i++) {
                var stack=c.player.getInventory().getStack(i);
                if(p.has("unmodified")&&p.get("unmodified").getAsBoolean()&&!CraftingActions.eligible(stack))continue;
                if(!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(name)) {from=i;if(i==to)break;}
            }
            if(from<0) throw new IllegalStateException("missing_material:"+name);
            if(from!=to)c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,from<9?36+from:from,to,net.minecraft.screen.slot.SlotActionType.SWAP,c.player);
            c.player.getInventory().selectedSlot=to;
            return JSON.toJsonTree(Map.of("from",from,"slot",to,"count",c.player.getInventory().getStack(to).getCount()));
        }
        if(method.equals("selectWeapon")) {
            int best=c.player.getInventory().selectedSlot;double bestScore=-1;
            for(int i=0;i<36;i++) {
                var stack=c.player.getInventory().getStack(i);
                double[] damage={1,0,1},speed={4,0,1};
                stack.applyAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND,(attribute,modifier)->{
                    double[] values=attribute.equals(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE)?damage:
                        attribute.equals(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_SPEED)?speed:null;
                    if(values==null)return;
                    switch(modifier.operation()) {
                        case ADD_VALUE -> values[0]+=modifier.value();
                        case ADD_MULTIPLIED_BASE -> values[1]+=modifier.value();
                        case ADD_MULTIPLIED_TOTAL -> values[2]*=1+modifier.value();
                    }
                });
                double score=damage[0]*(1+damage[1])*damage[2]*Math.min(2,speed[0]*(1+speed[1])*speed[2]);
                if(score>bestScore){best=i;bestScore=score;}
            }
            int slot=best<9?best:8;
            if(slot!=best)c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,best,slot,net.minecraft.screen.slot.SlotActionType.SWAP,c.player);
            c.player.getInventory().selectedSlot=slot;
            return JSON.toJsonTree(Map.of("slot",slot,"item",net.minecraft.registry.Registries.ITEM.getId(c.player.getMainHandStack().getItem()).toString()));
        }
        if(method.equals("selectTool")) {
            var block=c.world.getBlockState(pos(p)); int best=c.player.getInventory().selectedSlot; double score=-1;
            for(int i=0;i<36;i++) {
                var stack=c.player.getInventory().getStack(i);
                double value=stack.getMiningSpeedMultiplier(block)+(stack.isSuitableFor(block)?100:0);
                if(value>score) {best=i;score=value;}
            }
            if(p.has("item"))best=HarvestDrops.tool(c,block,p.get("item").getAsString());
            if(best<0)throw new IllegalStateException("missing_harvest_tool_for_item");
            boolean useHands=!p.has("item")&&p.has("use_hands")&&p.get("use_hands").getAsBoolean();
            if(block.isToolRequired() && !c.player.getInventory().getStack(best).isSuitableFor(block) && !useHands)throw new IllegalStateException("missing_harvest_tool");
            int to=best<9?best:8;
            if(best!=to)c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,best,to,net.minecraft.screen.slot.SlotActionType.SWAP,c.player);
            c.player.getInventory().selectedSlot=to;
            return JSON.toJsonTree(Map.of("slot",to));
        }
        if(method.equals("attackEntity") || method.equals("mountEntity") || (method.equals("interactTarget") && p.has("entityId"))) {
            Entity e=c.world.getEntityById(p.get("entityId").getAsInt());
            if(e==null || !e.isAlive())throw new IllegalStateException("target_gone");
            if(!e.getUuidAsString().equals(p.get("uuid").getAsString()))throw new IllegalStateException("entity_identity_changed");
            Vec3d point=e.getBoundingBox().getCenter();
            if(c.player.getEyePos().distanceTo(point)>c.player.getEntityInteractionRange())throw new IllegalStateException("entity_out_of_reach");
            var hit=c.world.raycast(new RaycastContext(c.player.getEyePos(),point,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,c.player));
            if(hit.getType()!=HitResult.Type.MISS)throw new IllegalStateException("entity_occluded");
            aim(c,point);
            if(method.equals("attackEntity")) {
                if(c.player.getAttackCooldownProgress(0)<.95)throw new IllegalStateException("attack_cooldown");
                c.interactionManager.attackEntity(c.player,e); c.player.swingHand(Hand.MAIN_HAND);
                return JSON.toJsonTree(Map.of("submitted",true));
            }
            if(method.equals("mountEntity")) {
                return JSON.toJsonTree(MountActions.interact(c,e,!p.has("prepare")||p.get("prepare").getAsBoolean()));
            }
            var result=c.interactionManager.interactEntity(c.player,e,Hand.MAIN_HAND);
            if(result.isAccepted())c.player.swingHand(Hand.MAIN_HAND);
            return JSON.toJsonTree(Map.of("accepted",result.isAccepted()));
        }
        BlockPos target=pos(p);
        if(!WorldActions.loaded(c,target))throw new IllegalStateException("chunk_not_loaded");
        if(method.equals("digAt")) {
            if(p.has("expected") && !p.get("expected").getAsString().equals(Registries.BLOCK.getId(c.world.getBlockState(target).getBlock()).toString()))throw new IllegalStateException("target_changed");
            var hit=visibleFace(c,target,null); aim(c,hit.getPos()); return JSON.toJsonTree(BlockMining.begin(c,hit));
        }
        if(method.equals("placeAt")) {
            var stack=c.player.getMainHandStack();
            if(!(stack.getItem() instanceof BlockItem item))throw new IllegalStateException("selected_item_not_block");
            if(!c.world.getBlockState(target).isReplaceable())throw new IllegalStateException("target_occupied");
            Placement placement=placement(c,p,c.player.getEyePos(),false);
            if(placement==null) {
                var planned=placement(c,p,c.player.getEyePos(),true);
                if(planned!=null) {
                    var context=item.getPlacementContext(new ConstructionContext(c,stack,planned.hit(),planned.yaw(),planned.pitch()));
                    var state=context==null?null:item.getBlock().getPlacementState(context);
                    if(state!=null)for(var box:state.getCollisionShape(c.world,target,net.minecraft.block.ShapeContext.of(c.player)).getBoundingBoxes())
                        if(box.offset(target).intersects(c.player.getBoundingBox()))throw new IllegalStateException("placement_blocked_by_player");
                }
                throw new IllegalStateException("no_reachable_support_face_or_requested_state");
            }
            BlockHitResult hit=placement.hit();
            String expected=p.get("block").getAsString();
            float chosenYaw=placement.yaw(),chosenPitch=placement.pitch();
            c.player.setYaw(chosenYaw);c.player.setPitch(chosenPitch);
            // Server placement derives orientation from its last player rotation.
            c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(chosenYaw,chosenPitch,c.player.isOnGround()));
            long before=MusecraftClient.revision;
            // Sneak-use places against doors and containers instead of activating them.
            var result=placeAgainst(c,hit);
            c.player.swingHand(Hand.MAIN_HAND);
            return JSON.toJsonTree(Map.of("accepted",result.isAccepted(),"target",List.of(target.getX(),target.getY(),target.getZ()),
                "expected",expected,"afterRevision",before));
        }
        if(p.has("action") && !p.get("action").isJsonNull()) {
            String action=p.get("action").getAsString();
            if(!action.equals("open")&&!action.equals("close"))throw new IllegalArgumentException("action_must_be_open_or_close");
            var state=c.world.getBlockState(target);
            if(!state.contains(net.minecraft.state.property.Properties.OPEN))throw new IllegalStateException("target_has_no_open_state");
            if(state.get(net.minecraft.state.property.Properties.OPEN)==action.equals("open"))return JSON.toJsonTree(Map.of("accepted",true,"alreadySatisfied",true));
        }
        var hit=visibleFace(c,target,null); aim(c,hit.getPos());
        var result=c.interactionManager.interactBlock(c.player,Hand.MAIN_HAND,hit);
        if(result.isAccepted())c.player.swingHand(Hand.MAIN_HAND);
        return JSON.toJsonTree(Map.of("accepted",result.isAccepted(),"afterRevision",MusecraftClient.revision));
    }
    static JsonElement grid(MinecraftClient c,JsonObject p) {
        int radius=Math.max(2,Math.min(12,p.get("radius").getAsInt()));
        boolean flying=p.has("flying")&&p.get("flying").getAsBoolean();
        BlockPos center=c.player.getBlockPos(); List<List<Double>> stand=new ArrayList<>(),doors=new ArrayList<>();
        for(int x=center.getX()-radius;x<=center.getX()+radius;x++)for(int z=center.getZ()-radius;z<=center.getZ()+radius;z++) {
            List<Vec3d> spots=new ArrayList<>();
            if(flying)for(int y=center.getY()-4;y<=center.getY()+4;y++) {
                var feet=new Vec3d(x+.5,y,z+.5);if(Navigation.valid(c,feet,true,true))spots.add(feet);
            }
            else spots=NavigationTerrain.surfaces(c,x+.5,z+.5,center.getY()-4,center.getY()+4,true);
            for(Vec3d feet:spots) {
                var cell=List.of(feet.x-.5,feet.y,feet.z-.5);stand.add(cell);
                if(Navigation.door(c,BlockPos.ofFloored(feet)))doors.add(cell);
            }
        }
        return JSON.toJsonTree(Map.of("cells",stand,"passable",stand,"doors",doors,"center",List.of(center.getX(),center.getY(),center.getZ())));
    }
    static boolean safe(MinecraftClient c,BlockPos p) {
        return c.world.getFluidState(p).isEmpty()&&safeSupport(c,p);
    }
    static boolean safeSupport(MinecraftClient c,BlockPos p) {
        var state=c.world.getBlockState(p);
        if(state.isIn(net.minecraft.registry.tag.BlockTags.FIRE)
            ||c.world.getFluidState(p).isIn(net.minecraft.registry.tag.FluidTags.LAVA)
            ||state.isOf(net.minecraft.block.Blocks.LAVA_CAULDRON)
            ||state.isOf(net.minecraft.block.Blocks.CACTUS)
            ||state.isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)
            ||state.isOf(net.minecraft.block.Blocks.POWDER_SNOW))return false;
        if(state.isOf(net.minecraft.block.Blocks.SWEET_BERRY_BUSH)
            &&state.get(net.minecraft.state.property.Properties.AGE_3)>0)return false;
        return !(state.getBlock() instanceof net.minecraft.block.CampfireBlock
            &&state.get(net.minecraft.state.property.Properties.LIT));
    }
}
