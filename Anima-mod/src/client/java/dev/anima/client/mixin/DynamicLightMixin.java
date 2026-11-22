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
 * Injects Anima's smooth sub-level (0..240) dynamic light directly into
 * Minecraft 26.2's LightCoordsUtil.getLightCoords so block light fades
 * continuously instead of stepping in coarse integer levels.
 */
@Mixin(LightCoordsUtil.class)
public class DynamicLightMixin {

    @Inject(
            method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void anima$applyDynamicHandheldLight(
            LightCoordsUtil.BrightnessGetter brightnessGetter,
            BlockAndLightGetter level,
            BlockState state,
            BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {

        if (!AnimaDynamicLightEngine.isActive()) {
            return;
        }
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.dynamicHandheldLight) {
            return;
        }

        int packed = cir.getReturnValue();
        int currentSmoothBlock = LightCoordsUtil.smoothBlock(packed);
        if (currentSmoothBlock < 240) {
            int dynamicSmooth = AnimaDynamicLightEngine.computeSmoothDynamicBlockLight(
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
