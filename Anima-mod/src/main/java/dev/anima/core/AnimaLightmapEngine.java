package dev.anima.core;

/**
 * Generates Anima's 16x16 atmospheric lightmap (skyLight 0..15 x blockLight 0..15).
 * Replaces Minecraft's flat gray-white lightmap with warm sun highlights, cool atmospheric
 * shadow fill, silver-blue moonlight, and cozy incandescent torchlight — at virtually zero GPU cost.
 */
public final class AnimaLightmapEngine {

    private AnimaLightmapEngine() {}

    /**
     * Computes the final RGB lighting multiplier for a given (skyLight, blockLight) pair.
     *
     * @param skyLight    0..15 Minecraft sky light level
     * @param blockLight  0..15 Minecraft block light level (torches, lanterns, lava)
     * @param atmosphere  Current time-of-day atmospheric state
     * @param config      Active Anima configuration
     * @param timeSeconds Elapsed time in seconds (for subtle torch warmth flicker)
     * @param outRgb      Output float[3] RGB multiplier in 0..1+
     */
    public static void computeLight(
            int skyLight,
            int blockLight,
            AnimaAtmosphere atmosphere,
            AnimaConfig config,
            float timeSeconds,
            float[] outRgb) {

        float sky = ColorMath.clamp01(skyLight / 15f);
        float block = ColorMath.clamp01(blockLight / 15f);

        if (!config.isActive() || !config.atmosphericLightmap) {
            computeVanillaLight(sky, block, atmosphere, outRgb);
            return;
        }

        // Non-linear perceptual response curve: deeper crevices, crisper sunlit areas
        float skyCurve = (float) Math.pow(sky, 1.0f + 0.45f * config.shadowDepth);
        float blockCurve = (float) Math.pow(block, 1.35f);

        // Subtle organic torch flicker
        float flicker = 1.0f;
        if (config.torchFlicker > 0.001f && block > 0.01f) {
            float wave = (float) (Math.sin(timeSeconds * 7.3) * 0.5 + Math.sin(timeSeconds * 13.7) * 0.35 + Math.sin(timeSeconds * 23.1) * 0.15);
            flicker = 1.0f + wave * 0.035f * config.torchFlicker;
        }

        // Sky contribution blends between cool shadow tint (low skyLight) and warm sunlight (high skyLight)
        float sunMix = ColorMath.smoothstep(0.25f, 0.95f, sky);
        float skyStrength = ColorMath.lerp(0.22f, 1.08f, atmosphere.dayFactor);
        // Preserve readable moonlit night
        skyStrength = Math.max(skyStrength, 0.24f * atmosphere.nightFactor);

        float sr = ColorMath.lerp(atmosphere.shadowTintColor[0], atmosphere.sunColor[0], sunMix * 0.72f * config.warmthStrength);
        float sg = ColorMath.lerp(atmosphere.shadowTintColor[1], atmosphere.sunColor[1], sunMix * 0.72f * config.warmthStrength);
        float sb = ColorMath.lerp(atmosphere.shadowTintColor[2], atmosphere.sunColor[2], sunMix * 0.72f * config.warmthStrength);

        float skyR = sr * skyCurve * skyStrength;
        float skyG = sg * skyCurve * skyStrength;
        float skyB = sb * skyCurve * skyStrength;

        // Ambient bounce floor so caves and deep shade keep subtle cool-indigo readability
        float ambientFloor = ColorMath.lerp(0.055f, 0.095f, atmosphere.dayFactor) * (1.0f - 0.25f * config.shadowDepth);
        skyR += atmosphere.shadowTintColor[0] * ambientFloor;
        skyG += atmosphere.shadowTintColor[1] * ambientFloor;
        skyB += atmosphere.shadowTintColor[2] * ambientFloor;

        // Warm incandescent block light (torch/lantern) with glowing hot core at blockLight 13..15
        float hotCore = ColorMath.smoothstep(0.75f, 1.0f, block) * 0.28f;
        float blockIntensity = blockCurve * 1.18f * flicker;
        float blkR = (atmosphere.torchColor[0] + hotCore) * blockIntensity;
        float blkG = (atmosphere.torchColor[1] + hotCore * 0.85f) * blockIntensity;
        float blkB = (atmosphere.torchColor[2] + hotCore * 0.65f) * blockIntensity;

        // Screen-like additive blend prevents harsh clipping when torch meets sunlight
        outRgb[0] = 1f - (1f - ColorMath.clamp01(skyR)) * (1f - ColorMath.clamp01(blkR));
        outRgb[1] = 1f - (1f - ColorMath.clamp01(skyG)) * (1f - ColorMath.clamp01(blkG));
        outRgb[2] = 1f - (1f - ColorMath.clamp01(skyB)) * (1f - ColorMath.clamp01(blkB));

        // Gentle filmic tone polish
        ColorMath.applyFilmicPolish(outRgb, config.filmicStrength * 0.45f);
        ColorMath.adjustSaturation(outRgb, 1.0f + (config.saturationBoost - 1.0f) * 0.55f);
    }

    /**
     * Faithful approximation of vanilla Minecraft's flat, slightly washed-out lightmap.
     */
    public static void computeVanillaLight(float sky, float block, AnimaAtmosphere atmosphere, float[] outRgb) {
        float skyBright = ColorMath.lerp(0.18f, 1.0f, atmosphere.dayFactor);
        float s = (float) Math.pow(sky, 1.08f) * skyBright;
        float b = (float) Math.pow(block, 1.25f);

        float r = Math.max(s, b * 1.00f);
        float g = Math.max(s, b * 0.86f);
        float bl = Math.max(s * ColorMath.lerp(1.06f, 1.0f, atmosphere.dayFactor), b * 0.68f);

        // Vanilla minimum ambient brightness
        outRgb[0] = ColorMath.clamp01(r * 0.92f + 0.08f);
        outRgb[1] = ColorMath.clamp01(g * 0.92f + 0.08f);
        outRgb[2] = ColorMath.clamp01(bl * 0.92f + 0.08f);
    }

    /**
     * Fills an entire 16x16 ARGB lightmap buffer (row-major: blockLight + skyLight * 16).
     */
    public static void fillLightmapTable(int[] argb256, AnimaAtmosphere atmosphere, AnimaConfig config, float timeSeconds) {
        float[] tmp = new float[3];
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                computeLight(sky, block, atmosphere, config, timeSeconds, tmp);
                argb256[sky * 16 + block] = ColorMath.packArgb(1.0f, tmp[0], tmp[1], tmp[2]);
            }
        }
    }
}
