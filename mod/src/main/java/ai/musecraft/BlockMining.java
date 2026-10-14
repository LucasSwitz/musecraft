package ai.musecraft;

import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** One mining attempt, with a server receipt independent of later block updates. */
final class BlockMining {
    private static String id, item, reason;
    private static BlockState original;
    private static BlockHitResult hit;
    private static long afterRevision, until;
    private static Map<String,Object> receipt;

    static boolean clear(BlockState state) {
        // Waterlogged solids and replaceable vegetation still need mining.
        return state.isAir() || state.getBlock() instanceof FluidBlock;
    }
    static Map<String,Object> begin(MinecraftClient c,BlockHitResult face) {
        ResourceProtection.start(c);
        if(BaritoneProtection.rejectBreak(face.getBlockPos()))throw new IllegalStateException("protected_structure");
        stop(c,"mining_replaced");
        hit=face;original=c.world.getBlockState(hit.getBlockPos());
        float delta=original.calcBlockBreakingDelta(c.player,c.world,hit.getBlockPos());
        if(!c.interactionManager.hasCreativeInventory()&&delta<=0)throw new IllegalStateException("block_not_mineable");
        id=UUID.randomUUID().toString();item=Registries.ITEM.getId(c.player.getMainHandStack().getItem()).toString();
        afterRevision=MusecraftClient.revision;receipt=null;reason=null;
        until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        long expectedMs=c.interactionManager.hasCreativeInventory()?50:(long)Math.ceil(1.0/delta)*50;
        boolean started=c.interactionManager.attackBlock(hit.getBlockPos(),hit.getSide());
        c.player.swingHand(Hand.MAIN_HAND);
        if(!started)stop(c,"interaction_rejected");
        return Map.of("id",id,"expectedMs",expectedMs,"started",started,
            "target",position(),"afterRevision",afterRevision);
    }
    private static List<Integer> position() {
        var p=hit.getBlockPos();return List.of(p.getX(),p.getY(),p.getZ());
    }
    static void serverUpdated(BlockPos pos,BlockState state,long revision) {
        if(id==null||receipt!=null||!pos.equals(hit.getBlockPos())||revision<=afterRevision)return;
        // Properties alone (e.g. lit ore) do not prove removal. Retain the first
        // server-confirmed replacement even if fluid or falling blocks follow.
        if(state.getBlock()!=original.getBlock())receipt=Map.of("id",id,"target",position(),
            "revision",revision,"original",Registries.BLOCK.getId(original.getBlock()).toString(),
            "replacement",Registries.BLOCK.getId(state.getBlock()).toString(),"confirmation","server_block_removal");
    }
    static Map<String,Object> poll(MinecraftClient c,String requested) {
        if(id==null||!id.equals(requested))throw new IllegalStateException("mining_job_changed");
        if(receipt!=null)return Map.of("status","completed","removed",true,"receipt",receipt);
        if(reason!=null)return Map.of("status","blocked","reason",reason,
            "evidence",MiningAccess.evidence(c,hit.getBlockPos()));
        until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        return Map.of("status","running","removed",false);
    }
    static void stop(MinecraftClient c,String why) {
        if(until>0&&c.interactionManager!=null)c.interactionManager.cancelBlockBreaking();
        until=0;if(id!=null&&receipt==null&&reason==null)reason=why;
    }
    static void reset(MinecraftClient c) {stop(c,"world_changed");id=null;receipt=null;}
    static void tick(MinecraftClient c) {
        if(until==0||c.interactionManager==null)return;
        if(receipt!=null){stop(c,"completed");return;}
        if(System.nanoTime()>=until){stop(c,"mining_lease_expired");return;}
        if(!Registries.ITEM.getId(c.player.getMainHandStack().getItem()).toString().equals(item)) {
            stop(c,"mining_tool_changed");return;
        }
        // The client predicts removal before the server confirms it. Wait for
        // the packet; a local air/fluid state is not a completion receipt.
        if(c.world.getBlockState(hit.getBlockPos()).getBlock()!=original.getBlock())return;
        try {
            var faces=WorldActions.visibleFaces(c,hit.getBlockPos(),null,c.player.getEyePos(),true);
            if(faces.isEmpty())throw new IllegalStateException("no_reachable_visible_face");
            hit=faces.getFirst();
        }
        catch(IllegalStateException missing) {
            var eye=c.player.getEyePos();var p=hit.getBlockPos();
            double distance=eye.distanceTo(new net.minecraft.util.math.Vec3d(
                Math.clamp(eye.x,p.getX(),p.getX()+1.),Math.clamp(eye.y,p.getY(),p.getY()+1.),
                Math.clamp(eye.z,p.getZ(),p.getZ()+1.)));
            stop(c,distance>c.player.getBlockInteractionRange()-.05?"mining_out_of_reach":"mining_occluded");return;
        }
        if(BaritoneProtection.rejectBreak(hit.getBlockPos())){stop(c,"protected_structure");return;}
        WorldActions.aim(c,hit.getPos());
        c.interactionManager.updateBlockBreakingProgress(hit.getBlockPos(),hit.getSide());
        c.player.swingHand(Hand.MAIN_HAND);
    }
}
