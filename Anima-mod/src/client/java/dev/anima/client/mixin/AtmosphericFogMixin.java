package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import dev.anima.core.AnimaFogEngine;
import dev.anima.core.ColorMath;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enhances Minecraft 26.2's atmospheric fog with aerial perspective depth and
 * warm golden-hour horizon blending.
 */
@Mixin(AtmosphericFogEnvironment.class)
public class AtmosphericFogMixin {

    @Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
    private void anima$enhanceFogColor(ClientLevel level, Camera camera, int renderDistanceChunks, float partialTick, CallbackInfoReturnable<Integer> cir) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.atmosphericFog) {
            return;
        }
        AnimaAtmosphere atm = AnimaClientMod.atmosphere();
        int vanillaColor = cir.getReturnValue();
        float[] vRgb = new float[3];
        ColorMath.unpackRgb(vanillaColor, vRgb);

        float blend = ColorMath.clamp01(0.75f * cfg.fogDensity + 0.15f * atm.goldenHourFactor);
        float r = ColorMath.lerp(vRgb[0], atm.fogColor[0], blend);
        float g = ColorMath.lerp(vRgb[1], atm.fogColor[1], blend);
        float b = ColorMath.lerp(vRgb[2], atm.fogColor[2], blend);
        cir.setReturnValue((vanillaColor & 0xFF000000) | ColorMath.packRgb(r, g, b));
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private void anima$setupAerialPerspective(FogData fogData, Camera camera, ClientLevel level, float renderDistance, DeltaTracker deltaTracker, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();

        if (cfg.isActive() && cfg.atmosphericFog) {
            AnimaAtmosphere atm = AnimaClientMod.atmosphere();
            float startMult = AnimaFogEngine.fogStartMultiplier(atm, cfg);
            fogData.renderDistanceStart = Math.min(fogData.renderDistanceStart, renderDistance * startMult);
            fogData.environmentalStart = Math.min(fogData.environmentalStart, renderDistance * (startMult * 0.85f));
            fogData.environmentalEnd = Math.min(fogData.environmentalEnd, renderDistance * (1.05f - 0.15f * atm.goldenHourFactor));
        }

        // Encode per-frame smooth handheld light luminance (0..15) in renderDistanceEnd's fractional part
        // AND Anima water wave toggle in environmentalEnd's fractional part.
        // Works identically in BOTH Vanilla (FogRenderDistanceEnd / FogEnvironmentalEnd) AND Sodium (u_RenderFog.y / u_EnvironmentFog.y)!
        float heldLum = (cfg.isActive() && cfg.dynamicHandheldLight)
                ? AnimaDynamicLightEngine.sampleSmoothPlayerLuminance()
                : 0f;
        fogData.renderDistanceEnd = (float) Math.floor(Math.max(16.0f, fogData.renderDistanceEnd)) + (heldLum / 16.0f) * 0.96f;

        float waterFlag = (cfg.isActive() && cfg.waterEnhancement) ? 0.5f : 0.0f;
        fogData.environmentalEnd = (float) Math.floor(Math.max(16.0f, fogData.environmentalEnd)) + waterFlag;
    }
}
