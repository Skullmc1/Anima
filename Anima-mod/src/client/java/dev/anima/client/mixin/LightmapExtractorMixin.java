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

        // 1. Complementary-matched warm sunlight & luminous moonlit blue
        float skyR = ColorMath.lerp(0.62f, ColorMath.lerp(1.24f, 1.32f, atm.goldenHourFactor), atm.dayFactor);
        float skyG = ColorMath.lerp(0.80f, ColorMath.lerp(1.12f, 0.78f, atm.goldenHourFactor), atm.dayFactor);
        float skyB = ColorMath.lerp(1.26f, ColorMath.lerp(0.88f, 0.42f, atm.goldenHourFactor), atm.dayFactor);

        state.skyLightColor = new Vector3f(
                ColorMath.lerp(state.skyLightColor.x(), skyR, 0.90f * warmth),
                ColorMath.lerp(state.skyLightColor.y(), skyG, 0.90f * warmth),
                ColorMath.lerp(state.skyLightColor.z(), skyB, 0.90f * warmth)
        );
        state.skyFactor = Math.max(state.skyFactor, 0.42f * atm.nightFactor);

        // 2. Cool atmospheric shadow fill (luminous sapphire-blue at night, crisp sky-blue in shade)
        float ambR = ColorMath.lerp(0.11f, ColorMath.lerp(0.08f, 0.15f, atm.goldenHourFactor), atm.dayFactor);
        float ambG = ColorMath.lerp(0.17f, ColorMath.lerp(0.13f, 0.10f, atm.goldenHourFactor), atm.dayFactor);
        float ambB = ColorMath.lerp(0.34f, ColorMath.lerp(0.25f, 0.24f, atm.goldenHourFactor), atm.dayFactor);
        state.ambientColor = new Vector3f(ambR, ambG, ambB);

        // 3. Shape vanilla gamma lift for deeper shadow contrast
        float contrastFactor = ColorMath.lerp(0.80f, 0.54f, cfg.shadowDepth);
        state.brightness = state.brightness * contrastFactor;

        // 4. Warm 2600K incandescent torch/lantern block light with cozy glow
        float flicker = 1.0f;
        if (cfg.torchFlicker > 0.001f) {
            flicker += (float) (Math.sin(t * 7.3) * 0.025 + Math.sin(t * 13.7) * 0.015) * cfg.torchFlicker;
        }
        state.blockLightTint = new Vector3f(
                1.00f * flicker,
                0.56f * flicker,
                0.18f * flicker
        );
        state.blockFactor = ColorMath.clamp(state.blockFactor * (1.38f + 0.42f * atm.nightFactor), 0f, 1.95f);
        state.needsUpdate = true;
    }
}
