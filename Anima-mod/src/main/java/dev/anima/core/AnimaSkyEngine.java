package dev.anima.core;

/**
 * Multi-layer atmospheric sky dome, celestial halo, and cloud lighting engine.
 * Replaces vanilla's flat single-color sky box with Rayleigh/Mie-inspired gradients
 * that cost negligible CPU/GPU time.
 */
public final class AnimaSkyEngine {

    private AnimaSkyEngine() {}

    /**
     * Samples the sky color along normalized view ray (dx, dy, dz).
     */
    public static void sampleSky(
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outRgb) {

        float elev = ColorMath.clamp01(dy);

        if (!config.isActive() || !config.skyEnhancement) {
            sampleVanillaSky(dx, dy, dz, atm, outRgb);
            return;
        }

        // Smooth multi-stop zenith -> mid-sky -> horizon gradient
        float horizonBlend = (float) Math.pow(1.0f - elev, 2.35f);
        ColorMath.lerpRgb(atm.zenithColor, atm.horizonColor, horizonBlend, outRgb);

        // Mie forward-scattering warm glow around the sun
        float sunDot = dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ;
        if (atm.dayFactor > 0.02f) {
            float wideGlow = (float) Math.pow(Math.max(0f, sunDot), 4.0) * (0.28f + 0.55f * atm.goldenHourFactor);
            float tightHalo = (float) Math.pow(Math.max(0f, sunDot), 36.0) * (0.55f + 0.45f * atm.goldenHourFactor);
            float sunDisc = ColorMath.smoothstep(0.9965f, 0.9985f, sunDot) * 1.45f;

            float haloScale = (1f - atm.rainFactor * 0.85f) * atm.dayFactor;
            outRgb[0] += (atm.sunColor[0] * wideGlow + 1.00f * tightHalo + 1.00f * sunDisc) * haloScale;
            outRgb[1] += (atm.sunColor[1] * wideGlow + 0.92f * tightHalo + 0.97f * sunDisc) * haloScale;
            outRgb[2] += (atm.sunColor[2] * wideGlow + 0.72f * tightHalo + 0.88f * sunDisc) * haloScale;
        }

        // Crisp moon + cool silver halo at night
        if (atm.nightFactor > 0.05f) {
            float moonDot = -(dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ);
            float moonHalo = (float) Math.pow(Math.max(0f, moonDot), 28.0) * 0.35f * atm.nightFactor;
            float moonDisc = ColorMath.smoothstep(0.9972f, 0.9988f, moonDot) * 1.15f * atm.nightFactor;
            outRgb[0] += (0.55f * moonHalo + 0.88f * moonDisc) * (1f - atm.rainFactor * 0.8f);
            outRgb[1] += (0.70f * moonHalo + 0.93f * moonDisc) * (1f - atm.rainFactor * 0.8f);
            outRgb[2] += (0.98f * moonHalo + 1.00f * moonDisc) * (1f - atm.rainFactor * 0.8f);

            // Subtle procedural starfield above horizon
            if (dy > 0.06f && atm.rainFactor < 0.5f) {
                float star = hashStar(dx, dy, dz);
                if (star > 0.9965f) {
                    float brightness = ((star - 0.9965f) / 0.0035f) * atm.nightFactor * ColorMath.smoothstep(0.06f, 0.35f, dy);
                    outRgb[0] += brightness * 0.85f;
                    outRgb[1] += brightness * 0.92f;
                    outRgb[2] += brightness * 1.00f;
                }
            }
        }

        // Subtle warm sunset band along the horizon
        if (atm.goldenHourFactor > 0.05f) {
            float band = (float) Math.exp(-Math.pow(dy * 5.2f, 2.0)) * atm.goldenHourFactor * (0.4f + 0.6f * Math.max(0f, sunDot));
            outRgb[0] += band * 0.36f;
            outRgb[1] += band * 0.16f;
            outRgb[2] += band * 0.05f;
        }

        ColorMath.applyFilmicPolish(outRgb, config.filmicStrength * 0.35f);
        outRgb[0] = ColorMath.clamp01(outRgb[0]);
        outRgb[1] = ColorMath.clamp01(outRgb[1]);
        outRgb[2] = ColorMath.clamp01(outRgb[2]);
    }

    /**
     * Computes cloud top & bottom lighting colors for Anima's enhanced blocky clouds.
     */
    public static void computeCloudColors(AnimaAtmosphere atm, AnimaConfig config, float[] outTop, float[] outBottom) {
        if (!config.isActive() || !config.skyEnhancement) {
            float v = ColorMath.lerp(0.18f, 0.96f, atm.dayFactor);
            outTop[0] = v; outTop[1] = v; outTop[2] = v;
            outBottom[0] = v * 0.82f; outBottom[1] = v * 0.82f; outBottom[2] = v * 0.85f;
            return;
        }

        // Sunlit warm tops
        float[] dayTop = {1.00f, 0.98f, 0.95f};
        float[] goldenTop = {1.00f, 0.78f, 0.56f};
        float[] nightTop = {0.22f, 0.28f, 0.44f};
        float[] tmp = new float[3];
        ColorMath.lerpRgb(dayTop, goldenTop, atm.goldenHourFactor, tmp);
        ColorMath.lerpRgb(nightTop, tmp, atm.dayFactor, outTop);

        // Cool atmospheric undersides for 3D volumetric depth without volumetric shaders
        float[] dayBot = {0.72f, 0.80f, 0.92f};
        float[] goldenBot = {0.58f, 0.45f, 0.58f};
        float[] nightBot = {0.10f, 0.14f, 0.24f};
        ColorMath.lerpRgb(dayBot, goldenBot, atm.goldenHourFactor, tmp);
        ColorMath.lerpRgb(nightBot, tmp, atm.dayFactor, outBottom);
    }

    private static void sampleVanillaSky(float dx, float dy, float dz, AnimaAtmosphere atm, float[] outRgb) {
        // Vanilla Minecraft flat sky blue -> slightly lighter horizon
        float[] vDaySky = {0.47f, 0.65f, 1.00f};
        float[] vDayHoriz = {0.75f, 0.85f, 1.00f};
        float[] vNightSky = {0.01f, 0.01f, 0.03f};
        float[] vSunsetHoriz = {0.78f, 0.46f, 0.34f};

        float[] base = new float[3];
        float[] horiz = new float[3];
        ColorMath.lerpRgb(vNightSky, vDaySky, atm.dayFactor, base);
        ColorMath.lerpRgb(vNightSky, vDayHoriz, atm.dayFactor, horiz);
        ColorMath.lerpRgb(horiz, vSunsetHoriz, atm.goldenHourFactor * 0.65f, horiz);

        float h = (float) Math.pow(1f - ColorMath.clamp01(dy), 3.5f);
        ColorMath.lerpRgb(base, horiz, h, outRgb);

        // Vanilla square sun
        float sunDot = dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ;
        if (sunDot > 0.996f && atm.dayFactor > 0.1f) {
            outRgb[0] = 1.0f; outRgb[1] = 0.98f; outRgb[2] = 0.88f;
        }
    }

    private static float hashStar(float dx, float dy, float dz) {
        int ix = (int) Math.floor(dx * 160f);
        int iy = (int) Math.floor(dy * 160f);
        int iz = (int) Math.floor(dz * 160f);
        int h = ix * 374761393 + iy * 668265263 + iz * 2147483647;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0xFFFF) / 65535f;
    }
}
