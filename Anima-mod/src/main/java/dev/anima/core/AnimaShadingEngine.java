package dev.anima.core;

/**
 * Enhanced directional block-face shading, ambient occlusion shaping, and
 * sunlit foliage vibrancy (subsurface translucency approximation).
 */
public final class AnimaShadingEngine {

    private AnimaShadingEngine() {}

    /**
     * Enhances Minecraft's biome grass & foliage color to look lush and natural
     * rather than flat/yellow-green.
     */
    public static int enhanceFoliageColor(int vanillaRgb, AnimaAtmosphere atm, AnimaConfig config) {
        if (!config.isActive() || !config.foliageEnhancement) {
            return vanillaRgb;
        }
        float[] rgb = new float[3];
        ColorMath.unpackRgb(vanillaRgb, rgb);

        // Deepen greens slightly and warm sunlit highlights during golden hour
        rgb[0] = ColorMath.clamp01(rgb[0] * (0.94f + 0.12f * atm.goldenHourFactor));
        rgb[1] = ColorMath.clamp01(rgb[1] * 1.06f);
        rgb[2] = ColorMath.clamp01(rgb[2] * 0.90f);
        ColorMath.adjustSaturation(rgb, config.saturationBoost);

        return ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * Computes directional face shading tint (RGB multiplier) for a block face normal (nx, ny, nz).
     * In vanilla, faces only get flat scalar multipliers (1.0 top, 0.8 N/S, 0.6 E/W, 0.5 bottom).
     * Anima adds warm sun-facing highlights and cool sky-bounce on shadow-facing sides!
     */
    public static void computeDirectionalShade(
            float nx, float ny, float nz,
            boolean isFoliage,
            boolean inSunShadow,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outRgb) {

        if (!config.isActive() || !config.directionalShading) {
            // Standard vanilla Minecraft face shading
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

        // Subtle base face separation so voxel geometry stays crisp even in shadow
        float baseGeom = ny > 0.5f ? 0.96f : (ny < -0.5f ? 0.56f : (Math.abs(nz) > 0.5f ? 0.82f : 0.72f));

        // Cool sky bounce on non-sunlit faces
        float shadowMix = (1.0f - sunHit) * (0.45f + 0.35f * config.shadowDepth);
        float sunBoost = sunHit * (0.34f + 0.28f * atm.goldenHourFactor) * atm.dayFactor * config.warmthStrength;

        outRgb[0] = baseGeom * ColorMath.lerp(1.0f, atm.shadowTintColor[0], shadowMix * 0.45f) + atm.sunColor[0] * sunBoost;
        outRgb[1] = baseGeom * ColorMath.lerp(1.0f, atm.shadowTintColor[1], shadowMix * 0.45f) + atm.sunColor[1] * sunBoost;
        outRgb[2] = baseGeom * ColorMath.lerp(1.0f, atm.shadowTintColor[2], shadowMix * 0.45f) + atm.sunColor[2] * sunBoost;

        // Subsurface-like warm glow on sunlit foliage & grass
        if (isFoliage && config.foliageEnhancement && !inSunShadow && atm.dayFactor > 0.1f) {
            float sss = (0.14f + 0.20f * atm.goldenHourFactor) * (0.4f + 0.6f * Math.max(0f, ndotl));
            outRgb[0] += sss * 0.75f;
            outRgb[1] += sss * 0.95f;
            outRgb[2] += sss * 0.22f;
        }

        // Gentle shadow darkening when occluded from direct sun
        if (inSunShadow && atm.dayFactor > 0.1f) {
            float shadowDarken = 1.0f - 0.32f * config.shadowDepth * atm.dayFactor;
            outRgb[0] *= shadowDarken * 0.94f;
            outRgb[1] *= shadowDarken * 0.97f;
            outRgb[2] *= shadowDarken * 1.06f; // subtle cool sky bounce in cast shadows
        }
    }

    /**
     * Shapes Minecraft's 0..1 vertex ambient occlusion value for deeper, crisper block crevices.
     */
    public static float shapeAmbientOcclusion(float vanillaAo, AnimaConfig config) {
        if (!config.isActive() || !config.directionalShading) {
            return vanillaAo;
        }
        return (float) Math.pow(ColorMath.clamp01(vanillaAo), 1.0f + 0.55f * config.shadowDepth);
    }
}
