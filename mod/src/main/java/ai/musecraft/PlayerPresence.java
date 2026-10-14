package ai.musecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import java.util.*;

/** Packet-driven, client-thread roster. Reads retain transitions until cursor ACK upstream. */
public final class PlayerPresence {
    private static ClientPlayNetworkHandler connection;
    private static String session = UUID.randomUUID().toString();
    private static boolean armed;
    private static long sequence;
    private static final Map<UUID,Map<String,Object>> players = new LinkedHashMap<>();
    private static final ArrayDeque<Map<String,Object>> events = new ArrayDeque<>();

    private static void bind(ClientPlayNetworkHandler handler) {
        if (connection == handler) return;
        connection = handler; session = UUID.randomUUID().toString();
        armed = false; sequence = 0; players.clear(); events.clear();
    }
    private static void event(String kind, UUID uuid, Map<String,Object> player) {
        var local = MinecraftClient.getInstance().getSession().getUuidOrNull();
        if (!armed || uuid.equals(local)) return;
        events.addLast(Map.of("sequence",++sequence,"kind",kind,"player",player,
            "observedAt",java.time.Instant.now().toString()));
        while (events.size() > 128) events.removeFirst();
    }
    public static void added(ClientPlayNetworkHandler handler, PlayerListS2CPacket packet) {
        bind(handler);
        for (var entry : packet.getPlayerAdditionEntries()) {
            if (entry.profile() == null) continue;
            var player = Map.<String,Object>of("uuid",entry.profileId().toString(),"name",entry.profile().getName());
            if (players.put(entry.profileId(),player) == null) event("player_joined",entry.profileId(),player);
        }
    }
    public static void removed(ClientPlayNetworkHandler handler, PlayerRemoveS2CPacket packet) {
        bind(handler);
        for (var uuid : packet.profileIds()) {
            var player = players.remove(uuid);
            if (player != null) event("player_left",uuid,player);
        }
    }
    static Map<String,Object> snapshot(MinecraftClient client) {
        bind(client.getNetworkHandler());
        // Initial tab-list packets establish the roster. A new connection never
        // means everyone joined, and a dimension change preserves this roster.
        if (connection != null && client.player != null && client.world != null && client.currentScreen == null) armed = true;
        var roster = players.values().stream().sorted(Comparator.comparing(p -> p.get("uuid").toString())).toList();
        return Map.of("session",session,"sequence",sequence,
            "oldestSequence",events.isEmpty()?sequence+1:events.peekFirst().get("sequence"),
            "events",List.copyOf(events),"players",roster.subList(0,Math.min(32,roster.size())),
            "count",roster.size(),"omitted",Math.max(0,roster.size()-32));
    }
}
