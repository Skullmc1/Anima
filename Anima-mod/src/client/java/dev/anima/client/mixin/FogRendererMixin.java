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
 * Encodes Anima's per-frame smooth handheld light luminance (0..15) and water-wave flag
 * into FogData AFTER FogRenderer.setupFog finishes overwriting renderDistanceStart/End.
 *
 * Guarantees both Vanilla (terrain.vsh) and Sodium (block_layer_opaque.vsh) receive
 * real-time 60/120/240 FPS handheld dynamic lighting with zero lag and zero chunk rebuilds!
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

        float waterFlag = (cfg.isActive() && cfg.waterEnhancement) ? 0.5f : 0.0f;
        fogData.environmentalEnd = (float) Math.floor(Math.max(16.0f, fogData.environmentalEnd)) + waterFlag;
    }
}
