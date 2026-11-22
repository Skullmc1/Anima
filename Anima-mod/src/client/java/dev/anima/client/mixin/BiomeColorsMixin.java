package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaShadingEngine;
import dev.anima.core.AnimaWaterEngine;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enhances biome grass, foliage, and water tinting using Anima's shader-free color engines.
 */
@Mixin(BiomeColors.class)
public class BiomeColorsMixin {

    @Inject(method = "getAverageGrassColor", at = @At("RETURN"), cancellable = true)
    private static void anima$enhanceGrass(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        AnimaConfig cfg = AnimaMod.config();
        if (cfg.isActive() && cfg.foliageEnhancement) {
            cir.setReturnValue(AnimaShadingEngine.enhanceFoliageColor(cir.getReturnValue(), AnimaClientMod.atmosphere(), cfg));
        }
    }

    @Inject(method = "getAverageFoliageColor", at = @At("RETURN"), cancellable = true)
    private static void anima$enhanceFoliage(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        AnimaConfig cfg = AnimaMod.config();
        if (cfg.isActive() && cfg.foliageEnhancement) {
            cir.setReturnValue(AnimaShadingEngine.enhanceFoliageColor(cir.getReturnValue(), AnimaClientMod.atmosphere(), cfg));
        }
    }

    @Inject(method = "getAverageWaterColor", at = @At("RETURN"), cancellable = true)
    private static void anima$enhanceWater(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        AnimaConfig cfg = AnimaMod.config();
        if (cfg.isActive() && cfg.waterEnhancement) {
            cir.setReturnValue(AnimaWaterEngine.enhanceBiomeWaterColor(cir.getReturnValue(), AnimaClientMod.atmosphere(), cfg));
        }
    }
}
