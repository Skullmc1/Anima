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
 * Injects Anima Lush's Complementary-inspired atmospheric lightmap into Minecraft 26.2:
 * warm golden-white midday sunlight, rich sunset amber, luminous midnight-blue moonlight,
 * cool atmospheric shadows, and warm 2600K incandescent torchlight.
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
        float warmth = ColorMath.clamp01(cfg.warmthStrength);

        // 1. Complementary-inspired warm sunlight & luminous moonlight
        float skyR = ColorMath.lerp(0.56f, ColorMath.lerp(1.22f, 1.28f, atm.goldenHourFactor), atm.dayFactor);
        float skyG = ColorMath.lerp(0.74f, ColorMath.lerp(1.10f, 0.72f, atm.goldenHourFactor), atm.dayFactor);
        float skyB = ColorMath.lerp(1.18f, ColorMath.lerp(0.88f, 0.36f, atm.goldenHourFactor), atm.dayFactor);

        state.skyLightColor = new Vector3f(
                ColorMath.lerp(state.skyLightColor.x(), skyR, 0.88f * warmth),
                ColorMath.lerp(state.skyLightColor.y(), skyG, 0.88f * warmth),
                ColorMath.lerp(state.skyLightColor.z(), skyB, 0.88f * warmth)
        );
        state.skyFactor = Math.max(state.skyFactor, 0.28f * atm.nightFactor);

        // 2. Cool atmospheric shadow fill (luminous blue-indigo at night, crisp sky-blue in shade)
        float ambR = ColorMath.lerp(0.08f, ColorMath.lerp(0.06f, 0.14f, atm.goldenHourFactor), atm.dayFactor);
        float ambG = ColorMath.lerp(0.13f, ColorMath.lerp(0.11f, 0.09f, atm.goldenHourFactor), atm.dayFactor);
        float ambB = ColorMath.lerp(0.27f, ColorMath.lerp(0.23f, 0.23f, atm.goldenHourFactor), atm.dayFactor);
        state.ambientColor = new Vector3f(ambR, ambG, ambB);

        // 3. Shape vanilla gamma lift for deeper shadow contrast
        float contrastFactor = ColorMath.lerp(0.80f, 0.50f, cfg.shadowDepth);
        state.brightness = state.brightness * contrastFactor;

        // 4. Warm 2600K incandescent torch/lantern block light with subtle organic flicker
        float flicker = 1.0f;
        if (cfg.torchFlicker > 0.001f) {
            flicker += (float) (Math.sin(t * 7.3) * 0.025 + Math.sin(t * 13.7) * 0.015) * cfg.torchFlicker;
        }
        state.blockLightTint = new Vector3f(
                1.00f * flicker,
                0.52f * flicker,
                0.14f * flicker
        );
        state.blockFactor = ColorMath.clamp(state.blockFactor * (1.32f + 0.36f * atm.nightFactor), 0f, 1.90f);
        state.needsUpdate = true;
    }
}
