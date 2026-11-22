package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects dynamic light from DROPPED luminous items into LightCoordsUtil.getLightCoords.
 * Player handheld light is applied per-frame on the GPU in terrain.vsh for zero lag!
 */
@Mixin(LightCoordsUtil.class)
public class DynamicLightMixin {

    @Inject(
            method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void anima$applyDroppedItemLight(
            LightCoordsUtil.BrightnessGetter brightnessGetter,
            BlockAndLightGetter level,
            BlockState state,
            BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {

        if (!AnimaDynamicLightEngine.hasDroppedSources()) {
            return;
        }
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.dynamicHandheldLight) {
            return;
        }

        int packed = cir.getReturnValue();
        int currentSmoothBlock = LightCoordsUtil.smoothBlock(packed);
        if (currentSmoothBlock < 240) {
            int dynamicSmooth = AnimaDynamicLightEngine.computeSmoothDroppedItemLight(
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5
            );
            if (dynamicSmooth > currentSmoothBlock) {
                cir.setReturnValue(LightCoordsUtil.smoothPack(dynamicSmooth, LightCoordsUtil.smoothSky(packed)));
            }
        }
    }
}
