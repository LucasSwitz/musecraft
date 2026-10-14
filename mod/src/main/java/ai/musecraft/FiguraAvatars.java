package ai.musecraft;

import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Optional Figura 0.1.x integration. Invoked only on Minecraft's client thread.
 * Reflection keeps the game bridge usable when Figura is not installed.
 *
 * The player's avatar script lists and plays its animations through two global
 * functions, musecraft_animations() (comma-separated names) and
 * musecraft_animate(name, value) (true when it played). The avatar operations
 * do what Figura's Wardrobe does: load a local avatar folder, upload it for this
 * account, and reload avatars from Figura's backend.
 */
final class FiguraAvatars {
    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("org.figuramc.figura." + name);
    }
    static boolean installed() {
        try { type("avatar.AvatarManager"); return true; } catch (ClassNotFoundException e) { return false; }
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }
    /** The signed-in account's avatar; works from the title screen as well as in a world. */
    private static Object avatar(MinecraftClient c) throws ReflectiveOperationException {
        UUID account = c.getSession().getUuidOrNull();
        if (account == null) throw new IllegalStateException("figura_account_unknown");
        return type("avatar.AvatarManager").getMethod("getAvatarForPlayer", UUID.class).invoke(null, account);
    }
    /** Runs a Lua chunk in the player's avatar; null when it has no running script. */
    private static Object lua(MinecraftClient c, String source, Object... args) throws ReflectiveOperationException {
        Object avatar = avatar(c);
        if (avatar == null || !Boolean.TRUE.equals(field(avatar, "loaded")) || Boolean.TRUE.equals(field(avatar, "scriptError")))
            return null;
        Object runtime = field(avatar, "luaRuntime");
        if (runtime == null) return null;
        Object chunk = runtime.getClass().getMethod("load", String.class, String.class).invoke(runtime, "musecraft", source);
        Object result = avatar.getClass().getMethod("run", Object.class, type("avatar.Avatar$Instructions"), Object[].class)
            .invoke(avatar, chunk, field(avatar, "tick"), args);
        return result == null ? null : result.getClass().getMethod("arg1").invoke(result);
    }
    private static boolean nil(Object value) throws ReflectiveOperationException {
        return value == null || (Boolean) value.getClass().getMethod("isnil").invoke(value);
    }

    /** The avatar's animations, or null when Figura or the avatar offers none. */
    static List<Map<String, Object>> animations(MinecraftClient c) throws ReflectiveOperationException {
        if (!installed() || c.player == null) return null;
        Object names = lua(c, "return musecraft_animations and musecraft_animations() or nil");
        if (nil(names)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : ((String) names.getClass().getMethod("tojstring").invoke(names)).split(","))
            if (!name.isBlank()) result.add(Map.of("name", name.trim(), "value", 0, "maxValue", 255));
        result.sort(Comparator.comparing(row -> (String) row.get("name")));
        return result;
    }
    static Map<String, Object> play(MinecraftClient c, String name, double value) throws ReflectiveOperationException {
        if (name.isEmpty() || name.length() > 128) throw new IllegalArgumentException("invalid_animation_name");
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > 255)
            throw new IllegalArgumentException("invalid_animation_value");
        Object played = lua(c, "return musecraft_animate and musecraft_animate(...) or false", name, (int) value);
        if (nil(played) || !(Boolean) played.getClass().getMethod("toboolean").invoke(played))
            throw new IllegalArgumentException("unknown_client_animation:" + name);
        return Map.of("name", name, "value", (int) value, "maxValue", 255);
    }

    /** Operator avatar setup: {operation:"status"|"load"|"upload"|"reload", path?}. */
    static Map<String, Object> operate(MinecraftClient c, com.google.gson.JsonObject p) throws ReflectiveOperationException {
        if (!installed()) throw new IllegalStateException("figura_not_installed");
        String operation = p.has("operation") ? p.get("operation").getAsString() : "status";
        Class<?> manager = type("avatar.AvatarManager");
        Class<?> network = type("backend2.NetworkStuff");
        switch (operation) {
            case "status" -> { }
            case "load" -> {
                Path folder = Path.of(p.get("path").getAsString()).toAbsolutePath().normalize();
                if (!Files.isRegularFile(folder.resolve("avatar.json"))) throw new IllegalArgumentException("avatar_folder_missing_avatar_json");
                manager.getMethod("loadLocalAvatar", Path.class).invoke(null, folder);
            }
            case "upload" -> {
                Object avatar = avatar(c);
                if (avatar == null || !Boolean.TRUE.equals(field(avatar, "loaded")) || field(avatar, "nbt") == null)
                    throw new IllegalStateException("figura_avatar_not_loaded");
                if (manager.getField("localUploaded").getBoolean(null)) throw new IllegalStateException("figura_avatar_not_local");
                if (!(Boolean) network.getMethod("canUpload").invoke(null)) throw new IllegalStateException("figura_backend_not_ready");
                int limit = (Integer) network.getMethod("getSizeLimit").invoke(null);
                int size = (Integer) field(avatar, "fileSize");
                if (size > limit) throw new IllegalStateException("figura_avatar_too_large:" + size + ">" + limit);
                network.getMethod("uploadAvatar", type("avatar.Avatar")).invoke(null, avatar);
            }
            case "reload" -> manager.getMethod("clearAllAvatars").invoke(null);
            default -> throw new IllegalArgumentException("unknown_avatar_operation:" + operation);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("operation", operation);
        status.put("connected", network.getMethod("isConnected").invoke(null));
        status.put("canUpload", network.getMethod("canUpload").invoke(null));
        status.put("sizeLimit", network.getMethod("getSizeLimit").invoke(null));
        Object avatar = avatar(c);
        if (avatar != null) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("name", field(avatar, "name"));
            current.put("loaded", field(avatar, "loaded"));
            current.put("source", manager.getField("localUploaded").getBoolean(null) ? "backend" : "local");
            current.put("size", field(avatar, "fileSize"));
            current.put("scriptError", field(avatar, "scriptError"));
            status.put("avatar", current);
        } else status.put("avatar", null);
        return status;
    }
}
