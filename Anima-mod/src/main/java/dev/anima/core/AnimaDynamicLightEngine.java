package dev.anima.core;

/**
 * Shader-free dynamic lighting engine supporting:
 * - Zero-lag per-frame GPU handheld lighting (player main hand & offhand) via smooth
 *   wall-clock faded luminance passed directly into terrain.vsh (ZERO chunk rebuilds!).
 * - Dropped luminous ItemEntities (torches, lanterns, glowstone, etc.) illuminating
 *   surrounding blocks and entities.
 */
public final class AnimaDynamicLightEngine {

    public static final int MAX_SOURCES = 16;

    private static final class LightSource {
        double x, y, z;
        float luminance;
    }

    // All active sources (used by EntityLightMixin, ItemInHandLightMixin, and Anima-renderer)
    private static volatile LightSource[] activeSources = new LightSource[0];
    // Dropped-only sources (used by DynamicLightMixin for chunk meshes — player light is handled per-frame in GPU!)
    private static volatile LightSource[] droppedSources = new LightSource[0];

    // Real-time wall-clock faded player handheld luminance (0..15) for 60/120/240 FPS silky fading
    private static volatile float targetPlayerLum = 0f;
    private static volatile float smoothPlayerLum = 0f;
    private static volatile long lastFadeNanos = 0L;

    private static volatile double playerX = 0.0;
    private static volatile double playerY = 0.0;
    private static volatile double playerZ = 0.0;

    private AnimaDynamicLightEngine() {}

    /**
     * Advances smooth wall-clock fade of the player's handheld light luminance.
     * Called per-frame from FogRenderer so fading runs at full monitor refresh rate (60/120/240+ FPS).
     */
    public static float sampleSmoothPlayerLuminance() {
        long now = System.nanoTime();
        long prev = lastFadeNanos;
        lastFadeNanos = now;
        if (prev == 0L) {
            return smoothPlayerLum;
        }
        float dt = (float) Math.min(0.1, (now - prev) * 1e-9);
        float target = targetPlayerLum;
        float current = smoothPlayerLum;

        // Exponential ease (~0.18s smooth fade in/out, silky at any FPS)
        float speed = (target > current) ? 14.0f : 10.0f;
        float alpha = 1.0f - (float) Math.exp(-speed * dt);
        current += (target - current) * alpha;
        if (Math.abs(target - current) < 0.02f) {
            current = target;
        }
        smoothPlayerLum = ColorMath.clamp(current, 0f, 15f);
        return smoothPlayerLum;
    }

    public static float getSmoothPlayerLuminance() {
        return smoothPlayerLum;
    }

    /**
     * Updates target player handheld luminance and dropped luminous items each client tick.
     * Note: Player position is tracked directly without artificial lag!
     */
    public static void updateTick(
            double px, double py, double pz,
            int targetLum,
            double[] droppedX,
            double[] droppedY,
            double[] droppedZ,
            float[] droppedLum,
            int droppedCount) {

        playerX = px;
        playerY = py;
        playerZ = pz;
        targetPlayerLum = Math.max(0, Math.min(15, targetLum));

        // Build dropped-only array
        int maxDrop = Math.min(MAX_SOURCES - 1, droppedCount);
        LightSource[] drops = new LightSource[maxDrop];
        int dIdx = 0;
        for (int i = 0; i < maxDrop; i++) {
            if (droppedLum[i] > 0.05f) {
                LightSource ds = new LightSource();
                ds.x = droppedX[i];
                ds.y = droppedY[i];
                ds.z = droppedZ[i];
                ds.luminance = droppedLum[i];
                drops[dIdx++] = ds;
            }
        }
        if (dIdx != maxDrop) {
            LightSource[] trimmed = new LightSource[dIdx];
            System.arraycopy(drops, 0, trimmed, 0, dIdx);
            drops = trimmed;
        }
        droppedSources = drops;

        // Build combined array for entity/item lighting
        float pLum = smoothPlayerLum;
        boolean hasPlayer = pLum > 0.05f || targetLum > 0;
        LightSource[] all = new LightSource[dIdx + (hasPlayer ? 1 : 0)];
        int idx = 0;
        if (hasPlayer) {
            LightSource ps = new LightSource();
            ps.x = px;
            ps.y = py;
            ps.z = pz;
            ps.luminance = Math.max(pLum, targetLum * 0.25f);
            all[idx++] = ps;
        }
        System.arraycopy(drops, 0, all, idx, dIdx);
        activeSources = all;
    }

    /**
     * Direct single-source setter (used by Anima-renderer).
     */
    public static void updateSource(double x, double y, double z, int luminance) {
        int clamped = Math.max(0, Math.min(15, luminance));
        playerX = x;
        playerY = y;
        playerZ = z;
        targetPlayerLum = clamped;
        smoothPlayerLum = clamped;
        if (clamped <= 0) {
            activeSources = new LightSource[0];
            droppedSources = new LightSource[0];
        } else {
            LightSource s = new LightSource();
            s.x = x;
            s.y = y;
            s.z = z;
            s.luminance = clamped;
            activeSources = new LightSource[]{s};
            droppedSources = new LightSource[0];
        }
    }

    public static void clear() {
        targetPlayerLum = 0f;
        smoothPlayerLum = 0f;
        activeSources = new LightSource[0];
        droppedSources = new LightSource[0];
    }

    public static boolean isActive() {
        return activeSources.length > 0 || smoothPlayerLum > 0.05f;
    }

    public static boolean hasDroppedSources() {
        return droppedSources.length > 0;
    }

    public static float computeDynamicBlockLightFloat(double x, double y, double z) {
        return computeFromSources(activeSources, x, y, z);
    }

    public static int computeSmoothDynamicBlockLight(double x, double y, double z) {
        float val = computeFromSources(activeSources, x, y, z);
        if (val <= 0.01f) return 0;
        return Math.min(240, Math.round(val * 16.0f));
    }

    /**
     * Computes smooth dynamic block light (0..240) ONLY from dropped luminous items
     * (used by DynamicLightMixin so terrain chunks don't double-apply player handheld light).
     */
    public static int computeSmoothDroppedItemLight(double x, double y, double z) {
        float val = computeFromSources(droppedSources, x, y, z);
        if (val <= 0.01f) return 0;
        return Math.min(240, Math.round(val * 16.0f));
    }

    public static int computeDynamicBlockLight(double x, double y, double z) {
        return Math.min(15, Math.round(computeFromSources(activeSources, x, y, z)));
    }

    private static float computeFromSources(LightSource[] sources, double x, double y, double z) {
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
            float val = lum - dist * 1.02f;
            if (val > best) {
                best = val;
            }
        }
        return ColorMath.clamp(best, 0f, 15f);
    }
}
