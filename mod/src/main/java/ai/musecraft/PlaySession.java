package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded controller lease fences connection attempts and all gameplay writes. */
final class PlaySession {
    private static String id, server, player;
    private static long generation, until, connectingSince;
    private static String connectionError;
    private static boolean active, ended;
    private PlaySession() {}
    static Map<String, Object> policy(MinecraftClient c, JsonObject p) {
        long next = p.get("generation").getAsLong();
        String nextId = p.get("id").getAsString();
        if (next < generation || (next == generation && id != null && !id.equals(nextId)))
            throw new IllegalStateException("play_session_changed");
        if (next == generation && ended && p.get("active").getAsBoolean())
            throw new IllegalStateException("play_session_ended");
        if (next > generation) { disconnect(c); ended = false; }
        id = nextId; generation = next;
        server = p.get("server").getAsString(); player = p.get("player").getAsString();
        active = p.get("active").getAsBoolean(); ended = ended || p.get("ended").getAsBoolean();
        until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        if (!active) disconnect(c);
        return Map.of("id", id, "active", active, "ended", ended);
    }
    static boolean controlsPlayer() {
        return id!=null&&active&&!ended&&System.nanoTime()<until;
    }
    static void admit(JsonObject request) {
        if (id == null) return;
        if (!active || ended || System.nanoTime() >= until) throw new IllegalStateException("play_session_inactive");
        if (!request.has("playSession") || !id.equals(request.get("playSession").getAsString()))
            throw new IllegalStateException("play_session_changed");
    }
    static Map<String, Object> connect(MinecraftClient c, JsonObject p) {
        if (id == null || !id.equals(p.get("id").getAsString()) || !active || ended || System.nanoTime() >= until)
            throw new IllegalStateException("play_session_inactive");
        if (!c.getSession().getUsername().equals(player)) throw new IllegalStateException("wrong_licensed_player");
        // Initial resource loading later installs the title screen. Connecting
        // underneath that overlay can join successfully, then cover the world.
        if (c.getOverlay() != null || c.world != null || c.currentScreen instanceof ConnectScreen)
            return Map.of("submitted", false);
        connectingSince = System.nanoTime(); connectionError = null;
        ConnectScreen.connect(new TitleScreen(), c, ServerAddress.parse(server),
            new ServerInfo("Musecraft", server, ServerInfo.ServerType.OTHER), false, null);
        return Map.of("submitted", true);
    }
    static Map<String, Object> snapshot(MinecraftClient c) {
        var result = new LinkedHashMap<String, Object>();
        result.put("connecting", c.currentScreen instanceof ConnectScreen);
        result.put("reason", c.currentScreen instanceof DisconnectedScreen ? c.currentScreen.getNarratedTitle().getString() : connectionError);
        result.put("playSession", id);
        return result;
    }
    static void tick(MinecraftClient c) {
        if (c.currentScreen instanceof ConnectScreen && connectingSince != 0 &&
                System.nanoTime() - connectingSince > java.util.concurrent.TimeUnit.SECONDS.toNanos(45)) {
            disconnect(c); connectionError = "connection_attempt_timed_out"; connectingSince = 0;
        }
        if (id != null && (!active || ended || System.nanoTime() >= until)) disconnect(c);
    }
    private static void disconnect(MinecraftClient c) {
        MusecraftClient.release(c);
        if (c.world != null) { c.world.disconnect(); c.disconnect(new TitleScreen()); }
        // on close, ConnectScreen cancels its pending network connection.
        if (c.currentScreen instanceof ConnectScreen screen) ((ai.musecraft.mixin.ConnectScreenInvoker) screen).musecraft$cancel(null);
    }
}
