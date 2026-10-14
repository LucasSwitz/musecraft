package ai.musecraft;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The game thread captures; bounded subscriber threads handle socket writes. */
final class ObservationStream {
    private static volatile String latest;
    private static final AtomicInteger subscribers = new AtomicInteger();

    static void capture(MinecraftClient client) {
        if (subscribers.get() == 0) return;
        latest = MusecraftClient.observe(client, new JsonObject()).toString() + "\n";
    }

    static void serve(SocketChannel channel) throws Exception {
        if (subscribers.incrementAndGet() > 1) {
            subscribers.decrementAndGet();
            throw new IllegalStateException("observation_subscriber_already_connected");
        }
        try {
            String sent = null;
            while (channel.isOpen()) {
                String next = latest;
                if (next != null && next != sent) {
                    var bytes = StandardCharsets.UTF_8.encode(next);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (bytes.hasRemaining()) {
                        if (System.nanoTime() >= deadline) throw new IllegalStateException("observation_subscriber_too_slow");
                        if (channel.write(bytes) == 0) Thread.sleep(5);
                    }
                    sent = next;
                }
                Thread.sleep(50);
            }
        } finally { subscribers.decrementAndGet(); }
    }
}
