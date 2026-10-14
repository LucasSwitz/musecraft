package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;

/** Tick-driven melee. The controller chooses a target and renews the action lease. */
public final class Combat {
    private static final Gson JSON=new Gson();
    private static String id,uuid,status="idle",reason,outcome;
    private static int entityId,strikes;
    private static long leaseUntil;
    private static boolean guarding,needsApproach,shieldEquipped;
    private static Float lastHealth;
    private record Target(int entityId,String uuid) {}
    private static final ArrayDeque<Target> queue=new ArrayDeque<>();
    private static final ArrayList<Map<String,Object>> outcomes=new ArrayList<>();
    private static int targetStrikes,omittedOutcomes,unconfirmedTargets;
    private static long targetStartedAt,targetFirstStrikeAt;

    private static Target resolve(MinecraftClient c,JsonObject p) {
        int entityId=p.get("entityId").getAsInt();String uuid=p.get("uuid").getAsString();
        var target=c.world.getEntityById(entityId);
        if(!(target instanceof LivingEntity)||!target.isAlive()||!target.getUuidAsString().equals(uuid))
            throw new IllegalStateException("target_unavailable");
        if(target==c.player)throw new IllegalStateException("cannot_attack_self");
        return new Target(entityId,uuid);
    }
    private static void select(MinecraftClient c,Target target) {
        Navigation.stop(c,"completed","combat_target_changed");
        entityId=target.entityId();uuid=target.uuid();
        lastHealth=null;targetStrikes=0;needsApproach=true;targetStartedAt=System.currentTimeMillis();targetFirstStrikeAt=0;
    }
    static JsonElement enqueue(MinecraftClient c,JsonObject p) {
        if(id==null||!id.equals(p.get("id").getAsString())||!status.equals("running"))
            throw new IllegalStateException("attack_not_running");
        Target target=resolve(c,p);
        if(!uuid.equals(target.uuid())&&queue.stream().noneMatch(q->q.uuid().equals(target.uuid()))) {
            if(queue.size()>=31)throw new IllegalStateException("attack_queue_full");
            queue.add(target);
        }
        return snapshot(c);
    }
    private static void finishedTarget(String why) {
        if(!why.equals("dead"))unconfirmedTargets++;
        if(outcomes.size()>=32){outcomes.remove(0);omittedOutcomes++;}
        outcomes.add(Map.of("entityId",entityId,"uuid",uuid,"outcome",why,"strikes",targetStrikes,
            "startedAt",targetStartedAt,"firstStrikeAt",targetFirstStrikeAt,"finishedAt",System.currentTimeMillis()));
    }

    static JsonElement start(MinecraftClient c,JsonObject p) {
        Target next=resolve(c,p);
        stop(c,"cancelled","replaced");
        queue.clear();outcomes.clear();omittedOutcomes=0;unconfirmedTargets=0;
        if(p.has("weapon")) {
            var equip=new JsonObject();equip.addProperty("item",p.get("weapon").getAsString());
            ItemActions.equip(c,equip);
        } else WorldActions.dispatch(c,"selectWeapon",new JsonObject());
        if(!c.player.getOffHandStack().isOf(Items.SHIELD)) {
            for(int slot=0;slot<36;slot++)if(c.player.getInventory().getStack(slot).isOf(Items.SHIELD)) {
                // Vanilla SWAP preserves the previous offhand item in this slot.
                c.interactionManager.clickSlot(c.player.playerScreenHandler.syncId,slot<9?36+slot:slot,40,SlotActionType.SWAP,c.player);
                break;
            }
        }
        id=UUID.randomUUID().toString();status="running";reason=null;outcome=null;
        strikes=0;lastHealth=null;needsApproach=true;shieldEquipped=c.player.getOffHandStack().isOf(Items.SHIELD);
        select(c,next);
        leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static JsonElement poll(MinecraftClient c,JsonObject p) {
        if(id==null||!id.equals(p.get("id").getAsString()))throw new IllegalStateException("combat_identity_changed");
        if(status.equals("running"))leaseUntil=System.currentTimeMillis()+5000;
        return snapshot(c);
    }
    static JsonElement snapshot(MinecraftClient c) {
        var out=new LinkedHashMap<String,Object>();
        out.put("id",id);out.put("entityId",entityId);out.put("uuid",uuid);out.put("status",status);out.put("reason",reason);
        out.put("strikes",strikes);out.put("outcome",outcome);out.put("lastHealth",lastHealth);
        out.put("needsApproach",needsApproach);out.put("shieldEquipped",shieldEquipped);out.put("guarding",guarding);
        out.put("queue",queue.stream().map(q->Map.of("entityId",q.entityId(),"uuid",q.uuid())).toList());
        out.put("outcomes",outcomes);out.put("omittedOutcomes",omittedOutcomes);out.put("targetStartedAt",targetStartedAt);out.put("targetFirstStrikeAt",targetFirstStrikeAt);
        var target=c.world==null?null:c.world.getEntityById(entityId);
        if(target!=null&&target.getUuidAsString().equals(uuid))out.put("target",List.of(target.getX(),target.getY(),target.getZ()));
        return JSON.toJsonTree(out);
    }
    public static boolean maintainingUse() {
        return status.equals("running")&&guarding&&System.currentTimeMillis()<leaseUntil;
    }
    private static void guard(MinecraftClient c,boolean wanted) {
        if(!wanted) {
            if(guarding&&c.player!=null&&c.player.isUsingItem()&&c.player.getActiveHand()==Hand.OFF_HAND)
                c.interactionManager.stopUsingItem(c.player);
            guarding=false;return;
        }
        if(c.player.getOffHandStack().isOf(Items.SHIELD)&&!c.player.getItemCooldownManager().isCoolingDown(Items.SHIELD)) {
            if(!c.player.isUsingItem())c.interactionManager.interactItem(c.player,Hand.OFF_HAND);
            guarding=c.player.isUsingItem()&&c.player.getActiveHand()==Hand.OFF_HAND;
        } else guard(c,false);
    }
    static void stop(MinecraftClient c,String terminal,String why) {
        if(!status.equals("running"))return;
        guard(c,false);status=terminal;reason=why;needsApproach=false;
        if(terminal.equals("cancelled"))queue.clear();
    }
    static void tick(MinecraftClient c) {
        if(!status.equals("running"))return;
        if(c.player==null||c.world==null||c.currentScreen!=null){stop(c,"cancelled","client_not_ready");return;}
        if(System.currentTimeMillis()>=leaseUntil){stop(c,"cancelled","combat_lease_expired");return;}
        var target=c.world.getEntityById(entityId);
        // The queue advances in the same client tick. Neither a controller poll
        // nor an agent turn sits between one confirmed death and the next target.
        while(target==null||!target.getUuidAsString().equals(uuid)||!target.isAlive()) {
            outcome=target==null?"gone":!target.getUuidAsString().equals(uuid)?"identity_changed":"dead";
            finishedTarget(outcome);
            if(queue.isEmpty()) {
                boolean allDead=unconfirmedTargets==0;
                stop(c,allDead?"completed":"partial",allDead?"targets_defeated":"targets_unconfirmed");return;
            }
            select(c,queue.removeFirst());
            target=c.world.getEntityById(entityId);
        }
        lastHealth=target instanceof LivingEntity living?living.getHealth():null;
        var point=target.getBoundingBox().getCenter();var eye=c.player.getEyePos();
        double distance=eye.distanceTo(point);
        boolean visible=c.world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,c.player)).getType()==HitResult.Type.MISS;
        needsApproach=!visible||distance>c.player.getEntityInteractionRange()-.05;
        if(!needsApproach)Navigation.stop(c,"completed","combat_in_reach");
        if(needsApproach && distance>4.5){guard(c,false);return;}
        WorldActions.aim(c,point);
        // Shield direction is authoritative on the server before use/attack.
        c.player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(c.player.getYaw(),c.player.getPitch(),c.player.isOnGround()));
        try {
            if(!needsApproach&&c.player.getAttackCooldownProgress(0)>=.95) {
                guard(c,false);
                var request=new JsonObject();request.addProperty("entityId",entityId);request.addProperty("uuid",uuid);
                WorldActions.dispatch(c,"attackEntity",request);strikes++;targetStrikes++;
                if(targetFirstStrikeAt==0)targetFirstStrikeAt=System.currentTimeMillis();
            }
            guard(c,visible&&distance<4.5);
        } catch(RuntimeException error) {stop(c,"blocked",error.getMessage());}
    }
}
