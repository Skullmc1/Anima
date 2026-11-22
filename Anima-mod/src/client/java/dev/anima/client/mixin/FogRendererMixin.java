package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Encodes Anima's per-frame smooth handheld light luminance (0..15) in renderDistanceEnd
 * and active visual mode (0.0 = Off, 0.35 = Lush, 0.75 = Borderlands) in environmentalEnd
 * AFTER FogRenderer.setupFog finishes overwriting fog distances.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void anima$encodeGpuUniforms(
            Camera camera,
            int renderDistanceChunks,
            DeltaTracker deltaTracker,
            float darkenWorldAmount,
            ClientLevel level,
            CallbackInfoReturnable<FogData> cir) {

        FogData fogData = cir.getReturnValue();
        if (fogData == null) {
            return;
        }
        AnimaConfig cfg = AnimaMod.config();

        float heldLum = (cfg.isActive() && cfg.dynamicHandheldLight)
                ? AnimaDynamicLightEngine.sampleSmoothPlayerLuminance()
                : 0f;
        fogData.renderDistanceEnd = (float) Math.floor(Math.max(16.0f, fogData.renderDistanceEnd)) + (heldLum / 16.0f) * 0.96f;

        float modeFrac = cfg.gpuModeFraction();
        fogData.environmentalEnd = (float) Math.floor(Math.max(16.0f, fogData.environmentalEnd)) + modeFrac;
    }
}
