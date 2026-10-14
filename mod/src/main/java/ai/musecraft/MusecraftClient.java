package ai.musecraft;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MusecraftClient implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Map<KeyBinding, Long> HELD = new HashMap<>();
    private static final Path HOME = Path.of(System.getProperty("musecraft.home", "/home/hatch/workspace/musecraft"));
    private static final String BOOT = UUID.randomUUID().toString();
    private static final ThreadPoolExecutor REQUESTS = new ThreadPoolExecutor(4, 4, 0,
        TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "musecraft-request"); thread.setDaemon(true); return thread;
        });
    private static Object observedWorld;
    private static long worldEpoch, tickCount;
    static long revision;
    public static long inventoryRevision;
    private static Map<String,Object> attacker;
    private static long damagedAt, damageSequence;
    private static final Map<BlockPos, Map<String, Object>> CONFIRMED = new LinkedHashMap<>();

    private static String session(MinecraftClient c) {
        if (observedWorld != c.world) {
            observedWorld = c.world;
            worldEpoch++;
            CONFIRMED.clear();
            BlockMining.reset(c);
            attacker=null;damagedAt=0;damageSequence=0;
            ChatInbox.clear();
            release(c);
            if(c.world!=null)ResourceProtection.start(c);else StructureProtection.load(c);
        }
        return BOOT + ":" + worldEpoch;
    }
    public static void serverBlockUpdated(BlockPos pos, BlockState state) {
        BlockContext.invalidate();
        session(MinecraftClient.getInstance());
        NavigationMap.changed(pos);
        BaritoneNavigation.changed(pos);
        RegionInspection.changed(pos);
        BaritoneBuild.changed(pos,state);RoutePlacement.changed(pos,state);
        CONFIRMED.put(pos.toImmutable(), Map.of("revision", ++revision,
            "name", Registries.BLOCK.getId(state.getBlock()).toString()));
        BlockMining.serverUpdated(pos,state,revision);
        if (CONFIRMED.size() > 512) CONFIRMED.remove(CONFIRMED.keySet().iterator().next());
    }
    public static void serverChunkUpdated(int x,int z) { NavigationMap.chunkChanged(x,z); }
    public static void serverDamage(net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket packet) {
        var c=MinecraftClient.getInstance();
        if(c.player==null||c.world==null||packet.entityId()!=c.player.getId())return;
        session(c);
        var source=packet.createDamageSource(c.world).getAttacker();
        attacker=source==null?null:WorldActions.entity(source);damagedAt=System.currentTimeMillis();
        damageSequence++;
    }
    public static void serverInventoryUpdated() {
        inventoryRevision++;
        var c=MinecraftClient.getInstance();
        if(c.player!=null) InventoryActions.status(c);
    }
    static boolean confirmed(BlockPos pos,long after) { var update=CONFIRMED.get(pos);return update!=null && ((Number)update.get("revision")).longValue()>after; }

    public void onInitializeClient() {
        Thread thread = new Thread(this::serve, "musecraft-client-bridge");
        thread.setDaemon(true);
        thread.start();
    }
    private void serve() {
        Path socket = HOME.resolve("client.sock");
        try {
            Files.createDirectories(HOME);
            // A client reconnect uses the same private endpoint after process exit.
            if (Files.exists(socket)) {
                try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    probe.connect(UnixDomainSocketAddress.of(socket));
                    throw new IllegalStateException("Another Musecraft client owns the socket");
                } catch (ConnectException stale) { Files.delete(socket); }
            }
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socket));
                Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
                while (true) {
                    SocketChannel channel = server.accept();
                    try { REQUESTS.execute(() -> serveConnection(channel)); }
                    catch (RejectedExecutionException busy) { channel.close(); }
                }
            }
        } catch (Exception e) { System.err.println("Musecraft bridge failed: " + e); }
    }
    private static void serveConnection(SocketChannel channel) {
        try (channel) {
            channel.configureBlocking(false);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(65536);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean complete = false;
            while (!complete && buffer.hasRemaining() && System.nanoTime() < deadline) {
                int read = channel.read(buffer);
                if (read < 0) break;
                for (int i = 0; i < buffer.position(); i++) if (buffer.get(i) == '\n') { complete = true; break; }
                if (!complete) Thread.sleep(5);
            }
            if (!complete) return;
            String input = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).trim();
            JsonObject response = new JsonObject();
            AtomicBoolean admitted = new AtomicBoolean(true);
            try {
                JsonObject request = JsonParser.parseString(input).getAsJsonObject();
                if ("subscribe".equals(request.get("method").getAsString())) {
                    if (!request.has("protocol") || request.get("protocol").getAsInt() != Release.PROTOCOL
                            || !request.has("version") || !Release.VERSION.equals(request.get("version").getAsString()))
                        throw new IllegalStateException("musecraft_version_mismatch");
                    ObservationStream.serve(channel);
                    return;
                }
                CompletableFuture<JsonElement> result = new CompletableFuture<>();
                MinecraftClient.getInstance().execute(() -> {
                    if (!admitted.compareAndSet(true, false)) return;
                    try {
                        var immediate=dispatch(request,result);
                        if(immediate!=null)result.complete(immediate);
                    }
                    catch (Exception e) { result.completeExceptionally(e); }
                });
                response.add("value", result.get(5, TimeUnit.SECONDS));
                response.add("compatibility", GSON.toJsonTree(compatibility()));
                response.addProperty("ok", true);
            } catch (Exception e) {
                admitted.set(false);
                response.addProperty("ok", false);
                response.addProperty("error", String.valueOf(e.getCause() == null ? e : e.getCause()));
            }
            java.nio.ByteBuffer out = StandardCharsets.UTF_8.encode(GSON.toJson(response) + "\n");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (out.hasRemaining() && System.nanoTime() < deadline) {
                if (channel.write(out) == 0) Thread.sleep(5);
            }
        } catch (Exception e) { System.err.println("Musecraft request: " + e); }
    }
    private static JsonElement dispatch(JsonObject request,CompletableFuture<JsonElement> response) throws Exception {
        MinecraftClient c = MinecraftClient.getInstance();
        String method = request.get("method").getAsString();
        JsonObject p = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        if (!method.equals("stop") && (!request.has("protocol") || request.get("protocol").getAsInt() != Release.PROTOCOL
                || !request.has("version") || !Release.VERSION.equals(request.get("version").getAsString())))
            throw new IllegalStateException("musecraft_version_mismatch: install mod and controller from the same release (mod=" + Release.VERSION + ")");
        String currentSession = session(c);
        Set<String> writes = Set.of("lookAt", "equip", "chat", "animate", "creativeItem", "moveToHotbar", "setFlying", "placeAt", "digAt", "attackEntity", "interactTarget", "closeScreen", "respawn", "selectMaterial", "selectTool", "setHudHidden", "moveStart", "movePoll", "selectItem", "useItemStart", "useItemPoll", "digPoll", "inventoryMove", "inventorySwap", "craftRecipe", "craftBatchStart", "craftBatchPoll", "mountEntity", "dismount", "wake", "selectWeapon", "jumpPlaceStart", "jumpPlacePoll", "combatStart", "combatQueue", "fleeAdd", "combatPoll", "moveStop", "buildStart", "buildPoll", "nativeTaskStart", "nativeTaskPoll", "protectArea");
        if (writes.contains(method)) {
            PlaySession.admit(request);
            if (!request.has("session") || !currentSession.equals(request.get("session").getAsString()))
                throw new IllegalStateException("session_changed");
            if(CraftingBatch.running()&&!Set.of("craftBatchPoll","chat").contains(method))throw new IllegalStateException("craft_batch_running");
            if (!method.equals("buildPoll") && c.player != null && c.player.isDead() && !Set.of("respawn","chat","closeScreen").contains(method)) throw new IllegalStateException("player_died");
            if (!method.equals("buildPoll") && c.currentScreen != null && !method.equals("closeScreen") && !method.equals("chat") && !method.equals("respawn") && !method.equals("wake") && !Set.of("inventoryMove","inventorySwap","craftRecipe","craftBatchStart","craftBatchPoll").contains(method)) throw new IllegalStateException("screen_open");
        }
        if (method.equals("playPolicy")) return GSON.toJsonTree(PlaySession.policy(c, p));
        if (method.equals("connectServer")) return GSON.toJsonTree(PlaySession.connect(c, p));
        if (method.equals("stop")) {
            release(c);
            CraftingBatch.whenStopped().thenRun(()->response.complete(GSON.toJsonTree(Map.of("stopped",true))));
            return null;
        }
        if (method.equals("screenshot")) {
            Path file = HOME.resolve("screen-" + UUID.randomUUID() + ".png");
            try (var image = ScreenshotRecorder.takeScreenshot(c.getFramebuffer())) { image.writeTo(file); }
            return GSON.toJsonTree(Map.of("path", file.toRealPath().toString()));
        }
        if (method.equals("observe")) return observe(c, p);
        if (method.equals("buildStatus")) return BaritoneBuild.snapshot();
        if (method.equals("buildPoll")) return BaritoneBuild.poll(c,p);
        if (c.player == null || c.world == null || c.interactionManager == null) throw new IllegalStateException("Client is not in a world");
        if(method.equals("harvestSources")) {
            HarvestScan.start(c,p).whenComplete((value,error)->{
                if(error!=null)response.completeExceptionally(error);
                else response.complete(GSON.toJsonTree(value));
            });
            return null;
        }
        if(method.equals("inspectRegion")) {
            RegionInspection.start(c,p).whenComplete((value,error)->{
                if(error!=null)response.completeExceptionally(error);
                else response.complete(GSON.toJsonTree(value));
            });
            return null;
        }
        if(method.equals("scanBlocks")) {
            VisibleBlockScan.start(c,p).whenComplete((value,error)->{
                if(error!=null)response.completeExceptionally(error);
                else response.complete(GSON.toJsonTree(value));
            });
            return null;
        }
        return switch (method) {
            case "wake" -> {
                c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(c.player,net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.STOP_SLEEPING));
                yield GSON.toJsonTree(Map.of("submitted",true));
            }
            case "dismount" -> {
                Navigation.dismount();
                yield GSON.toJsonTree(Map.of("submitted",true));
            }
            case "mountEntity", "selectWeapon" -> WorldActions.dispatch(c,method,p);
            case "jumpPlaceStart" -> JumpPlacement.start(c,p);
            case "jumpPlacePoll" -> JumpPlacement.poll(c,p);
            case "droppedItems" -> {
                int radius=BlockScan.bounded(p,"radius",16,32);
                var near=p.has("near")?p.getAsJsonArray("near"):null;
                var center=near==null?c.player.getPos():new Vec3d(near.get(0).getAsDouble(),near.get(1).getAsDouble(),near.get(2).getAsDouble());
                var volume=BlockVolume.around(center,radius);
                yield GSON.toJsonTree(c.world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,volume.box(),
                    e->e.isAlive()&&!e.getStack().isEmpty()&&volume.contains(e.getPos()))
                    .stream().map(e->{
                        var out=WorldActions.entity(e);
                        var stack=e.getStack();int capacity=0;
                        for(int slot=0;slot<36;slot++) {
                            var held=c.player.getInventory().getStack(slot);
                            if(held.isEmpty())capacity+=stack.getMaxCount();
                            else if(net.minecraft.item.ItemStack.areItemsAndComponentsEqual(stack,held))
                                capacity+=Math.max(0,held.getMaxCount()-held.getCount());
                        }
                        var offhand=c.player.getOffHandStack();
                        if(net.minecraft.item.ItemStack.areItemsAndComponentsEqual(stack,offhand))
                            capacity+=Math.max(0,offhand.getMaxCount()-offhand.getCount());
                        out.put("pickupCapacity",capacity);return out;
                    }).toList());
            }
            case "respawn" -> {
                if (c.world.getLevelProperties().isHardcore()) throw new IllegalStateException("hardcore_respawn_unsupported");
                if (!c.player.isDead()) yield GSON.toJsonTree(Map.of("submitted", false));
                release(c);
                c.player.requestRespawn();
                c.setScreen(null);
                yield GSON.toJsonTree(Map.of("submitted", true));
            }
            case "container" -> GSON.toJsonTree(ContainerState.snapshot(c));
            case "recipes" -> CraftingActions.recipes(c,p);
            case "craftingGraph" -> CraftingActions.graph(c,p);
            case "craftingInputs" -> CraftingActions.inputs(c);
            case "craftingSelection" -> CraftingActions.selection(c,p);
            case "cookingRecipe" -> CraftingActions.cooking(c,p);
            case "craftRecipe" -> CraftingActions.craft(c,p);
            case "craftBatchStart" -> CraftingBatch.start(c,p);
            case "craftBatchPoll" -> CraftingBatch.poll(c,p);
            case "craftBatchStatus" -> CraftingBatch.status();
            case "inventoryMove" -> InventoryActions.move(c,p);
            case "inventorySwap" -> InventoryActions.swap(c,p);
            case "selectItem" -> { InventoryActions.requireSettled(c); yield ItemActions.equip(c,p); }
            case "useItemStart" -> ItemActions.start(c,p);
            case "useItemPoll" -> ItemActions.poll(c,p);
            case "digPoll" -> GSON.toJsonTree(BlockMining.poll(c,p.get("id").getAsString()));
            case "animations" -> GSON.toJsonTree(PlayerAnimations.list(c));
            case "animate" -> GSON.toJsonTree(PlayerAnimations.play(c, p.get("name").getAsString(), number(p, "value")));
            case "avatar" -> GSON.toJsonTree(FiguraAvatars.operate(c, p));
            case "setHudHidden" -> { c.options.hudHidden = p.get("hidden").getAsBoolean(); yield JsonNull.INSTANCE; }
            case "cameraInfo" -> GSON.toJsonTree(Map.of("hudHidden",c.options.hudHidden,"fov",c.options.getFov().getValue()));
            case "nativeTaskStart" -> BaritoneTasks.start(c,p);
            case "nativeTaskPoll" -> BaritoneTasks.poll(c,p);
            case "routeCleanupPlan" -> RoutePlacement.cleanupPlan(c,p);
            case "importSchematic" -> SchematicImport.read(p);
            case "buildStart" -> BaritoneBuild.start(c,p);
            case "placementPositions" -> WorldActions.positions(c,p,true);
            case "placementSuggestion" -> GSON.toJsonTree(PlacementSuggestion.find(c,p));
            case "actionPositions" -> WorldActions.positions(c,p,false);
            case "miningStances" -> GSON.toJsonTree(MiningAccess.stances(c,p));
            case "miningAccess" -> GSON.toJsonTree(MiningAccess.next(c,p));
            case "pickupAccess" -> GSON.toJsonTree(PickupAccess.next(c,p));
            case "blocksAt" -> { JsonArray result=new JsonArray(); for(var entry:p.getAsJsonArray("positions")) { var a=entry.getAsJsonArray(); result.add(block(c,new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()))); } yield result; }
            case "viewBounds" -> WorldActions.viewBounds(c,p);
            case "moveStart" -> Navigation.start(c,p);
            case "movePoll" -> Navigation.poll(c,p);
            case "fleeCheck" -> FleeNavigation.check(c,p);
            case "fleeAdd" -> FleeNavigation.add(c,p);
            case "moveStop" -> { Navigation.stop(c,"cancelled","movement_released"); yield GSON.toJsonTree(Map.of("stopped",true)); }
            case "combatStart" -> Combat.start(c,p);
            case "combatPoll" -> Combat.poll(c,p);
            case "combatQueue" -> Combat.enqueue(c,p);
            case "navigationGrid" -> WorldActions.grid(c, p);
            case "materialFor" -> {
                var id = net.minecraft.util.Identifier.tryParse(p.get("block").getAsString());
                if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("unknown_block");
                var item = Registries.BLOCK.get(id).asItem();
                if (item == net.minecraft.item.Items.AIR || !item.isEnabled(c.world.getEnabledFeatures()))
                    throw new IllegalArgumentException("block_has_no_available_item");
                yield GSON.toJsonTree(Map.of("name", Registries.ITEM.getId(item).toString()));
            }
            case "placeAt", "digAt", "attackEntity", "interactTarget", "selectMaterial", "selectTool" -> WorldActions.dispatch(c, method, p);
            case "closeScreen" -> { InventoryActions.requireSettled(c); c.player.closeHandledScreen(); c.setScreen(null); yield JsonNull.INSTANCE; }
            case "creativeItem" -> {
                if (!c.interactionManager.hasCreativeInventory()) throw new IllegalStateException("creative_mode_required");
                int slot = p.get("slot").getAsInt(), count = p.get("count").getAsInt();
                if (slot < 0 || slot > 8) throw new IllegalArgumentException("hotbar_slot_must_be_0_to_8");
                var id = net.minecraft.util.Identifier.tryParse(p.get("name").getAsString());
                if (id == null || !Registries.ITEM.containsId(id)) throw new IllegalArgumentException("unknown_item");
                var item = Registries.ITEM.get(id);
                if (item == net.minecraft.item.Items.AIR || !item.isEnabled(c.world.getEnabledFeatures()))
                    throw new IllegalArgumentException("item_unavailable");
                var stack = new net.minecraft.item.ItemStack(item);
                if (count < 1 || count > stack.getMaxCount()) throw new IllegalArgumentException("invalid_item_count");
                stack.setCount(count);
                c.player.getInventory().setStack(slot, stack);
                c.interactionManager.clickCreativeStack(stack, 36 + slot);
                yield GSON.toJsonTree(Map.of("submitted", true));
            }
            case "moveToHotbar" -> {
                int from = p.get("inventorySlot").getAsInt(), to = p.get("hotbarSlot").getAsInt();
                if (from < 0 || from > 35 || to < 0 || to > 8) throw new IllegalArgumentException("invalid_inventory_slot");
                if (c.player.currentScreenHandler != c.player.playerScreenHandler) throw new IllegalStateException("close_container_first");
                if (c.player.getInventory().getStack(from).isEmpty()) throw new IllegalStateException("source_slot_empty");
                if (from != to) c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,
                    from < 9 ? 36 + from : from, to, net.minecraft.screen.slot.SlotActionType.SWAP, c.player);
                yield GSON.toJsonTree(Map.of("submitted", true));
            }
            case "setFlying" -> {
                if (!c.player.getAbilities().allowFlying) throw new IllegalStateException("flight_not_allowed");
                c.player.getAbilities().flying = p.get("flying").getAsBoolean();
                c.player.sendAbilitiesUpdate();
                yield GSON.toJsonTree(Map.of("flying", c.player.getAbilities().flying));
            }
            case "inventory" -> GSON.toJsonTree(inventory(c));
            case "protectArea" -> GSON.toJsonTree(StructureProtection.protect(c,p));
            case "blockAt" -> block(c, position(p));
            case "findBlocksBatch" -> GSON.toJsonTree(BlockScan.findBatch(c,p));
            case "findBlocks" -> {
                JsonObject query = p.deepCopy();
                query.addProperty("limit",64);
                JsonArray matches = new JsonArray();
                for (JsonElement match : GSON.toJsonTree(BlockScan.scan(c,query).get("blocks")).getAsJsonArray()) {
                    var pos = match.getAsJsonObject().getAsJsonArray("pos");
                    var full = block(c,new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt())).getAsJsonObject();
                    if (match.getAsJsonObject().has("context")) full.add("context",match.getAsJsonObject().get("context"));
                    matches.add(full);
                }
                yield matches;
            }
            case "equip" -> {
                int slot = p.get("slot").getAsInt();
                if (slot < 0 || slot > 8) throw new IllegalArgumentException("Hotbar slot must be 0..8");
                c.player.getInventory().selectedSlot = slot;
                yield GSON.toJsonTree(Map.of("slot",slot));
            }
            case "lookAt" -> {
                Vec3d delta = new Vec3d(number(p,"x"),number(p,"y"),number(p,"z")).subtract(c.player.getEyePos());
                c.player.setYaw((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
                c.player.setPitch((float)-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x,delta.z))));
                yield JsonNull.INSTANCE;
            }
            case "chat" -> {
                String text = p.get("text").getAsString();
                if (text.startsWith("/")) throw new IllegalArgumentException("Chat sends text only; server commands are not part of this API");
                if (text.length() > 256) throw new IllegalArgumentException("Chat maximum is 256 characters");
                c.player.networkHandler.sendChatMessage(text);
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        };
    }
    private static Map<String, Object> compatibility() {
        return Map.of("version", Release.VERSION, "protocol", Release.PROTOCOL,
            "minecraft", Release.MINECRAFT, "fabricLoader", net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString());
    }
    public static String chatSession() { return session(MinecraftClient.getInstance()); }
    static JsonElement observe(MinecraftClient c, JsonObject params) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("session", session(c));
        doc.put("compatibility", compatibility());
        doc.put("chat", ChatInbox.snapshot(session(c), params));
        doc.put("playerPresence", PlayerPresence.snapshot(c));
        doc.put("observedAt", java.time.Instant.now().toString());
        doc.put("connection", PlaySession.snapshot(c));
        doc.put("connected", c.player != null && c.world != null);
        doc.put("joining", c.currentScreen instanceof net.minecraft.client.gui.screen.DownloadingTerrainScreen);
        doc.put("ready", c.player != null && c.world != null && c.currentScreen == null);
        String screen = c.currentScreen == null ? null : c.currentScreen.getTitle().getString();
        if (screen != null && screen.isBlank()) screen = "screen_open";
        doc.put("screen", screen);
        doc.put("heldKeys", HELD.size());
        doc.put("clientTick", tickCount);
        doc.put("movement",Navigation.snapshot(c));
        doc.put("combat",Combat.snapshot(c));
        doc.put("usingItem",c.player!=null&&c.player.isUsingItem());
        if (c.player != null && c.world != null) {
            doc.put("server", c.getCurrentServerEntry()!=null ? c.getCurrentServerEntry().address : c.getServer()!=null ? "singleplayer:"+c.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize() : null);
            doc.put("player", c.player.getName().getString());
            doc.put("pos", List.of(c.player.getX(),c.player.getY(),c.player.getZ()));
            doc.put("yaw", c.player.getYaw()); doc.put("pitch", c.player.getPitch());
            doc.put("health", c.player.getHealth()); doc.put("dead", c.player.isDead()); doc.put("food", c.player.getHungerManager().getFoodLevel());
            doc.put("attacker",System.currentTimeMillis()-damagedAt<=5000?attacker:null);
            var damage = new LinkedHashMap<String,Object>();
            damage.put("sequence",damageSequence);damage.put("attacker",attacker);
            damage.put("observedAt",damagedAt==0?null:java.time.Instant.ofEpochMilli(damagedAt).toString());
            doc.put("damage",damage);
            doc.put("visibleHostiles",ThreatPerception.snapshot(c));
            doc.put("vehicle",c.player.getVehicle()==null?null:WorldActions.entity(c.player.getVehicle()));
            doc.put("sleeping",c.player.isSleeping());
            doc.put("timeOfDay",c.world.getTimeOfDay()%24000);
            doc.put("worldDay",Math.floorDiv(c.world.getTimeOfDay(),24000L));
            doc.put("air", c.player.getAir()); doc.put("submerged", c.player.isSubmergedInWater());
            doc.put("dimension", c.world.getRegistryKey().getValue().toString());
            doc.put("gameMode", c.interactionManager.getCurrentGameMode().getName());
            doc.put("creative", c.interactionManager.hasCreativeInventory());
            doc.put("canFly", c.player.getAbilities().allowFlying);
            doc.put("flying", c.player.getAbilities().flying);
            doc.put("onGround", c.player.isOnGround());
            doc.put("velocity", List.of(c.player.getVelocity().x,c.player.getVelocity().y,c.player.getVelocity().z));
            doc.put("attackCooldown", c.player.getAttackCooldownProgress(0));
            doc.put("container", c.player.currentScreenHandler == c.player.playerScreenHandler ? null : ContainerState.snapshot(c));
            doc.put("inventory", inventory(c)); doc.put("selectedSlot", c.player.getInventory().selectedSlot);
            doc.put("selectedItem", Registries.ITEM.getId(c.player.getMainHandStack().getItem()).toString());
            doc.put("target", c.crosshairTarget instanceof BlockHitResult hit && hit.getType()==HitResult.Type.BLOCK ? block(c,hit.getBlockPos()) : null);
            doc.put("nearbyBlocks", BlockScan.nearby(c));
            var nearby = c.world.getOtherEntities(c.player, c.player.getBoundingBox().expand(32),
                e -> e.squaredDistanceTo(c.player) <= 32 * 32);
            nearby.sort(Comparator.<net.minecraft.entity.Entity>comparingInt(e ->
                e instanceof net.minecraft.entity.player.PlayerEntity ? 0 :
                e == c.player.getAttacker() ? 1 : e instanceof net.minecraft.entity.mob.HostileEntity ? 2 : 3)
                .thenComparingDouble(e -> e.squaredDistanceTo(c.player)).thenComparing(e -> e.getUuidAsString()));
            List<Map<String,Object>> entities = new ArrayList<>();
            for (var e : nearby.subList(0, Math.min(32, nearby.size()))) {
                var entry = new LinkedHashMap<String,Object>(WorldActions.entity(e));
                entry.put("distance", Math.sqrt(e.squaredDistanceTo(c.player)));
                entry.put("lineOfSight", c.player.canSee(e));
                entities.add(entry);
            }
            doc.put("entities", entities);
            doc.put("entityScope", Map.of("radius", 32, "truncated", nearby.size() > 32,
                "omitted", Math.max(0, nearby.size() - 32)));
            var drops=nearby.stream().filter(e->e instanceof net.minecraft.entity.ItemEntity&&e.isAlive())
                .sorted(Comparator.comparingDouble(e->e.squaredDistanceTo(c.player))).toList();
            List<Map<String,Object>> dropped=new ArrayList<>();
            for(var e:drops.subList(0,Math.min(32,drops.size()))) {
                var entry=new LinkedHashMap<String,Object>(WorldActions.entity(e));
                entry.put("distance",Math.sqrt(e.squaredDistanceTo(c.player)));
                entry.put("lineOfSight",c.player.canSee(e));dropped.add(entry);
            }
            doc.put("droppedItems",Map.of("items",dropped,"radius",32,"omitted",Math.max(0,drops.size()-32)));
        }
        return GSON.toJsonTree(doc);
    }
    private static double number(JsonObject p,String key) {
        double n=p.get(key).getAsDouble(); if(!Double.isFinite(n))throw new IllegalArgumentException("Coordinates must be finite");return n;
    }
    private static BlockPos position(JsonObject p) { return BlockPos.ofFloored(number(p,"x"),number(p,"y"),number(p,"z")); }
    private static JsonObject block(MinecraftClient c,BlockPos pos) {
        if(!WorldActions.loaded(c,pos))throw new IllegalArgumentException("Chunk is not loaded");
        JsonObject result=new JsonObject(); result.addProperty("name",Registries.BLOCK.getId(c.world.getBlockState(pos).getBlock()).toString());
        result.addProperty("state",c.world.getBlockState(pos).toString());
        result.add("properties",GSON.toJsonTree(WorldActions.properties(c.world.getBlockState(pos))));
        result.add("resource",GSON.toJsonTree(ResourceProtection.describe(c,pos)));
        result.addProperty("miningClear",BlockMining.clear(c.world.getBlockState(pos)));
        result.addProperty("replaceable",c.world.getBlockState(pos).isReplaceable());
        result.addProperty("canOpenContainer",c.world.getBlockState(pos).createScreenHandlerFactory(c.world,pos)!=null);
        result.add("pos",GSON.toJsonTree(List.of(pos.getX(),pos.getY(),pos.getZ())));
        result.add("serverUpdate", GSON.toJsonTree(CONFIRMED.get(pos)));
        return result;
    }
    static List<Map<String,Object>> inventory(MinecraftClient c) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(int i=0;i<c.player.getInventory().size();i++) {
            var stack=c.player.getInventory().getStack(i);
            if(!stack.isEmpty()){var entry=new LinkedHashMap<String,Object>(ItemActions.stack(stack));entry.put("slot",i);result.add(entry);}
        }
        return result;
    }
    private static KeyBinding key(MinecraftClient c,String key) {
        return switch(key.toUpperCase(Locale.ROOT)) {
            case "W","FORWARD" -> c.options.forwardKey;case "S","BACK" -> c.options.backKey;
            case "A","LEFT" -> c.options.leftKey;case "D","RIGHT" -> c.options.rightKey;
            case "SPACE","JUMP" -> c.options.jumpKey;case "SHIFT","SNEAK" -> c.options.sneakKey;
            case "CTRL","SPRINT" -> c.options.sprintKey;
            default -> throw new IllegalArgumentException("Unsupported key: " + key);
        };
    }
    static void release(MinecraftClient c) {
        CraftingBatch.stop(c,"controls_released");
        releaseMovement(c);
    }
    private static void releaseMovement(MinecraftClient c) {
        BaritoneTasks.stop(c,"cancelled","action_cancelled");
        BaritoneBuild.stop(c,"cancelled","action_cancelled");
        Combat.stop(c,"cancelled","controls_released");
        Navigation.stop(c,"cancelled","controls_released");
        JumpPlacement.stop(c,"cancelled","controls_released");
        ItemActions.stop(c);
        for(KeyBinding key:HELD.keySet())key.setPressed(false);HELD.clear();
        BlockMining.stop(c,"controls_released");
        if(c.interactionManager!=null)c.interactionManager.cancelBlockBreaking();
    }
    public static void tick() {
        PlaySession.tick(MinecraftClient.getInstance());
        tickCount++;
        MinecraftClient c=MinecraftClient.getInstance();long now=System.nanoTime();
        session(c);
        VisibleBlockScan.tick(c);
        RegionInspection.tick(c);
        BaritoneTasks.tick(c);
        BaritoneBuild.tick(c);
        HarvestScan.tick(c);
        CraftingBatch.tick(c);
        if (tickCount % 5 == 0) ObservationStream.capture(c);
        if(c.player==null || c.world==null) {release(c);return;}
        if(c.currentScreen!=null) {releaseMovement(c);return;}
        Navigation.tick(c);
        HELD.entrySet().removeIf(e->{if(now>=e.getValue()){e.getKey().setPressed(false);return true;}return false;});
        ItemActions.tick(c);
        Combat.tick(c);
        BlockMining.tick(c);
    }
}
