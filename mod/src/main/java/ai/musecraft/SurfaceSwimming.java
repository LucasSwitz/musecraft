package ai.musecraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.LivingEntity;

/** Keep an actively leased companion afloat between actions and model turns. */
public final class SurfaceSwimming {
    private static AbstractHorseEntity swimmingMount;
    private SurfaceSwimming() {}
    public static void apply(Input input) {
        var c=MinecraftClient.getInstance();
        boolean active=PlaySession.controlsPlayer()&&c.player!=null&&c.world!=null
            &&c.player.isAlive()&&!c.player.getAbilities().flying;
        var mount=active&&c.player.getVehicle() instanceof AbstractHorseEntity horse
            &&horse.isTouchingWater()?horse:null;
        if(swimmingMount!=null&&swimmingMount!=mount)swimmingMount.setJumping(false);
        swimmingMount=mount;
        if(mount!=null)return;
        if(!active||c.player.hasVehicle())return;
        // Only upward vanilla fluid movement. Do not add underwater routes or
        // horizontal travel, and do not synthesize a held keyboard binding.
        if(c.player.isTouchingWater()&&(!c.player.isOnGround()||c.player.isSubmergedIn(FluidTags.WATER)))
            input.jumping=true;
    }
    public static void beforeMountJump(LivingEntity entity) {
        if(entity!=swimmingMount)return;
        // Input ticks and vehicle physics run at different points. Recheck
        // fluid depth immediately before vanilla consumes jumping, so a stale
        // swimming intent cannot become a full horse jump after surfacing.
        var c=MinecraftClient.getInstance();
        entity.setJumping(PlaySession.controlsPlayer()&&c.player!=null
            &&c.player.getVehicle()==entity&&entity.isAlive()&&entity.isTouchingWater()
            &&entity.getFluidHeight(FluidTags.WATER)>entity.getSwimHeight());
    }
}
