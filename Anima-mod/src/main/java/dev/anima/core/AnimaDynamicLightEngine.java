package dev.anima.core;

/**
 * Shader-free dynamic lighting engine supporting:
 * - Player main hand & offhand light sources
 * - Dropped luminous ItemEntities (torches, lanterns, glowstone, etc.)
 * - Smooth temporal fade-in/fade-out and sub-level continuous lightmap precision (0..240)
 *   so light glides smoothly instead of snapping in coarse integer steps.
 */
public final class AnimaDynamicLightEngine {

    public static final int MAX_SOURCES = 16;

    private static final class LightSource {
        double x, y, z;
        float luminance; // continuous 0..15
    }

    private static volatile LightSource[] activeSources = new LightSource[0];

    // Smoothly interpolated player handheld light state
    private static double smoothPlayerX = Double.NaN;
    private static double smoothPlayerY = Double.NaN;
    private static double smoothPlayerZ = Double.NaN;
    private static float smoothPlayerLum = 0f;

    private AnimaDynamicLightEngine() {}

    /**
     * Smoothly updates the player handheld light (fading luminance & gliding position)
     * and replaces the active source snapshot with player + dropped luminous items.
     *
     * @return true if the light state changed noticeably enough to refresh nearby sections.
     */
    public static boolean updateFrame(
            double targetPlayerX,
            double targetPlayerY,
            double targetPlayerZ,
            int targetPlayerLum,
            double[] droppedX,
            double[] droppedY,
            double[] droppedZ,
            float[] droppedLum,
            int droppedCount) {

        float prevLum = smoothPlayerLum;
        double prevX = smoothPlayerX;
        double prevY = smoothPlayerY;
        double prevZ = smoothPlayerZ;

        if (targetPlayerLum > 0) {
            if (Double.isNaN(smoothPlayerX) || smoothPlayerLum < 0.05f) {
                smoothPlayerX = targetPlayerX;
                smoothPlayerY = targetPlayerY;
                smoothPlayerZ = targetPlayerZ;
            } else {
                // Fast, silky glide toward player's current position
                smoothPlayerX += (targetPlayerX - smoothPlayerX) * 0.50;
                smoothPlayerY += (targetPlayerY - smoothPlayerY) * 0.50;
                smoothPlayerZ += (targetPlayerZ - smoothPlayerZ) * 0.50;
            }
            // Fast smooth fade-in / level transition (~5-6 ticks to settle)
            smoothPlayerLum += (targetPlayerLum - smoothPlayerLum) * 0.36f;
            if (Math.abs(targetPlayerLum - smoothPlayerLum) < 0.08f) {
                smoothPlayerLum = targetPlayerLum;
            }
        } else {
            // Smooth fade-out when switching away from a torch/lantern
            smoothPlayerLum *= 0.62f;
            if (smoothPlayerLum < 0.08f) {
                smoothPlayerLum = 0f;
            }
        }

        int total = (smoothPlayerLum > 0.05f ? 1 : 0) + Math.min(MAX_SOURCES - 1, droppedCount);
        LightSource[] next = new LightSource[total];
        int idx = 0;
        if (smoothPlayerLum > 0.05f) {
            LightSource ps = new LightSource();
            ps.x = smoothPlayerX;
            ps.y = smoothPlayerY;
            ps.z = smoothPlayerZ;
            ps.luminance = smoothPlayerLum;
            next[idx++] = ps;
        }
        int maxDrop = Math.min(MAX_SOURCES - idx, droppedCount);
        for (int i = 0; i < maxDrop; i++) {
            if (droppedLum[i] > 0.05f) {
                LightSource ds = new LightSource();
                ds.x = droppedX[i];
                ds.y = droppedY[i];
                ds.z = droppedZ[i];
                ds.luminance = droppedLum[i];
                next[idx++] = ds;
            }
        }
        if (idx != total) {
            LightSource[] trimmed = new LightSource[idx];
            System.arraycopy(next, 0, trimmed, 0, idx);
            next = trimmed;
        }
        activeSources = next;

        boolean lumChanged = Math.abs(smoothPlayerLum - prevLum) > 0.06f;
        boolean posChanged = !Double.isNaN(prevX) && (
                (smoothPlayerX - prevX) * (smoothPlayerX - prevX)
                + (smoothPlayerY - prevY) * (smoothPlayerY - prevY)
                + (smoothPlayerZ - prevZ) * (smoothPlayerZ - prevZ) > 0.08
        );
        return lumChanged || posChanged || droppedCount > 0;
    }

    /**
     * Direct single-source setter (used by Anima-renderer).
     */
    public static void updateSource(double x, double y, double z, int luminance) {
        int clamped = Math.max(0, Math.min(15, luminance));
        smoothPlayerX = x;
        smoothPlayerY = y;
        smoothPlayerZ = z;
        smoothPlayerLum = clamped;
        if (clamped <= 0) {
            activeSources = new LightSource[0];
        } else {
            LightSource s = new LightSource();
            s.x = x;
            s.y = y;
            s.z = z;
            s.luminance = clamped;
            activeSources = new LightSource[]{s};
        }
    }

    public static void clear() {
        smoothPlayerLum = 0f;
        smoothPlayerX = Double.NaN;
        activeSources = new LightSource[0];
    }

    public static boolean isActive() {
        return activeSources.length > 0;
    }

    /**
     * Returns continuous floating-point dynamic block light in [0.0, 15.0] at (x, y, z).
     */
    public static float computeDynamicBlockLightFloat(double x, double y, double z) {
        LightSource[] sources = activeSources;
        if (sources.length == 0) return 0f;

        float best = 0f;
        for (int i = 0; i < sources.length; i++) {
            LightSource s = sources[i];
            float lum = s.luminance;
            if (lum <= best) continue;

            double dx = x - s.x;
            double dy = y - s.y;
            double dz = z - s.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            double maxRange = lum + 0.5;
            if (distSq >= maxRange * maxRange) continue;

            float dist = (float) Math.sqrt(distSq);
            float val = lum - dist * 1.05f;
            if (val > best) {
                best = val;
            }
        }
        return ColorMath.clamp(best, 0f, 15f);
    }

    /**
     * Returns smooth sub-level packed block light in [0, 240] (16 sub-steps per light level!)
     * for Minecraft 26.2's LightCoordsUtil.smoothPack(block0to240, sky0to240).
     */
    public static int computeSmoothDynamicBlockLight(double x, double y, double z) {
        float val = computeDynamicBlockLightFloat(x, y, z);
        if (val <= 0.01f) return 0;
        return Math.min(240, Math.round(val * 16.0f));
    }

    /**
     * Returns integer block light [0, 15] (used by Anima-renderer).
     */
    public static int computeDynamicBlockLight(double x, double y, double z) {
        return Math.min(15, Math.round(computeDynamicBlockLightFloat(x, y, z)));
    }
}
