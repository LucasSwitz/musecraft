package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import java.util.*;

/** Bounded client-thread inbox; reads never consume messages. */
public final class ChatInbox {
    private static final ArrayDeque<Map<String, Object>> messages = new ArrayDeque<>();
    private static long sequence;
    static void clear() { messages.clear(); sequence = 0; }
    public static void add(String kind, String text, UUID sender, String name, boolean signaturePresent) {
        add(kind,text,sender,name,signaturePresent,null);
    }
    public static void add(String kind, String text, UUID sender, String name, boolean signaturePresent, String translationKey) {
        String session = MusecraftClient.chatSession();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", session + ":chat:" + (++sequence)); row.put("seq", sequence);
        row.put("receivedAt", java.time.Instant.now().toString()); row.put("kind", kind);
        row.put("text", text.length() > 1024 ? text.substring(0, 1024) : text);
        row.put("textTruncated", text.length() > 1024);
        row.put("senderUuid", sender == null ? null : sender.toString()); row.put("senderName", name);
        row.put("signaturePresent", signaturePresent);
        if(translationKey!=null)row.put("translationKey",translationKey.substring(0,Math.min(translationKey.length(),128)));
        var player = MinecraftClient.getInstance().player;
        row.put("self", player != null && player.getUuid().equals(sender));
        messages.addLast(row);
        while (messages.size() > 128) messages.removeFirst();
    }
    static Map<String, Object> snapshot(String session, JsonObject params) {
        long after = 0;
        boolean supplied = params.has("chatAfter") && !params.get("chatAfter").isJsonNull();
        boolean reset = false;
        if (supplied) {
            JsonObject cursor = params.getAsJsonObject("chatAfter");
            reset = !session.equals(cursor.get("session").getAsString());
            if (!reset) {
                double seq = cursor.get("seq").getAsDouble();
                if (!Double.isFinite(seq) || seq < 0 || seq != Math.rint(seq) || seq > sequence)
                    throw new IllegalArgumentException("invalid_chat_cursor");
                after = (long) seq;
            }
        }
        long oldest = messages.isEmpty() ? sequence + 1 : (long) messages.peekFirst().get("seq");
        List<Map<String, Object>> selected = new ArrayList<>();
        for (var row : messages) if ((long) row.get("seq") > after) selected.add(row);
        return Map.of("messages", selected, "nextCursor", Map.of("session", session, "seq", sequence),
            "reset", reset, "truncated", after < oldest - 1, "oldestSeq", oldest);
    }
}
