package dev.anima.core;

/**
 * Aerial perspective distance fog, valley mist, and sun in-scattering haze.
 * Creates deep atmospheric depth (like shaders) using Minecraft's native fog parameters.
 */
public final class AnimaFogEngine {

    private AnimaFogEngine() {}

    /**
     * Computes atmospheric fog color for a view ray (dx, dy, dz).
     * Adds subtle sun-facing golden in-scattering so distant hills glow warmly toward the sun.
     */
    public static void computeFogColor(
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outRgb) {

        if (!config.isActive() || !config.atmosphericFog) {
            float[] vFog = {0.75f * atm.dayFactor + 0.02f, 0.85f * atm.dayFactor + 0.02f, 1.00f * atm.dayFactor + 0.04f};
            outRgb[0] = vFog[0];
            outRgb[1] = vFog[1];
            outRgb[2] = vFog[2];
            return;
        }

        outRgb[0] = atm.fogColor[0];
        outRgb[1] = atm.fogColor[1];
        outRgb[2] = atm.fogColor[2];

        // Forward Mie in-scattering towards the sun
        float sunDot = Math.max(0f, dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ);
        float scatter = (float) Math.pow(sunDot, 5.0) * (0.22f + 0.48f * atm.goldenHourFactor) * atm.dayFactor * (1f - atm.rainFactor * 0.7f);
        outRgb[0] = ColorMath.clamp01(outRgb[0] + atm.sunColor[0] * scatter);
        outRgb[1] = ColorMath.clamp01(outRgb[1] + atm.sunColor[1] * scatter);
        outRgb[2] = ColorMath.clamp01(outRgb[2] + atm.sunColor[2] * scatter);
    }

    /**
     * Computes aerial perspective fog factor in 0..1 for a world point at distance `dist` and height `worldY`.
     */
    public static float computeFogFactor(float dist, float worldY, float waterLevelY, float renderDistance, AnimaAtmosphere atm, AnimaConfig config) {
        if (!config.isActive() || !config.atmosphericFog) {
            // Vanilla fog starts very far (80% of render distance)
            return ColorMath.smoothstep(renderDistance * 0.75f, renderDistance * 1.05f, dist);
        }

        // Smooth exponential-squared aerial perspective starting gently in mid-distance
        float normDist = dist / Math.max(16f, renderDistance);
        float density = (0.85f + 0.55f * config.fogDensity + 0.45f * atm.goldenHourFactor + 0.5f * atm.rainFactor);
        float aerial = 1.0f - (float) Math.exp(-Math.pow(normDist * density, 1.65));

        // Gentle low-altitude mist near water level / valleys
        float heightAboveWater = Math.max(0f, worldY - waterLevelY);
        float valleyMist = (float) Math.exp(-heightAboveWater * 0.14f) * ColorMath.smoothstep(10f, 48f, dist) * (0.14f + 0.16f * atm.goldenHourFactor) * config.fogDensity;

        return ColorMath.clamp01(aerial * 0.78f + valleyMist);
    }

    /**
     * Returns recommended fogStart multiplier relative to render distance.
     * Keeps midday hills crisp like Complementary Reimagined while adding lush haze at golden hour.
     */
    public static float fogStartMultiplier(AnimaAtmosphere atm, AnimaConfig config) {
        if (!config.isActive() || !config.atmosphericFog) return 0.75f;
        float middayStart = 0.70f;
        float goldenStart = 0.44f;
        return ColorMath.clamp(ColorMath.lerp(middayStart, goldenStart, atm.goldenHourFactor), 0.36f, 0.78f);
    }
}
