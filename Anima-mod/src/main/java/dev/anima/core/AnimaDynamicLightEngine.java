package dev.anima.core;

/**
 * Shader-free dynamic handheld lighting engine.
 * Tracks the active player handheld light source (main hand or offhand) and computes
 * O(1), zero-allocation block light contribution around the player as they move.
 */
public final class AnimaDynamicLightEngine {

    private static volatile boolean active = false;
    private static volatile double sourceX = 0.0;
    private static volatile double sourceY = 0.0;
    private static volatile double sourceZ = 0.0;
    private static volatile int sourceLuminance = 0;

    private AnimaDynamicLightEngine() {}

    public static void updateSource(double x, double y, double z, int luminance) {
        int clamped = Math.max(0, Math.min(15, luminance));
        sourceX = x;
        sourceY = y;
        sourceZ = z;
        sourceLuminance = clamped;
        active = clamped > 0;
    }

    public static void clear() {
        active = false;
        sourceLuminance = 0;
    }

    public static boolean isActive() {
        return active && sourceLuminance > 0;
    }

    public static int getSourceLuminance() {
        return sourceLuminance;
    }

    public static double getSourceX() { return sourceX; }
    public static double getSourceY() { return sourceY; }
    public static double getSourceZ() { return sourceZ; }

    /**
     * Computes dynamic block light (0..15) at world coordinates (x, y, z)
     * using fast squared-distance early rejection and smooth radial falloff.
     */
    public static int computeDynamicBlockLight(double x, double y, double z) {
        if (!active) return 0;
        int lum = sourceLuminance;
        if (lum <= 0) return 0;

        double dx = x - sourceX;
        double dy = y - sourceY;
        double dz = z - sourceZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        double maxRange = lum + 0.5;
        if (distSq >= maxRange * maxRange) {
            return 0;
        }

        double dist = Math.sqrt(distSq);
        int light = (int) Math.round(lum - dist * 1.05);
        return Math.max(0, Math.min(15, light));
    }
}
