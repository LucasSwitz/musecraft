package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;

/** Jump to reach a support face, submit one vanilla placement, and land. */
public final class JumpPlacement {
    private static final Gson JSON=new Gson();
    private static String id,status="idle",reason;
    private static JsonObject request,admission;
    private static long deadline;
    private static boolean airborne;
    static JsonElement start(MinecraftClient c,JsonObject p) {
        if(!c.player.isOnGround()||c.player.hasVehicle()||c.player.getAbilities().flying)
            throw new IllegalStateException("jump_placement_requires_ground");
        Navigation.stop(c,"cancelled","jump_placement_started");
        id=UUID.randomUUID().toString();status="running";reason=null;
        request=p.deepCopy();admission=null;airborne=false;
        deadline=System.currentTimeMillis()+4000;
        return snapshot();
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(id==null||!id.equals(p.get("id").getAsString()))throw new IllegalStateException("placement_identity_changed");
        return snapshot();
    }
    private static JsonElement snapshot() {
        var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("status",status);out.put("reason",reason);out.put("admission",admission);
        return JSON.toJsonTree(out);
    }
    static void stop(MinecraftClient c,String terminal,String why) {
        if(!status.equals("running"))return;
        status=terminal;reason=why;
        if(c.player!=null)c.player.input.jumping=false;
    }
    public static void apply(Input input) {
        if(!status.equals("running"))return;
        var c=MinecraftClient.getInstance();
        input.movementForward=0;input.movementSideways=0;input.jumping=false;input.sneaking=false;
        if(c.player==null||c.world==null||c.currentScreen!=null){stop(c,"cancelled","client_not_ready");return;}
        if(System.currentTimeMillis()>=deadline){stop(c,"blocked","jump_placement_unreachable");return;}
        if(c.player.isOnGround()) {
            if(airborne){stop(c,admission==null?"blocked":"completed",admission==null?"jump_placement_unreachable":"placed_and_landed");return;}
            input.jumping=true;return;
        }
        airborne=true;
        if(admission!=null)return;
        try {
            var result=WorldActions.dispatch(c,"placeAt",request).getAsJsonObject();
            if(!result.get("accepted").getAsBoolean()){stop(c,"blocked","interaction_rejected");return;}
            admission=result;
        } catch(IllegalStateException error) {
            if(!error.getMessage().startsWith("no_reachable_")&&!error.getMessage().equals("target_intersects_player"))
                stop(c,"blocked",error.getMessage());
        }
    }
}
