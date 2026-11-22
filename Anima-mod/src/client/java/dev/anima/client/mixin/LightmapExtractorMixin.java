package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.ColorMath;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects Anima's shader-free atmospheric lightmap directly into Minecraft 26.2's
 * LightmapRenderState: warm golden-white sunlight, deep cool-blue atmospheric shadow
 * contrast, silver-blue moonlight, and rich incandescent 2600K torchlight.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapExtractorMixin {

    @Inject(method = "extract", at = @At("TAIL"))
    private void anima$applyAtmosphericLightmap(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.atmosphericLightmap) {
            return;
        }

        AnimaAtmosphere atm = AnimaClientMod.atmosphere();
        float t = AnimaClientMod.elapsedSeconds();

        // 1. Rich warm sunlight at midday, blazing amber-gold at golden hour, cool silver-blue at night
        float warmth = cfg.warmthStrength;
        float skyR = ColorMath.lerp(0.46f, ColorMath.lerp(1.14f, 1.24f, atm.goldenHourFactor), atm.dayFactor);
        float skyG = ColorMath.lerp(0.64f, ColorMath.lerp(1.03f, 0.72f, atm.goldenHourFactor), atm.dayFactor);
        float skyB = ColorMath.lerp(1.04f, ColorMath.lerp(0.86f, 0.36f, atm.goldenHourFactor), atm.dayFactor);

        state.skyLightColor = new Vector3f(
                ColorMath.lerp(state.skyLightColor.x(), skyR, 0.85f * ColorMath.clamp01(warmth)),
                ColorMath.lerp(state.skyLightColor.y(), skyG, 0.85f * ColorMath.clamp01(warmth)),
                ColorMath.lerp(state.skyLightColor.z(), skyB, 0.85f * ColorMath.clamp01(warmth))
        );

        // 2. Deep cool atmospheric shadow fill (replaces vanilla's flat washed-out gray)
        float ambR = ColorMath.lerp(0.05f, ColorMath.lerp(0.05f, 0.14f, atm.goldenHourFactor), atm.dayFactor);
        float ambG = ColorMath.lerp(0.09f, ColorMath.lerp(0.10f, 0.08f, atm.goldenHourFactor), atm.dayFactor);
        float ambB = ColorMath.lerp(0.22f, ColorMath.lerp(0.21f, 0.22f, atm.goldenHourFactor), atm.dayFactor);
        state.ambientColor = new Vector3f(ambR, ambG, ambB);

        // 3. Shape vanilla gamma lift so crevices & shadows keep cinematic depth
        float contrastFactor = ColorMath.lerp(0.82f, 0.48f, cfg.shadowDepth);
        state.brightness = state.brightness * contrastFactor;

        // 4. Warm 2600K incandescent torch/lantern block light with gentle organic flicker
        float flicker = 1.0f;
        if (cfg.torchFlicker > 0.001f) {
            flicker += (float) (Math.sin(t * 7.3) * 0.025 + Math.sin(t * 13.7) * 0.015) * cfg.torchFlicker;
        }
        state.blockLightTint = new Vector3f(
                1.00f * flicker,
                0.50f * flicker,
                0.13f * flicker
        );
        state.blockFactor = ColorMath.clamp(state.blockFactor * (1.28f + 0.30f * atm.nightFactor), 0f, 1.85f);
        state.needsUpdate = true;
    }
}
