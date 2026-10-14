package ai.musecraft;

import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Map;

/** Animations offered by the player's Figura avatar. */
final class PlayerAnimations {
    static List<Map<String, Object>> list(MinecraftClient c) throws ReflectiveOperationException {
        var figura = FiguraAvatars.animations(c);
        if (figura == null) throw new IllegalStateException("player_model_has_no_animations");
        return figura;
    }
    static Map<String, Object> play(MinecraftClient c, String name, double value) throws ReflectiveOperationException {
        if (list(c).stream().noneMatch(row -> name.equals(row.get("name"))))
            throw new IllegalArgumentException("unknown_client_animation:" + name);
        return FiguraAvatars.play(c, name, value);
    }
}
