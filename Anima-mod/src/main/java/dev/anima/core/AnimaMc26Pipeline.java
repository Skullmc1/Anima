package dev.anima.core;

/**
 * Exact implementation of Minecraft 26.2's native rendering pipeline math:
 * - lightmap.fsh (get_brightness, parabolicMixFactor, notGamma, uniform mixing)
 * - AtmosphericFogEnvironment linear fog & color blending
 * - SkyRenderer sky disc + sunrise/sunset horizon glow
 *
 * Shared by BOTH Anima-mod's Minecraft 26.2 mixins AND Anima-renderer so that
 * rendered previews are an exact 1:1 representation of in-game Minecraft 26.2 visuals.
 */
public final class AnimaMc26Pipeline {

    private AnimaMc26Pipeline() {}

    /**
     * Mirror of Minecraft 26.2's LightmapInfo UBO / LightmapRenderState fields.
     */
    public static final class LightmapUniforms {
        public float skyFactor = 1.0f;
        public float blockFactor = 1.0f;
        public float brightnessFactor = 0.5f; // Minecraft default Brightness = 50%
        public final float[] skyLightColor = {1.0f, 1.0f, 1.0f};
        public final float[] ambientColor = {0.04f, 0.04f, 0.04f};
        public final float[] blockLightTint = {1.0f, 0.72f, 0.42f};
    }

    /**
     * Populates vanilla Minecraft 26.2 baseline LightmapInfo uniforms for a given time of day.
     */
    public static void fillVanillaLightmapUniforms(AnimaAtmosphere atm, LightmapUniforms u) {
        // Vanilla skyFactor drops to ~0.16 at midnight, 1.0 at midday
        u.skyFactor = ColorMath.lerp(0.16f, 1.0f, atm.dayFactor);
        u.blockFactor = 1.0f;
        u.brightnessFactor = 0.5f;

        // Vanilla skyLightColor is near-white during day, slightly cool bluish at night
        u.skyLightColor[0] = ColorMath.lerp(0.55f, 1.00f, atm.dayFactor);
        u.skyLightColor[1] = ColorMath.lerp(0.62f, 1.00f, atm.dayFactor);
        u.skyLightColor[2] = ColorMath.lerp(0.88f, 1.00f, atm.dayFactor);

        // Vanilla ambientColor is flat neutral dark gray
        u.ambientColor[0] = 0.04f;
        u.ambientColor[1] = 0.04f;
        u.ambientColor[2] = 0.04f;

        // Vanilla block light tint
        u.blockLightTint[0] = 1.00f;
        u.blockLightTint[1] = 0.74f;
        u.blockLightTint[2] = 0.48f;
    }

    /**
     * Applies Anima-mod's exact LightmapExtractorMixin modifications to Minecraft 26.2's LightmapInfo uniforms.
     */
    public static void applyAnimaLightmapUniforms(LightmapUniforms u, AnimaAtmosphere atm, AnimaConfig cfg, float elapsedSeconds) {
        if (!cfg.isActive() || !cfg.atmosphericLightmap) {
            return;
        }

        float warmth = ColorMath.clamp01(cfg.warmthStrength);

        // 1. Complementary-matched warm golden-white midday sunlight, rich sunset amber, luminous moonlit blue
        float skyR = ColorMath.lerp(0.62f, ColorMath.lerp(1.24f, 1.32f, atm.goldenHourFactor), atm.dayFactor);
        float skyG = ColorMath.lerp(0.80f, ColorMath.lerp(1.12f, 0.78f, atm.goldenHourFactor), atm.dayFactor);
        float skyB = ColorMath.lerp(1.26f, ColorMath.lerp(0.88f, 0.42f, atm.goldenHourFactor), atm.dayFactor);

        u.skyLightColor[0] = ColorMath.lerp(u.skyLightColor[0], skyR, 0.90f * warmth);
        u.skyLightColor[1] = ColorMath.lerp(u.skyLightColor[1], skyG, 0.90f * warmth);
        u.skyLightColor[2] = ColorMath.lerp(u.skyLightColor[2], skyB, 0.90f * warmth);

        // Luminous moonlit night readability matching Complementary Reimagined
        u.skyFactor = Math.max(u.skyFactor, 0.42f * atm.nightFactor);

        // 2. Cool atmospheric shadow fill (luminous sapphire-blue at night, clean sky-blue in shade)
        u.ambientColor[0] = ColorMath.lerp(0.11f, ColorMath.lerp(0.08f, 0.15f, atm.goldenHourFactor), atm.dayFactor);
        u.ambientColor[1] = ColorMath.lerp(0.17f, ColorMath.lerp(0.13f, 0.10f, atm.goldenHourFactor), atm.dayFactor);
        u.ambientColor[2] = ColorMath.lerp(0.34f, ColorMath.lerp(0.25f, 0.24f, atm.goldenHourFactor), atm.dayFactor);

        // 3. Shape vanilla gamma lift for deeper shadow contrast
        float contrastFactor = ColorMath.lerp(0.80f, 0.54f, cfg.shadowDepth);
        u.brightnessFactor = u.brightnessFactor * contrastFactor;

        // 4. Warm 2600K incandescent torch/lantern block light with cozy glow
        float flicker = 1.0f;
        if (cfg.torchFlicker > 0.001f) {
            flicker += (float) (Math.sin(elapsedSeconds * 7.3) * 0.025 + Math.sin(elapsedSeconds * 13.7) * 0.015) * cfg.torchFlicker;
        }
        u.blockLightTint[0] = 1.00f * flicker;
        u.blockLightTint[1] = 0.56f * flicker;
        u.blockLightTint[2] = 0.18f * flicker;
        u.blockFactor = ColorMath.clamp(u.blockFactor * (1.38f + 0.42f * atm.nightFactor), 0f, 1.95f);
    }

    /**
     * Exact port of Minecraft 26.2's assets/minecraft/shaders/core/lightmap.fsh!
     */
    public static void evaluateMc26Lightmap(int skyLight, int blockLight, LightmapUniforms u, float[] outRgb) {
        float blockLevel = ColorMath.clamp01(blockLight / 15.0f);
        float skyLevel = ColorMath.clamp01(skyLight / 15.0f);

        float blockBrightness = mcGetBrightness(blockLevel) * u.blockFactor;
        float skyBrightness = mcGetBrightness(skyLevel) * u.skyFactor;

        // Ambient + sky light
        float r = u.ambientColor[0] + u.skyLightColor[0] * skyBrightness;
        float g = u.ambientColor[1] + u.skyLightColor[1] * skyBrightness;
        float b = u.ambientColor[2] + u.skyLightColor[2] * skyBrightness;

        // Block light with Minecraft 26.2's parabolicMixFactor toward white at level 15
        float pmf = 0.9f * mcParabolicMixFactor(blockLevel);
        float br = ColorMath.lerp(u.blockLightTint[0], 1.0f, pmf);
        float bg = ColorMath.lerp(u.blockLightTint[1], 1.0f, pmf);
        float bb = ColorMath.lerp(u.blockLightTint[2], 1.0f, pmf);

        r = ColorMath.clamp01(r + br * blockBrightness);
        g = ColorMath.clamp01(g + bg * blockBrightness);
        b = ColorMath.clamp01(b + bb * blockBrightness);

        // Minecraft 26.2's exact notGamma() brightness curve
        float maxComp = Math.max(r, Math.max(g, b));
        if (maxComp > 1e-5f) {
            float maxInv = 1.0f - maxComp;
            float maxScaled = 1.0f - maxInv * maxInv * maxInv * maxInv;
            float scale = maxScaled / maxComp;
            float ngR = r * scale;
            float ngG = g * scale;
            float ngB = b * scale;
            r = ColorMath.lerp(r, ngR, u.brightnessFactor);
            g = ColorMath.lerp(g, ngG, u.brightnessFactor);
            b = ColorMath.lerp(b, ngB, u.brightnessFactor);
        }

        outRgb[0] = ColorMath.clamp01(r);
        outRgb[1] = ColorMath.clamp01(g);
        outRgb[2] = ColorMath.clamp01(b);
    }

    private static float mcGetBrightness(float level) {
        return level / (4.0f - 3.0f * level);
    }

    private static float mcParabolicMixFactor(float level) {
        float t = 2.0f * level - 1.0f;
        return t * t;
    }

    /**
     * Exact Minecraft 26.2 cardinal face shading (no raytraced sun shadows).
     */
    public static float cardinalShade(int nx, int ny, int nz) {
        if (ny > 0) return 1.0f;
        if (ny < 0) return 0.5f;
        if (nz != 0) return 0.8f;
        return 0.6f;
    }

    /**
     * Computes Minecraft 26.2's AtmosphericFogEnvironment base fog color (vanilla + Anima mixin).
     */
    public static void computeMc26FogColor(AnimaAtmosphere atm, AnimaConfig cfg, float[] outRgb) {
        // Vanilla Minecraft overworld fog color
        float[] vDay = {0.75f, 0.85f, 1.00f};
        float[] vNight = {0.02f, 0.02f, 0.05f};
        float[] vSunset = {0.78f, 0.48f, 0.36f};
        ColorMath.lerpRgb(vNight, vDay, atm.dayFactor, outRgb);
        ColorMath.lerpRgb(outRgb, vSunset, atm.goldenHourFactor * 0.45f, outRgb);

        if (cfg.isActive() && cfg.atmosphericFog) {
            float blend = ColorMath.clamp01(0.75f * cfg.fogDensity + 0.15f * atm.goldenHourFactor);
            ColorMath.lerpRgb(outRgb, atm.fogColor, blend, outRgb);
        }
    }

    /**
     * Computes Minecraft 26.2's linear fog factor using AtmosphericFogEnvironment + AtmosphericFogMixin start/end.
     */
    public static float computeMc26LinearFog(float dist, float renderDistance, AnimaAtmosphere atm, AnimaConfig cfg) {
        float fogStart = renderDistance * 0.75f;
        float fogEnd = renderDistance;

        if (cfg.isActive() && cfg.atmosphericFog) {
            float startMult = AnimaFogEngine.fogStartMultiplier(atm, cfg);
            fogStart = renderDistance * startMult;
            // Push fogEnd out so distant mountains stay crisp & tinted instead of whiteout (like Complementary!)
            fogEnd = renderDistance * ColorMath.lerp(1.38f, 1.18f, atm.goldenHourFactor);
        }
        return ColorMath.clamp01((dist - fogStart) / Math.max(1.0f, fogEnd - fogStart)) * ColorMath.lerp(0.68f, 0.80f, atm.goldenHourFactor);
    }

    /**
     * Computes Minecraft 26.2's SkyRenderer zenith sky disc color (vanilla + SkyRendererMixin).
     */
    public static void computeMc26SkyColor(AnimaAtmosphere atm, AnimaConfig cfg, float[] outRgb) {
        float[] vDaySky = {0.47f, 0.65f, 1.00f};
        float[] vNightSky = {0.01f, 0.01f, 0.03f};
        ColorMath.lerpRgb(vNightSky, vDaySky, atm.dayFactor, outRgb);

        if (cfg.isActive() && cfg.skyEnhancement) {
            ColorMath.lerpRgb(outRgb, atm.zenithColor, 0.84f, outRgb);
        }
    }
}
