package dev.anima.core;

/**
 * Lightweight color and math utilities for Anima's shader-free rendering pipeline.
 * Designed for zero-allocation hot paths so even low-end hardware stays at full FPS.
 */
public final class ColorMath {
    private ColorMath() {}

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    public static void lerpRgb(float[] a, float[] b, float t, float[] out) {
        out[0] = a[0] + (b[0] - a[0]) * t;
        out[1] = a[1] + (b[1] - a[1]) * t;
        out[2] = a[2] + (b[2] - a[2]) * t;
    }

    public static float[] rgb(float r, float g, float b) {
        return new float[]{r, g, b};
    }

    public static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /**
     * Adjusts saturation around perceptual luminance.
     */
    public static void adjustSaturation(float[] rgb, float saturation) {
        float l = luminance(rgb[0], rgb[1], rgb[2]);
        rgb[0] = clamp01(l + (rgb[0] - l) * saturation);
        rgb[1] = clamp01(l + (rgb[1] - l) * saturation);
        rgb[2] = clamp01(l + (rgb[2] - l) * saturation);
    }

    /**
     * Fast ACES-inspired filmic tone curve for rich contrast without crushed shadows
     * or blown-out highlights. Runs purely on RGB floats (no GPU shader needed).
     */
    public static float filmicCurve(float x) {
        x = Math.max(0f, x);
        float a = 2.51f;
        float b = 0.03f;
        float c = 2.43f;
        float d = 0.59f;
        float e = 0.14f;
        return clamp01((x * (a * x + b)) / (x * (c * x + d) + e));
    }

    /**
     * Soft S-curve contrast enhancement preserving highlight rolloff.
     */
    public static void applyFilmicPolish(float[] rgb, float strength) {
        if (strength <= 0f) return;
        for (int i = 0; i < 3; i++) {
            float v = Math.max(0f, rgb[i]);
            float mapped = filmicCurve(v * 1.12f);
            rgb[i] = clamp01(lerp(v, mapped, strength));
        }
    }

    /**
     * Schlick's Fresnel approximation for cheap, realistic water/sky grazing reflections.
     */
    public static float fresnelSchlick(float cosTheta, float f0) {
        float m = clamp01(1f - cosTheta);
        float m2 = m * m;
        return f0 + (1f - f0) * (m2 * m2 * m);
    }

    public static int packRgb(float r, float g, float b) {
        int ri = (int) (clamp01(r) * 255f + 0.5f);
        int gi = (int) (clamp01(g) * 255f + 0.5f);
        int bi = (int) (clamp01(b) * 255f + 0.5f);
        return (ri << 16) | (gi << 8) | bi;
    }

    public static int packArgb(float a, float r, float g, float b) {
        int ai = (int) (clamp01(a) * 255f + 0.5f);
        return (ai << 24) | packRgb(r, g, b);
    }

    public static int packAbgr(float a, float r, float g, float b) {
        int ai = (int) (clamp01(a) * 255f + 0.5f);
        int ri = (int) (clamp01(r) * 255f + 0.5f);
        int gi = (int) (clamp01(g) * 255f + 0.5f);
        int bi = (int) (clamp01(b) * 255f + 0.5f);
        return (ai << 24) | (bi << 16) | (gi << 8) | ri;
    }

    public static void unpackRgb(int rgb, float[] out) {
        out[0] = ((rgb >> 16) & 0xFF) / 255f;
        out[1] = ((rgb >> 8) & 0xFF) / 255f;
        out[2] = (rgb & 0xFF) / 255f;
    }
}
