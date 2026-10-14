package ai.musecraft.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read actual vanilla break progress without changing interaction ownership. */
@Mixin(ClientPlayerInteractionManager.class)
public interface MiningProgressAccessor {
    @Accessor("currentBreakingPos") BlockPos musecraftBreakingPos();
    @Accessor("currentBreakingProgress") float musecraftBreakingProgress();
}
