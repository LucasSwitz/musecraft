package ai.musecraft;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.util.math.*;

/** One movement owner. Ground travel uses Baritone; flight and vehicles are explicit modes. */
public final class Navigation {
    private static boolean ground,flee;
    static JsonElement start(MinecraftClient c,JsonObject p) {
        stop(c,"cancelled","replaced");
        flee=p.has("flee")&&p.get("flee").getAsBoolean();
        if(flee)return FleeNavigation.start(c,p);
        ground=!(p.has("flying")&&p.get("flying").getAsBoolean())&&!c.player.hasVehicle();
        return ground?BaritoneNavigation.start(c,p):SpecializedNavigation.start(c,p);
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(flee)return FleeNavigation.poll(c,p);
        return ground?BaritoneNavigation.poll(c,p):SpecializedNavigation.poll(c,p);
    }
    static JsonElement snapshot(MinecraftClient c) {
        if(flee)return FleeNavigation.snapshot(c);
        return ground?BaritoneNavigation.snapshot(c):SpecializedNavigation.snapshot(c);
    }
    static void stop(MinecraftClient c,String status,String reason) {
        FleeNavigation.stop(c,status,reason);
        BaritoneNavigation.stop(c,status,reason);
        SpecializedNavigation.stop(c,status,reason);
    }
    static void tick(MinecraftClient c){if(flee)FleeNavigation.tick(c);else if(ground)BaritoneNavigation.tick(c);}
    public static void apply(Input input) {
        if(flee)return;
        if(ground)BaritoneNavigation.align(input);
        else SpecializedNavigation.apply(input);
    }
    static void dismount(){ground=false;SpecializedNavigation.dismount();}
    static boolean door(MinecraftClient c,BlockPos p){return SpecializedNavigation.door(c,p);}
    static boolean valid(MinecraftClient c,Vec3d p,boolean fly,boolean doors){return SpecializedNavigation.valid(c,p,fly,doors);}
    static Vec3d feet(BlockPos p){return SpecializedNavigation.feet(p);}
    static boolean clear(MinecraftClient c,Vec3d from,Vec3d to,boolean fly){return SpecializedNavigation.clear(c,from,to,fly);}
}
