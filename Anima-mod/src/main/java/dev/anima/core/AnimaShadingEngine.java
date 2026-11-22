package dev.anima.core;

/**
 * Enhanced directional block-face shading, ambient occlusion shaping, and
 * sunlit foliage vibrancy (subsurface translucency approximation).
 */
public final class AnimaShadingEngine {

    private AnimaShadingEngine() {}

    /**
     * Enhances Minecraft's biome grass & foliage color to look lush, deep, and natural
     * (rich meadow emerald-olive rather than flat yellow-green or neon lime).
     */
    public static int enhanceFoliageColor(int vanillaRgb, AnimaAtmosphere atm, AnimaConfig config) {
        if (!config.isActive() || !config.foliageEnhancement) {
            return vanillaRgb;
        }
        float[] rgb = new float[3];
        ColorMath.unpackRgb(vanillaRgb, rgb);

        // Rich natural meadow green: slightly deeper value, warm golden shift at sunset
        rgb[0] = ColorMath.clamp01(rgb[0] * (0.86f + 0.14f * atm.goldenHourFactor));
        rgb[1] = ColorMath.clamp01(rgb[1] * 0.95f);
        rgb[2] = ColorMath.clamp01(rgb[2] * 0.80f);
        ColorMath.adjustSaturation(rgb, 1.0f + (config.saturationBoost - 1.0f) * 0.70f);

        return ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * Computes directional face shading tint (RGB multiplier) for a block face normal (nx, ny, nz).
     */
    public static void computeDirectionalShade(
            float nx, float ny, float nz,
            boolean isFoliage,
            boolean inSunShadow,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outRgb) {

        if (!config.isActive() || !config.directionalShading) {
            float shade;
            if (ny > 0.5f) shade = 1.0f;
            else if (ny < -0.5f) shade = 0.5f;
            else if (Math.abs(nz) > 0.5f) shade = 0.8f;
            else shade = 0.6f;
            outRgb[0] = shade;
            outRgb[1] = shade;
            outRgb[2] = shade;
            return;
        }

        float ndotl = nx * atm.sunDirX + ny * atm.sunDirY + nz * atm.sunDirZ;
        float sunHit = inSunShadow ? 0.0f : ColorMath.clamp01(ndotl);

        // Crisp voxel face separation
        float baseGeom = ny > 0.5f ? 0.92f : (ny < -0.5f ? 0.52f : (Math.abs(nz) > 0.5f ? 0.80f : 0.70f));

        // Cool sky bounce on non-sunlit faces
        float shadowMix = (1.0f - sunHit) * (0.50f + 0.35f * config.shadowDepth);
        float sunBoost = sunHit * (0.28f + 0.32f * atm.goldenHourFactor) * atm.dayFactor * config.warmthStrength;

        outRgb[0] = baseGeom * ColorMath.lerp(1.0f, atm.shadowTintColor[0], shadowMix * 0.45f) + atm.sunColor[0] * sunBoost;
        outRgb[1] = baseGeom * ColorMath.lerp(1.0f, atm.shadowTintColor[1], shadowMix * 0.45f) + atm.sunColor[1] * sunBoost;
        outRgb[2] = baseGeom * ColorMath.lerp(1.0f, atm.shadowTintColor[2], shadowMix * 0.45f) + atm.sunColor[2] * sunBoost;

        // Subtle warm subsurface glow on sunlit leaves & grass (stronger at golden hour)
        if (isFoliage && config.foliageEnhancement && !inSunShadow && atm.dayFactor > 0.1f) {
            float sss = (0.07f + 0.16f * atm.goldenHourFactor) * (0.35f + 0.65f * Math.max(0f, ndotl));
            outRgb[0] += sss * 0.72f;
            outRgb[1] += sss * 0.88f;
            outRgb[2] += sss * 0.18f;
        }

        // Crisp cool-tinted cast shadows under trees and cliffs
        if (inSunShadow && atm.dayFactor > 0.1f) {
            float shadowDarken = 1.0f - 0.38f * config.shadowDepth * atm.dayFactor;
            outRgb[0] *= shadowDarken * 0.90f;
            outRgb[1] *= shadowDarken * 0.95f;
            outRgb[2] *= shadowDarken * 1.12f;
        }
    }

    public static float shapeAmbientOcclusion(float vanillaAo, AnimaConfig config) {
        if (!config.isActive() || !config.directionalShading) {
            return vanillaAo;
        }
        return (float) Math.pow(ColorMath.clamp01(vanillaAo), 1.0f + 0.60f * config.shadowDepth);
    }
}
