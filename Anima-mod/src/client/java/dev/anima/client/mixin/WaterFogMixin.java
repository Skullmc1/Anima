package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import dev.anima.core.AnimaWaterEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enhances underwater visibility and turquoise clarity without custom underwater shaders.
 */
@Mixin(WaterFogEnvironment.class)
public class WaterFogMixin {

    @Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
    private void anima$enhanceWaterFogColor(ClientLevel level, Camera camera, int renderDistanceChunks, float partialTick, CallbackInfoReturnable<Integer> cir) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.waterEnhancement) {
            return;
        }
        int vanilla = cir.getReturnValue();
        int enhanced = AnimaWaterEngine.enhanceBiomeWaterColor(vanilla & 0xFFFFFF, AnimaClientMod.atmosphere(), cfg);
        cir.setReturnValue((vanilla & 0xFF000000) | enhanced);
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private void anima$enhanceWaterClarity(FogData fogData, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();

        if (cfg.isActive() && cfg.waterEnhancement) {
            float clarityBoost = 1.0f + 0.45f * cfg.waterClarity;
            fogData.environmentalEnd *= clarityBoost;
            fogData.renderDistanceEnd *= clarityBoost;
        }

        float heldLum = (cfg.isActive() && cfg.dynamicHandheldLight)
                ? AnimaDynamicLightEngine.sampleSmoothPlayerLuminance()
                : 0f;
        fogData.renderDistanceEnd = (float) Math.floor(Math.max(16.0f, fogData.renderDistanceEnd)) + (heldLum / 16.0f) * 0.96f;

        float waterFlag = (cfg.isActive() && cfg.waterEnhancement) ? 0.5f : 0.0f;
        fogData.environmentalEnd = (float) Math.floor(Math.max(16.0f, fogData.environmentalEnd)) + waterFlag;
    }
}
