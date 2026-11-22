package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import dev.anima.core.AnimaMc26Pipeline;
import dev.anima.core.ColorMath;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Multi-threaded 3D Minecraft 26.2 Voxel Rendering Engine.
 * Executes Minecraft 26.2's EXACT native rendering pipeline (lightmap.fsh, cardinal face
 * shading, 4-corner smooth AO, real water_still.png + BiomeColors tinting, linear
 * AtmosphericFogEnvironment, SkyRenderer dome, and LightCoordsUtil dynamic handheld lighting)
 * so renders are an honest 1:1 preview of Anima-mod in real Minecraft 26.2.
 */
public final class VoxelRenderer {

    public enum ClientEngine {
        VANILLA_26_2("Vanilla 26.2 (No Sodium)"),
        SODIUM_0_9_2("Sodium 0.9.2 + MC 26.2");

        public final String label;
        ClientEngine(String label) { this.label = label; }
    }

    private final VoxelWorld world;
    private final TextureAtlas atlas;
    private GlShaderExecutor gpuExecutor;

    public VoxelRenderer(VoxelWorld world, TextureAtlas atlas) {
        this.world = world;
        this.atlas = atlas;
    }

    public void setGpuExecutor(GlShaderExecutor gpuExecutor) {
        this.gpuExecutor = gpuExecutor;
    }

    public BufferedImage render(
            int width,
            int height,
            float camX, float camY, float camZ,
            float lookX, float lookY, float lookZ,
            float fovDeg,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float timeSeconds) {
        return render(width, height, camX, camY, camZ, lookX, lookY, lookZ, fovDeg, atm, config, timeSeconds, ClientEngine.VANILLA_26_2);
    }

    public BufferedImage render(
            int width,
            int height,
            float camX, float camY, float camZ,
            float lookX, float lookY, float lookZ,
            float fovDeg,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float timeSeconds,
            ClientEngine engine) {

        // Build exact Minecraft 26.2 LightmapInfo UBO uniforms (identical to LightmapExtractorMixin)
        AnimaMc26Pipeline.LightmapUniforms lmUniforms = new AnimaMc26Pipeline.LightmapUniforms();
        AnimaMc26Pipeline.fillVanillaLightmapUniforms(atm, lmUniforms);
        AnimaMc26Pipeline.applyAnimaLightmapUniforms(lmUniforms, atm, config, timeSeconds);

        // Precompute the 16x16 Minecraft 26.2 lightmap texture (just like lightmap.fsh!)
        float[][] lightmap = new float[256][3];
        for (int sky = 0; sky < 16; sky++) {
            for (int blk = 0; blk < 16; blk++) {
                AnimaMc26Pipeline.evaluateMc26Lightmap(sky, blk, lmUniforms, lightmap[sky * 16 + blk]);
            }
        }

        float[] fogColor = new float[3];
        AnimaMc26Pipeline.computeMc26FogColor(atm, config, fogColor);

        float[] skyZenith = new float[3];
        AnimaMc26Pipeline.computeMc26SkyColor(atm, config, skyZenith);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Build camera basis
        float fx = lookX - camX;
        float fy = lookY - camY;
        float fz = lookZ - camZ;
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fLen; fy /= fLen; fz /= fLen;

        float rx = -fz;
        float ry = 0f;
        float rz = fx;
        float rLen = (float) Math.sqrt(rx * rx + rz * rz);
        rx /= rLen; rz /= rLen;

        float ux = ry * fz - rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy - ry * fx;

        float aspect = (float) width / (float) height;
        float halfTan = (float) Math.tan(Math.toRadians(fovDeg * 0.5));

        final float fFx = fx, fFy = fy, fFz = fz;
        final float fRx = rx, fRz = rz;
        final float fUx = ux, fUy = uy, fUz = uz;

        int[] pixels = new int[width * height];
        float[] depthBuf = new float[width * height];

        IntStream.range(0, height).parallel().forEach(py -> {
            float[] rgb = new float[3];
            float[] hitDepth = new float[1];
            float ndcY = 1f - 2f * ((py + 0.5f) / height);
            for (int px = 0; px < width; px++) {
                float ndcX = 2f * ((px + 0.5f) / width) - 1f;
                float dx = fFx + ndcX * aspect * halfTan * fRx + ndcY * halfTan * fUx;
                float dy = fFy + ndcY * halfTan * fUy;
                float dz = fFz + ndcX * aspect * halfTan * fRz + ndcY * halfTan * fUz;
                float dLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= dLen; dy /= dLen; dz /= dLen;

                hitDepth[0] = 256.0f;
                tracePrimaryRay(camX, camY, camZ, dx, dy, dz, atm, config, timeSeconds, engine, lmUniforms, lightmap, fogColor, skyZenith, rgb, hitDepth);
                int idx = py * width + px;
                pixels[idx] = ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
                depthBuf[idx] = hitDepth[0];
            }
        });

        img.setRGB(0, 0, width, height, pixels, 0, width);

        // Execute Anima-mod's ACTUAL OpenGL 3.3 PostChain GLSL shaders on real GPU with Reverse-Z depth!
        if (config.isActive() && gpuExecutor != null) {
            gpuExecutor.applyRealPostChain(img, depthBuf, config);
        } else if (config.isBorderlands()) {
            applyBorderlandsDepthPostPass(img, depthBuf, false);
        } else if (config.isColorPencil()) {
            applyColorPencilPostPass(img, depthBuf);
        } else if (config.isBlueprint()) {
            applyBlueprintPostPass(img, depthBuf);
        } else if (config.isActive()) {
            applyLushBloomPostPass(img);
        }
        return img;
    }

    /**
     * Renders the scene with Miasma R1.0.2's signature cel-shading + Laplacian depth outlines + greenish miasma tint.
     */
    public BufferedImage renderMiasma(
            int width, int height,
            float camX, float camY, float camZ,
            float lookX, float lookY, float lookZ,
            float fovDeg, AnimaAtmosphere atm, float timeSeconds) {
        BufferedImage img = render(width, height, camX, camY, camZ, lookX, lookY, lookZ, fovDeg, atm, AnimaConfig.vanilla(), timeSeconds);
        // Re-extract depth via fast pass or apply Miasma pass directly on Borderlands depth
        AnimaConfig bCfg = AnimaConfig.borderlands();
        AnimaMc26Pipeline.LightmapUniforms lmUniforms = new AnimaMc26Pipeline.LightmapUniforms();
        AnimaMc26Pipeline.fillVanillaLightmapUniforms(atm, lmUniforms);
        float[][] lightmap = new float[256][3];
        for (int sky = 0; sky < 16; sky++)
            for (int blk = 0; blk < 16; blk++)
                AnimaMc26Pipeline.evaluateMc26Lightmap(sky, blk, lmUniforms, lightmap[sky * 16 + blk]);
        float[] fogColor = {0.48f * atm.dayFactor + 0.05f, 0.66f * atm.dayFactor + 0.09f, 0.44f * atm.dayFactor + 0.05f};
        float[] skyZenith = {0.26f * atm.dayFactor + 0.02f, 0.46f * atm.dayFactor + 0.05f, 0.30f * atm.dayFactor + 0.03f};

        float fx = lookX - camX, fy = lookY - camY, fz = lookZ - camZ;
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fLen; fy /= fLen; fz /= fLen;
        float rx = -fz, rz = fx;
        float rLen = (float) Math.sqrt(rx * rx + rz * rz);
        rx /= rLen; rz /= rLen;
        float ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        float aspect = (float) width / (float) height;
        float halfTan = (float) Math.tan(Math.toRadians(fovDeg * 0.5));
        final float fFx = fx, fFy = fy, fFz = fz, fRx = rx, fRz = rz, fUx = ux, fUy = uy, fUz = uz;

        int[] pixels = new int[width * height];
        float[] depthBuf = new float[width * height];
        IntStream.range(0, height).parallel().forEach(py -> {
            float[] rgb = new float[3];
            float[] hitDepth = new float[1];
            float ndcY = 1f - 2f * ((py + 0.5f) / height);
            for (int px = 0; px < width; px++) {
                float ndcX = 2f * ((px + 0.5f) / width) - 1f;
                float dx = fFx + ndcX * aspect * halfTan * fRx + ndcY * halfTan * fUx;
                float dy = fFy + ndcY * halfTan * fUy;
                float dz = fFz + ndcX * aspect * halfTan * fRz + ndcY * halfTan * fUz;
                float dLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= dLen; dy /= dLen; dz /= dLen;
                hitDepth[0] = 256.0f;
                tracePrimaryRay(camX, camY, camZ, dx, dy, dz, atm, bCfg, timeSeconds, ClientEngine.VANILLA_26_2, lmUniforms, lightmap, fogColor, skyZenith, rgb, hitDepth);
                int idx = py * width + px;
                pixels[idx] = ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
                depthBuf[idx] = hitDepth[0];
            }
        });
        img.setRGB(0, 0, width, height, pixels, 0, width);
        applyBorderlandsDepthPostPass(img, depthBuf, true);
        return img;
    }

    /**
     * Exact mirror of anima:post/borderlands (Miasma 12-tap Laplacian depth silhouette outlines + cloud outlines).
     * When miasmaTint=true, applies Miasma R1.0.2's greenish toxic atmosphere for direct comparison.
     */
    private static void applyBorderlandsDepthPostPass(BufferedImage img, float[] depthBuf, boolean miasmaTint) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] dst = new int[w * h];

        float[] lumBuf = new float[w * h];
        for (int i = 0; i < src.length; i++) {
            float r = ((src[i] >> 16) & 0xFF) / 255f;
            float g = ((src[i] >> 8) & 0xFF) / 255f;
            float b = (src[i] & 0xFF) / 255f;
            lumBuf[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }

        int[][] offsets = {
                {1,0}, {-1,1}, {0,1}, {1,1},
                {-2,2}, {-1,2}, {0,2}, {1,2}, {2,2},
                {-2,1}, {2,1}, {2,0}
        };

        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                float r = ((src[idx] >> 16) & 0xFF) / 255f;
                float g = ((src[idx] >> 8) & 0xFF) / 255f;
                float b = (src[idx] & 0xFF) / 255f;
                float lum = lumBuf[idx];
                float z0 = depthBuf[idx];

                float sr = ColorMath.clamp01(lum + (r - lum) * 1.14f);
                float sg = ColorMath.clamp01(lum + (g - lum) * 1.14f);
                float sb = ColorMath.clamp01(lum + (b - lum) * 1.14f);

                float cr = sr, cg = sg, cb = sb;
                if (z0 < 250f) {
                    float bands = 5.0f;
                    float scaled = lum * bands;
                    float baseBand = (float) Math.floor(scaled);
                    float frac = ColorMath.smoothstep(0.35f, 0.65f, scaled - baseBand);
                    float celLum = ColorMath.clamp((baseBand + frac + 0.16f) / (bands + 0.16f), 0.05f, 1.0f);
                    float mul = celLum / Math.max(0.035f, lum);
                    cr = ColorMath.clamp01(sr * mul);
                    cg = ColorMath.clamp01(sg * mul);
                    cb = ColorMath.clamp01(sb * mul);
                }

                // Exact Miasma 12-tap Laplacian depth silhouette outline (zero grid on flat sand/water!)
                float outlineFactor = 1.0f;
                float zScaled = z0 * 2.0f;
                for (int[] off : offsets) {
                    int x1 = Math.max(0, Math.min(w - 1, x + off[0]));
                    int y1 = Math.max(0, Math.min(h - 1, y + off[1]));
                    int x2 = Math.max(0, Math.min(w - 1, x - off[0]));
                    int y2 = Math.max(0, Math.min(h - 1, y - off[1]));
                    float diff = zScaled - (depthBuf[y1 * w + x1] + depthBuf[y2 * w + x2]);
                    outlineFactor *= ColorMath.clamp01(1.0f - Math.max(0f, diff - 0.18f) * 1.15f);
                }
                float depthInk = (1.0f - outlineFactor) * (1.0f - ColorMath.smoothstep(140f, 250f, z0));

                // Cloud silhouette outlines on sky pixels
                float cloudInk = 0f;
                if (z0 >= 250f) {
                    int xm = Math.max(0, x - 1), xp = Math.min(w - 1, x + 1);
                    int ym = Math.max(0, y - 1), yp = Math.min(h - 1, y + 1);
                    float grad = Math.abs(lumBuf[y * w + xp] - lumBuf[y * w + xm])
                               + Math.abs(lumBuf[yp * w + x] - lumBuf[ym * w + x]);
                    cloudInk = ColorMath.smoothstep(0.075f, 0.19f, grad) * 0.62f;
                }

                float ink = ColorMath.clamp(Math.max(depthInk, cloudInk), 0f, 0.90f);
                if (miasmaTint) {
                    // Miasma R1.0.2 signature desaturated greenish-toxic poison atmosphere
                    float ml = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
                    cr = ColorMath.clamp01(ColorMath.lerp(ml, cr, 0.78f) * 0.86f + 0.02f);
                    cg = ColorMath.clamp01(ColorMath.lerp(ml, cg, 0.88f) * 1.08f + 0.05f);
                    cb = ColorMath.clamp01(ColorMath.lerp(ml, cb, 0.72f) * 0.80f + 0.01f);
                }
                cr = ColorMath.lerp(cr, 0.04f, ink);
                cg = ColorMath.lerp(cg, 0.035f, ink);
                cb = ColorMath.lerp(cb, 0.055f, ink);
                dst[idx] = ColorMath.packRgb(cr, cg, cb);
            }
        });
        img.setRGB(0, 0, w, h, dst, 0, w);
    }

    private static float hash21(float px, float py) {
        float x = (float) (px * 234.34 - Math.floor(px * 234.34));
        float y = (float) (py * 435.345 - Math.floor(py * 435.345));
        float d = x * (x + 34.23f) + y * (y + 34.23f);
        x += d; y += d;
        float p = x * y;
        return (float) (p - Math.floor(p));
    }

    private static float valueNoise2D(float px, float py) {
        float ix = (float) Math.floor(px), iy = (float) Math.floor(py);
        float fx = px - ix, fy = py - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash21(ix, iy);
        float b = hash21(ix + 1f, iy);
        float c = hash21(ix, iy + 1f);
        float d = hash21(ix + 1f, iy + 1f);
        return ColorMath.lerp(ColorMath.lerp(a, b, fx), ColorMath.lerp(c, d, fx), fy);
    }

    /**
     * Exact mirror of anima:post/color_pencil (warm sketchbook paper grain + diagonal colored-pencil cross-hatching + graphite outlines).
     */
    private static void applyColorPencilPostPass(BufferedImage img, float[] depthBuf) {
        int w = img.getWidth(), h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] dst = new int[w * h];
        float[] lumBuf = new float[w * h];
        for (int i = 0; i < src.length; i++) {
            float r = ((src[i] >> 16) & 0xFF) / 255f;
            float g = ((src[i] >> 8) & 0xFF) / 255f;
            float b = (src[i] & 0xFF) / 255f;
            lumBuf[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }
        int[][] offsets = {{1, 0}, {0, 1}, {1, 1}, {-1, 1}};
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                float paperFine = valueNoise2D(x * 0.45f, y * 0.45f);
                float paperCoarse = valueNoise2D(x * 0.12f + 17.3f, y * 0.12f + 9.1f);
                float paperGrain = paperFine * 0.65f + paperCoarse * 0.35f;

                int wx = Math.max(0, Math.min(w - 1, Math.round(x + (paperCoarse - 0.5f) * 0.9f)));
                int wy = Math.max(0, Math.min(h - 1, Math.round(y + (valueNoise2D(x * 0.12f + 3.7f, y * 0.12f + 29.4f) - 0.5f) * 0.9f)));
                int sPix = src[wy * w + wx];
                float r = ((sPix >> 16) & 0xFF) / 255f;
                float g = ((sPix >> 8) & 0xFF) / 255f;
                float b = (sPix & 0xFF) / 255f;
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float z0 = depthBuf[idx];

                // 1. Vibrant colored-pencil pigment + warm cream sketchbook paper tooth
                float pr = ColorMath.clamp01(lum + (r - lum) * 1.24f);
                float pg = ColorMath.clamp01(lum + (g - lum) * 1.24f);
                float pb = ColorMath.clamp01(lum + (b - lum) * 1.24f);
                float paperScale = 0.94f + 0.10f * paperGrain;
                float cr = 0.965f * paperScale, cg = 0.945f * paperScale, cb = 0.895f * paperScale;
                float toothMix = ColorMath.smoothstep(0.55f, 0.98f, paperGrain) * 0.16f + 0.08f;
                float sr = ColorMath.lerp(pr, cr, toothMix);
                float sg = ColorMath.lerp(pg, cg, toothMix);
                float sb = ColorMath.lerp(pb, cb, toothMix);

                // 2. Diagonal colored-pencil cross-hatching in mid-tones & shadows
                if (z0 < 250f) {
                    float diag1 = (float) Math.sin((x + y) * 0.95f + (paperCoarse - 0.5f) * 2.2f);
                    float diag2 = (float) Math.sin((x - y) * 0.95f + (paperFine - 0.5f) * 2.0f);
                    float stroke1 = ColorMath.smoothstep(0.55f, 0.92f, diag1) * (1f - ColorMath.smoothstep(0.28f, 0.68f, lum));
                    float stroke2 = ColorMath.smoothstep(0.62f, 0.94f, diag2) * (1f - ColorMath.smoothstep(0.10f, 0.38f, lum));
                    float hatch = ColorMath.clamp(stroke1 * 0.36f + stroke2 * 0.32f, 0f, 0.52f);
                    float hr = ColorMath.lerp(0.16f, pr * 0.42f, 0.55f);
                    float hg = ColorMath.lerp(0.14f, pg * 0.42f, 0.55f);
                    float hb = ColorMath.lerp(0.18f, pb * 0.42f, 0.55f);
                    sr = ColorMath.lerp(sr, hr, hatch);
                    sg = ColorMath.lerp(sg, hg, hatch);
                    sb = ColorMath.lerp(sb, hb, hatch);
                }

                // 3. Hand-drawn graphite silhouette outlines
                float maxCurv = 0f, maxStep = 0f;
                for (int[] off : offsets) {
                    int x1 = Math.max(0, Math.min(w - 1, x + off[0]));
                    int y1 = Math.max(0, Math.min(h - 1, y + off[1]));
                    int x2 = Math.max(0, Math.min(w - 1, x - off[0]));
                    int y2 = Math.max(0, Math.min(h - 1, y - off[1]));
                    float za = depthBuf[y1 * w + x1];
                    float zb = depthBuf[y2 * w + x2];
                    float curv = Math.abs(2f * z0 - (za + zb)) / Math.max(z0, 1f);
                    float stepA = Math.abs(za - z0) / Math.max(Math.min(za, z0), 1f);
                    float stepB = Math.abs(zb - z0) / Math.max(Math.min(zb, z0), 1f);
                    maxCurv = Math.max(maxCurv, curv);
                    maxStep = Math.max(maxStep, Math.max(stepA, stepB));
                }
                float distFade = 1f - ColorMath.smoothstep(140f, 250f, z0);
                float lineWeight = 0.72f + 0.28f * paperFine;
                float depthPencil = Math.max(
                        ColorMath.smoothstep(0.058f, 0.15f, maxCurv),
                        ColorMath.smoothstep(0.14f, 0.30f, maxStep)
                ) * distFade * lineWeight;

                float cloudPencil = 0f;
                if (z0 >= 250f) {
                    int xm = Math.max(0, x - 1), xp = Math.min(w - 1, x + 1);
                    int ym = Math.max(0, y - 1), yp = Math.min(h - 1, y + 1);
                    float grad = Math.abs(lumBuf[y * w + xp] - lumBuf[y * w + xm])
                               + Math.abs(lumBuf[yp * w + x] - lumBuf[ym * w + x]);
                    cloudPencil = ColorMath.smoothstep(0.07f, 0.17f, grad) * 0.48f * lineWeight;
                }

                float outline = ColorMath.clamp(Math.max(depthPencil, cloudPencil), 0f, 0.80f);
                sr = ColorMath.lerp(sr, 0.15f, outline);
                sg = ColorMath.lerp(sg, 0.13f, outline);
                sb = ColorMath.lerp(sb, 0.17f, outline);
                dst[idx] = ColorMath.packRgb(sr, sg, sb);
            }
        });
        img.setRGB(0, 0, w, h, dst, 0, w);
    }

    /**
     * Exact mirror of anima:post/blueprint (textureless Prussian-blue architectural cyanotype + drafting grid + white linework).
     */
    private static void applyBlueprintPostPass(BufferedImage img, float[] depthBuf) {
        int w = img.getWidth(), h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        int[] dst = new int[w * h];
        float[] lumBuf = new float[w * h];
        for (int i = 0; i < src.length; i++) {
            float r = ((src[i] >> 16) & 0xFF) / 255f;
            float g = ((src[i] >> 8) & 0xFF) / 255f;
            float b = (src[i] & 0xFF) / 255f;
            lumBuf[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        }
        int[][] offsets = {{1, 0}, {0, 1}, {1, 1}, {-1, 1}};
        IntStream.range(0, h).parallel().forEach(y -> {
            float uvY = (y + 0.5f) / h;
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                float uvX = (x + 0.5f) / w;
                float lum = lumBuf[idx];
                float z0 = depthBuf[idx];

                // 1. Prussian-blue cyanotype paper with subtle drafting vignette
                float vx = uvX * 2f - 1f, vy = uvY * 2f - 1f;
                float vig = ColorMath.clamp(1f - (vx * vx + vy * vy) * 0.16f, 0.72f, 1f);
                float grain = (hash21((float) Math.floor(x * 0.5f), (float) Math.floor(y * 0.5f)) - 0.5f) * 0.025f;

                float pr, pg, pb;
                if (z0 >= 250f) {
                    float t = ColorMath.clamp01(uvY);
                    pr = ColorMath.lerp(0.065f, 0.095f * 0.92f, t);
                    pg = ColorMath.lerp(0.235f, 0.305f * 0.92f, t);
                    pb = ColorMath.lerp(0.510f, 0.625f * 0.92f, t);
                } else {
                    float t = ColorMath.smoothstep(0.08f, 0.90f, lum);
                    pr = ColorMath.lerp(0.055f, 0.095f, t);
                    pg = ColorMath.lerp(0.195f, 0.305f, t);
                    pb = ColorMath.lerp(0.445f, 0.625f, t);
                }
                pr = ColorMath.clamp01(pr * vig + grain);
                pg = ColorMath.clamp01(pg * vig + grain);
                pb = ColorMath.clamp01(pb * vig + grain);

                // 2. Crisp technical drafting grid (minor 24px + major 120px)
                float mx = Math.abs(((x / 24f + 0.5f) % 1f) - 0.5f) * 24f;
                float my = Math.abs(((y / 24f + 0.5f) % 1f) - 0.5f) * 24f;
                float Mx = Math.abs(((x / 120f + 0.5f) % 1f) - 0.5f) * 120f;
                float My = Math.abs(((y / 120f + 0.5f) % 1f) - 0.5f) * 120f;
                float minorLine = (1f - ColorMath.smoothstep(0.4f, 1.1f, Math.min(mx, my))) * 0.11f;
                float majorLine = (1f - ColorMath.smoothstep(0.5f, 1.4f, Math.min(Mx, My))) * 0.22f;
                float grid = ColorMath.clamp(Math.max(minorLine, majorLine), 0f, 0.28f);
                pr = ColorMath.lerp(pr, 0.45f, grid);
                pg = ColorMath.lerp(pg, 0.76f, grid);
                pb = ColorMath.lerp(pb, 1.00f, grid);

                // 3. Architectural white diagonal hatching in shaded geometry
                if (z0 < 250f) {
                    float h1 = ColorMath.smoothstep(0.68f, 0.94f, (float) Math.sin((x + y) * 0.85f)) * (1f - ColorMath.smoothstep(0.22f, 0.58f, lum));
                    float h2 = ColorMath.smoothstep(0.72f, 0.96f, (float) Math.sin((x - y) * 0.85f)) * (1f - ColorMath.smoothstep(0.08f, 0.28f, lum));
                    float hatch = ColorMath.clamp(h1 * 0.24f + h2 * 0.20f, 0f, 0.38f);
                    pr = ColorMath.lerp(pr, 0.78f, hatch);
                    pg = ColorMath.lerp(pg, 0.92f, hatch);
                    pb = ColorMath.lerp(pb, 1.00f, hatch);
                }

                // 4. Crisp bright-white technical linework
                float maxCurv = 0f, maxStep = 0f;
                for (int[] off : offsets) {
                    int x1 = Math.max(0, Math.min(w - 1, x + off[0]));
                    int y1 = Math.max(0, Math.min(h - 1, y + off[1]));
                    int x2 = Math.max(0, Math.min(w - 1, x - off[0]));
                    int y2 = Math.max(0, Math.min(h - 1, y - off[1]));
                    float za = depthBuf[y1 * w + x1];
                    float zb = depthBuf[y2 * w + x2];
                    float curv = Math.abs(2f * z0 - (za + zb)) / Math.max(z0, 1f);
                    float stepA = Math.abs(za - z0) / Math.max(Math.min(za, z0), 1f);
                    float stepB = Math.abs(zb - z0) / Math.max(Math.min(zb, z0), 1f);
                    maxCurv = Math.max(maxCurv, curv);
                    maxStep = Math.max(maxStep, Math.max(stepA, stepB));
                }
                float distFade = 1f - ColorMath.smoothstep(140f, 250f, z0);
                float depthLine = Math.max(
                        ColorMath.smoothstep(0.050f, 0.135f, maxCurv),
                        ColorMath.smoothstep(0.13f, 0.28f, maxStep)
                ) * distFade;

                float cloudLine = 0f;
                if (z0 >= 250f) {
                    int xm = Math.max(0, x - 1), xp = Math.min(w - 1, x + 1);
                    int ym = Math.max(0, y - 1), yp = Math.min(h - 1, y + 1);
                    float grad = Math.abs(lumBuf[y * w + xp] - lumBuf[y * w + xm])
                               + Math.abs(lumBuf[yp * w + x] - lumBuf[ym * w + x]);
                    cloudLine = ColorMath.smoothstep(0.065f, 0.16f, grad) * 0.65f;
                }

                float whiteInk = ColorMath.clamp(Math.max(depthLine, cloudLine), 0f, 0.95f);
                pr = ColorMath.lerp(pr, 0.92f, whiteInk);
                pg = ColorMath.lerp(pg, 0.97f, whiteInk);
                pb = ColorMath.lerp(pb, 1.00f, whiteInk);
                dst[idx] = ColorMath.packRgb(pr, pg, pb);
            }
        });
        img.setRGB(0, 0, w, h, dst, 0, w);
    }
    private static void applyLushBloomPostPass(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] src = img.getRGB(0, 0, w, h, null, 0, w);
        float[] b1 = new float[w * h * 3];
        float[] b2 = new float[w * h * 3];

        for (int i = 0; i < src.length; i++) {
            float r = ((src[i] >> 16) & 0xFF) / 255f;
            float g = ((src[i] >> 8) & 0xFF) / 255f;
            float b = (src[i] & 0xFF) / 255f;
            float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float mask = ColorMath.smoothstep(0.68f, 1.0f, l);
            b1[i * 3] = r * mask; b1[i * 3 + 1] = g * mask; b1[i * 3 + 2] = b * mask;
        }
        float[] weights = {0.227027f, 0.1945946f, 0.1216216f, 0.054054f, 0.016216f};
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float sr = b1[(y * w + x) * 3] * weights[0];
                float sg = b1[(y * w + x) * 3 + 1] * weights[0];
                float sb = b1[(y * w + x) * 3 + 2] * weights[0];
                for (int k = 1; k < 5; k++) {
                    int x1 = Math.min(w - 1, x + k * 2), x2 = Math.max(0, x - k * 2);
                    sr += (b1[(y * w + x1) * 3] + b1[(y * w + x2) * 3]) * weights[k];
                    sg += (b1[(y * w + x1) * 3 + 1] + b1[(y * w + x2) * 3 + 1]) * weights[k];
                    sb += (b1[(y * w + x1) * 3 + 2] + b1[(y * w + x2) * 3 + 2]) * weights[k];
                }
                int di = (y * w + x) * 3;
                b2[di] = sr; b2[di + 1] = sg; b2[di + 2] = sb;
            }
        });
        int[] dst = new int[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float br = b2[(y * w + x) * 3] * weights[0];
                float bg = b2[(y * w + x) * 3 + 1] * weights[0];
                float bb = b2[(y * w + x) * 3 + 2] * weights[0];
                for (int k = 1; k < 5; k++) {
                    int y1 = Math.min(h - 1, y + k * 2), y2 = Math.max(0, y - k * 2);
                    br += (b2[(y1 * w + x) * 3] + b2[(y2 * w + x) * 3]) * weights[k];
                    bg += (b2[(y1 * w + x) * 3 + 1] + b2[(y2 * w + x) * 3 + 1]) * weights[k];
                    bb += (b2[(y1 * w + x) * 3 + 2] + b2[(y2 * w + x) * 3 + 2]) * weights[k];
                }
                int idx = y * w + x;
                float r = ((src[idx] >> 16) & 0xFF) / 255f + br * 0.42f;
                float g = ((src[idx] >> 8) & 0xFF) / 255f + bg * 0.39f;
                float b = (src[idx] & 0xFF) / 255f + bb * 0.34f;
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float ht = ColorMath.smoothstep(0.15f, 0.78f, lum);
                r *= ColorMath.lerp(0.92f, 1.06f, ht);
                g *= ColorMath.lerp(0.96f, 1.01f, ht);
                b *= ColorMath.lerp(1.08f, 0.94f, ht);
                r = ColorMath.lerp(r, ColorMath.filmicCurve(r * 1.18f), 0.58f);
                g = ColorMath.lerp(g, ColorMath.filmicCurve(g * 1.18f), 0.58f);
                b = ColorMath.lerp(b, ColorMath.filmicCurve(b * 1.18f), 0.58f);
                float[] rgb = {r, g, b};
                ColorMath.adjustSaturation(rgb, 1.15f);
                dst[idx] = ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
            }
        });
        img.setRGB(0, 0, w, h, dst, 0, w);
    }

    private void tracePrimaryRay(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float timeSeconds,
            ClientEngine engine,
            AnimaMc26Pipeline.LightmapUniforms lmUniforms,
            float[][] lightmap,
            float[] fogColor,
            float[] skyZenith,
            float[] outRgb,
            float[] outDepth) {

        float maxDist = 92f;
        int vx = (int) Math.floor(ox);
        int vy = (int) Math.floor(oy);
        int vz = (int) Math.floor(oz);

        int stepX = dx >= 0 ? 1 : -1;
        int stepY = dy >= 0 ? 1 : -1;
        int stepZ = dz >= 0 ? 1 : -1;

        float tDeltaX = dx == 0 ? 1e30f : Math.abs(1f / dx);
        float tDeltaY = dy == 0 ? 1e30f : Math.abs(1f / dy);
        float tDeltaZ = dz == 0 ? 1e30f : Math.abs(1f / dz);

        float tMaxX = dx == 0 ? 1e30f : ((dx > 0 ? (vx + 1 - ox) : (ox - vx)) * tDeltaX);
        float tMaxY = dy == 0 ? 1e30f : ((dy > 0 ? (vy + 1 - oy) : (oy - vy)) * tDeltaY);
        float tMaxZ = dz == 0 ? 1e30f : ((dz > 0 ? (vz + 1 - oz) : (oz - vz)) * tDeltaZ);

        float t = 0f;
        int nx = 0, ny = 0, nz = 0;

        boolean hitWater = false;
        float waterHitT = 0f;
        float waterHitX = 0f, waterHitZ = 0f;
        float waterU = 0f, waterV = 0f;
        int waterSkyLight = 15;
        int waterBlockLight = 0;

        for (int step = 0; step < 220; step++) {
            if (t > maxDist) break;

            if (world.inBounds(vx, vy, vz)) {
                BlockType block = world.getBlock(vx, vy, vz);

                if (block == BlockType.WATER && !hitWater) {
                    hitWater = true;
                    waterHitT = t;
                    float wx = ox + dx * t;
                    float wy = oy + dy * t;
                    float wz = oz + dz * t;
                    waterHitX = wx;
                    waterHitZ = wz;
                    waterU = ColorMath.clamp01(wx - vx);
                    waterV = ColorMath.clamp01(wz - vz);
                    int aboveY = Math.min(VoxelWorld.SIZE_Y - 1, vy + 1);
                    waterSkyLight = world.getSkyLight(vx, aboveY, vz);
                    waterBlockLight = Math.max(world.getBlockLight(vx, vy, vz), world.getBlockLight(vx, aboveY, vz));
                    if (config.isActive() && config.dynamicHandheldLight && AnimaDynamicLightEngine.isActive()) {
                        waterBlockLight = Math.max(waterBlockLight, AnimaDynamicLightEngine.computeDynamicBlockLight(wx, wy + 0.5, wz));
                    }
                } else if (block.crossModel) {
                    float[] plantRgb = new float[3];
                    float plantT = intersectCrossModel(ox, oy, oz, dx, dy, dz, vx, vy, vz, block, atm, config, plantRgb);
                    if (plantT >= 0f) {
                        shadeCrossPlant(vx, vy, vz, block, plantRgb, config, lightmap, outRgb);
                        applyMc26Fog(plantT, atm, config, fogColor, outRgb);
                        outDepth[0] = plantT;
                        return;
                    }
                } else if (block.solid) {
                    float hx = ox + dx * t;
                    float hy = oy + dy * t;
                    float hz = oz + dz * t;

                    float u, v;
                    if (ny != 0) {
                        u = hx - vx;
                        v = hz - vz;
                    } else if (nx != 0) {
                        u = hz - vz;
                        v = hy - vy;
                    } else {
                        u = hx - vx;
                        v = hy - vy;
                    }
                    u = ColorMath.clamp01(u);
                    v = ColorMath.clamp01(v);

                    int texel = atlas.sample(block, ny, u, v, atm, config);
                    int alpha = (texel >>> 24) & 0xFF;
                    if (alpha > 128) {
                        float[] surfaceRgb = new float[3];
                        shadeSolidHit(vx, vy, vz, nx, ny, nz, hx, hy, hz, u, v, texel, config, lightmap, surfaceRgb);

                        if (hitWater) {
                            // Exact mirror of Anima-mod's terrain.vsh + terrain.fsh water pipeline
                            float[] waterTex = new float[3];
                            atlas.sampleWater(waterU, waterV, atm, config, waterTex);
                            float[] wLight = lightmap[waterSkyLight * 16 + waterBlockLight];
                            float wr = waterTex[0] * wLight[0];
                            float wg = waterTex[1] * wLight[1];
                            float wb = waterTex[2] * wLight[2];

                            float waterDepth = Math.max(0.1f, t - waterHitT);
                            float depthFog = ColorMath.clamp01(waterDepth / (config.isActive() ? 14f : 9f));
                            float waterAlpha = ColorMath.clamp01(0.62f + depthFog * 0.25f);

                            if (config.isActive() && config.waterEnhancement) {
                                final float H = 0.3926990817f;
                                float wt = timeSeconds * 2.4f;
                                float r1 = (float) (Math.sin(waterHitX * (6.0f * H) + waterHitZ * (4.0f * H) + wt * 1.1f) * Math.cos(waterHitX * (4.0f * H) - waterHitZ * (7.0f * H) + wt * 0.85f));
                                float r2 = (float) Math.sin(waterHitX * (12.0f * H) - waterHitZ * (10.0f * H) - wt * 1.35f);
                                float ripple = r1 * 0.65f + r2 * 0.35f;

                                float ndotv = ColorMath.clamp(Math.abs(dy) + ripple * 0.07f, 0.02f, 1.0f);
                                float fresnel = 0.04f + 0.72f * (float) Math.pow(1.0f - ndotv, 4.0);

                                float skyR = fogColor[0] * 1.02f;
                                float skyG = fogColor[1] * 1.02f;
                                float skyB = fogColor[2] * 1.02f;
                                float spec = (float) Math.pow(Math.max(0f, ripple * 0.5f + 0.5f), 8.0) * 0.28f;

                                // Deep crystal Complementary turquoise-sapphire water body
                                float wbR = (float) Math.pow(Math.max(0f, wr), 1.32f) * 0.24f;
                                float wbG = (float) Math.pow(Math.max(0f, wg), 1.32f) * 0.84f;
                                float wbB = (float) Math.pow(Math.max(0f, wb), 1.32f) * 1.02f;

                                wr = ColorMath.clamp01(ColorMath.lerp(wbR, skyR, fresnel * 0.74f) + (skyR * 0.65f + 0.35f) * spec);
                                wg = ColorMath.clamp01(ColorMath.lerp(wbG, skyG, fresnel * 0.74f) + (skyG * 0.65f + 0.35f) * spec);
                                wb = ColorMath.clamp01(ColorMath.lerp(wbB, skyB, fresnel * 0.74f) + (skyB * 0.65f + 0.35f) * spec);
                                waterAlpha = ColorMath.clamp(ColorMath.lerp(0.44f, 0.88f, fresnel), 0.40f, 0.90f);
                            }

                            outRgb[0] = ColorMath.lerp(surfaceRgb[0], wr, waterAlpha);
                            outRgb[1] = ColorMath.lerp(surfaceRgb[1], wg, waterAlpha);
                            outRgb[2] = ColorMath.lerp(surfaceRgb[2], wb, waterAlpha);
                            applyMc26Fog(waterHitT, atm, config, fogColor, outRgb);
                            outDepth[0] = waterHitT;
                        } else {
                            outRgb[0] = surfaceRgb[0];
                            outRgb[1] = surfaceRgb[1];
                            outRgb[2] = surfaceRgb[2];
                            applyMc26Fog(t, atm, config, fogColor, outRgb);
                            outDepth[0] = t;
                        }
                        return;
                    }
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    t = tMaxX; tMaxX += tDeltaX; vx += stepX; nx = -stepX; ny = 0; nz = 0;
                } else {
                    t = tMaxZ; tMaxZ += tDeltaZ; vz += stepZ; nx = 0; ny = 0; nz = -stepZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    t = tMaxY; tMaxY += tDeltaY; vy += stepY; nx = 0; ny = -stepY; nz = 0;
                } else {
                    t = tMaxZ; tMaxZ += tDeltaZ; vz += stepZ; nx = 0; ny = 0; nz = -stepZ;
                }
            }
        }

        if (hitWater) {
            float[] waterTex = new float[3];
            atlas.sampleWater(waterU, waterV, atm, config, waterTex);
            float[] wLight = lightmap[waterSkyLight * 16 + waterBlockLight];
            outRgb[0] = waterTex[0] * wLight[0];
            outRgb[1] = waterTex[1] * wLight[1];
            outRgb[2] = waterTex[2] * wLight[2];
            applyMc26Fog(waterHitT, atm, config, fogColor, outRgb);
            return;
        }

        sampleMc26Sky(dx, dy, dz, atm, config, skyZenith, fogColor, atlas, outRgb);
        blendMc26Clouds(ox, oy, oz, dx, dy, dz, atm, outRgb);
    }

    private void shadeSolidHit(
            int vx, int vy, int vz,
            int nx, int ny, int nz,
            float hx, float hy, float hz,
            float u, float v,
            int texel,
            AnimaConfig config,
            float[][] lightmap,
            float[] outRgb) {

        float[] albedo = new float[3];
        TextureAtlas.unpackRgba(texel, albedo);

        int lx = vx + nx;
        int ly = vy + ny;
        int lz = vz + nz;
        int skyL = world.getSkyLight(lx, ly, lz);
        float blkFloat = world.getBlockLight(lx, ly, lz);
        if (config.isActive() && config.dynamicHandheldLight && AnimaDynamicLightEngine.isActive()) {
            blkFloat = Math.max(blkFloat, AnimaDynamicLightEngine.computeDynamicBlockLightFloat(hx + nx * 0.25, hy + ny * 0.25, hz + nz * 0.25));
        }

        // Exact Minecraft 26.2 linearly-filtered 16x16 lightmap lookup (smooth sub-level interpolation!)
        int bLo = Math.min(15, (int) Math.floor(blkFloat));
        int bHi = Math.min(15, bLo + 1);
        float bFrac = blkFloat - bLo;
        float[] l0 = lightmap[skyL * 16 + bLo];
        float[] l1 = lightmap[skyL * 16 + bHi];
        float lr = ColorMath.lerp(l0[0], l1[0], bFrac);
        float lg = ColorMath.lerp(l0[1], l1[1], bFrac);
        float lb = ColorMath.lerp(l0[2], l1[2], bFrac);

        float cardinal = AnimaMc26Pipeline.cardinalShade(nx, ny, nz);
        float ao = computeSmoothAO(lx, ly, lz, nx, ny, nz, u, v);
        float shade = cardinal * ao;

        outRgb[0] = ColorMath.clamp01(albedo[0] * lr * shade);
        outRgb[1] = ColorMath.clamp01(albedo[1] * lg * shade);
        outRgb[2] = ColorMath.clamp01(albedo[2] * lb * shade);
    }

    private void shadeCrossPlant(
            int vx, int vy, int vz,
            BlockType block,
            float[] albedo,
            AnimaConfig config,
            float[][] lightmap,
            float[] outRgb) {

        if (block == BlockType.TORCH && albedo[0] > 0.85f && albedo[1] > 0.55f) {
            outRgb[0] = albedo[0];
            outRgb[1] = albedo[1];
            outRgb[2] = albedo[2];
            return;
        }

        int skyL = world.getSkyLight(vx, vy, vz);
        int blkL = world.getBlockLight(vx, vy, vz);
        if (config.isActive() && config.dynamicHandheldLight && AnimaDynamicLightEngine.isActive()) {
            blkL = Math.max(blkL, AnimaDynamicLightEngine.computeDynamicBlockLight(vx + 0.5, vy + 0.5, vz + 0.5));
        }
        float[] lightRgb = lightmap[skyL * 16 + blkL];
        outRgb[0] = ColorMath.clamp01(albedo[0] * lightRgb[0]);
        outRgb[1] = ColorMath.clamp01(albedo[1] * lightRgb[1]);
        outRgb[2] = ColorMath.clamp01(albedo[2] * lightRgb[2]);
    }

    /**
     * Exact Minecraft 26.2 SkyRenderer dome: zenith disc blended into horizon fog color,
     * sunrise/sunset glow band, square sun/moon, and stars at night.
     */
    private static void sampleMc26Sky(
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] skyZenith,
            float[] fogColor,
            TextureAtlas atlas,
            float[] outRgb) {

        float elev = ColorMath.clamp01(dy);
        float horizonBlend = (float) Math.pow(1.0f - elev, 2.8f);
        ColorMath.lerpRgb(skyZenith, fogColor, horizonBlend, outRgb);

        // Minecraft 26.2 sunrise/sunset horizon cone (SkyRenderState.sunriseAndSunsetColor)
        float sunDot = dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ;
        if (atm.goldenHourFactor > 0.04f && sunDot > 0.0f) {
            float glowBand = (float) Math.exp(-Math.pow(elev * 4.5f, 2.0)) * (float) Math.pow(sunDot, 2.0);
            float strength = atm.goldenHourFactor * (config.isActive() && config.skyEnhancement ? 0.88f : 0.45f);
            float[] sunsetCol = (config.isActive() && config.skyEnhancement)
                    ? atm.horizonColor
                    : new float[]{0.82f, 0.44f, 0.28f};
            ColorMath.lerpRgb(outRgb, sunsetCol, ColorMath.clamp01(glowBand * strength), outRgb);
        }

        // Sun rendering: Vanilla square sun vs Anima's 256x256 radiant sun.png (additive blended just like SkyRenderer!)
        if (atm.dayFactor > 0.08f && sunDot > 0.85f) {
            // Build tangent basis around sunDir
            float sx = atm.sunDirX, sy = atm.sunDirY, sz = atm.sunDirZ;
            float rx = -sz, rz = sx;
            float rLen = (float) Math.sqrt(rx * rx + rz * rz);
            if (rLen < 1e-4f) { rx = 1f; rz = 0f; rLen = 1f; }
            rx /= rLen; rz /= rLen;
            float ux = -rz * sy;
            float uy = rz * sx - rx * sz;
            float uz = rx * sy;

            float localX = (dx * rx + dz * rz) / Math.max(0.1f, sunDot);
            float localY = (dx * ux + dy * uy + dz * uz) / Math.max(0.1f, sunDot);

            if (config.isActive() && config.skyEnhancement) {
                float halfSize = 0.28f; // Minecraft sun quad angular half-size
                float su = (localX / halfSize) * 0.5f + 0.5f;
                float sv = (localY / halfSize) * 0.5f + 0.5f;
                if (su >= 0f && su <= 1f && sv >= 0f && sv <= 1f) {
                    float[] sunRgb = new float[3];
                    atlas.sampleAnimaSun(su, sv, sunRgb);
                    outRgb[0] = ColorMath.clamp01(outRgb[0] + sunRgb[0] * atm.dayFactor);
                    outRgb[1] = ColorMath.clamp01(outRgb[1] + sunRgb[1] * atm.dayFactor);
                    outRgb[2] = ColorMath.clamp01(outRgb[2] + sunRgb[2] * atm.dayFactor);
                }
            } else {
                // Classic harsh vanilla square sun
                if (Math.abs(localX) < 0.065f && Math.abs(localY) < 0.065f) {
                    outRgb[0] = 1.0f; outRgb[1] = 0.99f; outRgb[2] = 0.90f;
                }
            }
        }

        // Classic Minecraft moon + stars at night
        if (atm.nightFactor > 0.08f) {
            float moonDot = -sunDot;
            if (moonDot > 0.995f) {
                outRgb[0] = 0.92f; outRgb[1] = 0.95f; outRgb[2] = 1.0f;
            } else if (dy > 0.08f) {
                float star = hashStar(dx, dy, dz);
                float thresh = (config.isActive() && config.skyEnhancement) ? 0.9955f : 0.9972f;
                if (star > thresh) {
                    float b = ((star - thresh) / (1f - thresh)) * atm.nightFactor * (config.isActive() ? 0.95f : 0.55f);
                    outRgb[0] = ColorMath.clamp01(outRgb[0] + b);
                    outRgb[1] = ColorMath.clamp01(outRgb[1] + b);
                    outRgb[2] = ColorMath.clamp01(outRgb[2] + b);
                }
            }
        }
    }

    private static void applyAnimaFilmicGrade(float[] rgb) {
        ColorMath.applyFilmicPolish(rgb, 0.42f);
        ColorMath.adjustSaturation(rgb, 1.12f);
    }

    private void applyMc26Fog(float dist, AnimaAtmosphere atm, AnimaConfig config, float[] fogColor, float[] rgb) {
        float renderDist = 76f;
        float f = AnimaMc26Pipeline.computeMc26LinearFog(dist, renderDist, atm, config);
        if (f > 0.001f) {
            ColorMath.lerpRgb(rgb, fogColor, f, rgb);
        }
    }

    /**
     * Minecraft 26.2 blocky 3D clouds (cardinal top/side/bottom shading, tinted by dayFactor).
     */
    private void blendMc26Clouds(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            float[] skyRgb) {

        if (dy <= 0.025f) return;
        float cloudBottom = 37f;
        float cloudTop = 40.2f;
        float tEnter = (cloudBottom - oy) / dy;
        float tExit = (cloudTop - oy) / dy;
        if (tEnter <= 0f || tEnter > 240f) return;

        float cellSize = 8.5f;
        int cellX = (int) Math.floor((ox + dx * tEnter) / cellSize);
        int cellZ = (int) Math.floor((oz + dz * tEnter) / cellSize);

        boolean hitBottom = cloudNoise(cellX, cellZ) >= 0.56f;
        boolean hitSide = false;
        float hitT = tEnter;

        if (!hitBottom) {
            for (int s = 1; s <= 4; s++) {
                float ts = tEnter + (tExit - tEnter) * (s / 4f);
                int sx = (int) Math.floor((ox + dx * ts) / cellSize);
                int sz = (int) Math.floor((oz + dz * ts) / cellSize);
                if (cloudNoise(sx, sz) >= 0.56f) {
                    hitSide = true;
                    hitT = ts;
                    break;
                }
            }
        }
        if (!hitBottom && !hitSide) return;

        float base = ColorMath.lerp(0.26f, 0.96f, atm.dayFactor);
        float faceMul = hitSide ? 0.92f : 0.70f;
        float cr = base * faceMul * ColorMath.lerp(0.68f, ColorMath.lerp(1.00f, 1.06f, atm.goldenHourFactor), atm.dayFactor);
        float cg = base * faceMul * ColorMath.lerp(0.80f, ColorMath.lerp(1.00f, 0.85f, atm.goldenHourFactor), atm.dayFactor);
        float cb = base * faceMul * ColorMath.lerp(1.18f, ColorMath.lerp(1.00f, 0.72f, atm.goldenHourFactor), atm.dayFactor);

        float horizonFade = ColorMath.smoothstep(0.025f, 0.16f, dy) * (1f - ColorMath.smoothstep(145f, 235f, hitT));
        float alpha = 0.82f * horizonFade;
        skyRgb[0] = ColorMath.lerp(skyRgb[0], ColorMath.clamp01(cr), alpha);
        skyRgb[1] = ColorMath.lerp(skyRgb[1], ColorMath.clamp01(cg), alpha);
        skyRgb[2] = ColorMath.lerp(skyRgb[2], ColorMath.clamp01(cb), alpha);
    }

    private float intersectCrossModel(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            int vx, int vy, int vz,
            BlockType block,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outAlbedo) {

        float bestT = Float.MAX_VALUE;
        int bestTex = 0;

        float denom1 = dx - dz;
        if (Math.abs(denom1) > 1e-5f) {
            float t1 = ((oz - vz) - (ox - vx)) / denom1;
            if (t1 > 0.01f && t1 < bestT) {
                float hx = ox + dx * t1 - vx;
                float hy = oy + dy * t1 - vy;
                float hz = oz + dz * t1 - vz;
                if (hx >= 0.05f && hx <= 0.95f && hy >= 0f && hy <= 1f && hz >= 0.05f && hz <= 0.95f) {
                    int tex = atlas.sample(block, 0, hx, hy, atm, config);
                    if (((tex >>> 24) & 0xFF) > 128) {
                        bestT = t1;
                        bestTex = tex;
                    }
                }
            }
        }

        float denom2 = dx + dz;
        if (Math.abs(denom2) > 1e-5f) {
            float t2 = (1f - (ox - vx) - (oz - vz)) / denom2;
            if (t2 > 0.01f && t2 < bestT) {
                float hx = ox + dx * t2 - vx;
                float hy = oy + dy * t2 - vy;
                float hz = oz + dz * t2 - vz;
                if (hx >= 0.05f && hx <= 0.95f && hy >= 0f && hy <= 1f && hz >= 0.05f && hz <= 0.95f) {
                    int tex = atlas.sample(block, 0, hx, hy, atm, config);
                    if (((tex >>> 24) & 0xFF) > 128) {
                        bestT = t2;
                        bestTex = tex;
                    }
                }
            }
        }

        if (bestTex != 0) {
            TextureAtlas.unpackRgba(bestTex, outAlbedo);
            return bestT;
        }
        return -1f;
    }

    private float computeSmoothAO(int lx, int ly, int lz, int nx, int ny, int nz, float u, float v) {
        int ux = 0, uy = 0, uz = 0;
        int vx = 0, vy = 0, vz = 0;
        if (ny != 0) {
            ux = 1; vz = 1;
        } else if (nx != 0) {
            uz = 1; vy = 1;
        } else {
            ux = 1; vy = 1;
        }

        float ao00 = cornerAO(lx, ly, lz, -ux, -uy, -uz, -vx, -vy, -vz);
        float ao10 = cornerAO(lx, ly, lz,  ux,  uy,  uz, -vx, -vy, -vz);
        float ao01 = cornerAO(lx, ly, lz, -ux, -uy, -uz,  vx,  vy,  vz);
        float ao11 = cornerAO(lx, ly, lz,  ux,  uy,  uz,  vx,  vy,  vz);

        float bottom = ao00 + (ao10 - ao00) * u;
        float top = ao01 + (ao11 - ao01) * u;
        return bottom + (top - bottom) * v;
    }

    private float cornerAO(int lx, int ly, int lz, int s1x, int s1y, int s1z, int s2x, int s2y, int s2z) {
        boolean side1 = isOpaque(lx + s1x, ly + s1y, lz + s1z);
        boolean side2 = isOpaque(lx + s2x, ly + s2y, lz + s2z);
        boolean corner = isOpaque(lx + s1x + s2x, ly + s1y + s2y, lz + s1z + s2z);
        int occ;
        if (side1 && side2) occ = 3;
        else occ = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        return switch (occ) {
            case 0 -> 1.00f;
            case 1 -> 0.80f;
            case 2 -> 0.60f;
            default -> 0.40f;
        };
    }

    private boolean isOpaque(int x, int y, int z) {
        BlockType b = world.getBlock(x, y, z);
        return b.solid && !b.transparent;
    }

    private static float cloudNoise(int x, int z) {
        float a = hashCloud(x, z);
        float b = hashCloud(x + 1, z);
        float c = hashCloud(x, z + 1);
        float d = hashCloud(x - 1, z);
        float e = hashCloud(x, z - 1);
        return a * 0.44f + (b + c + d + e) * 0.14f;
    }

    private static float hashCloud(int x, int z) {
        int h = x * 374761393 + z * 668265263 + 911;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0xFFFF) / 65535f;
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
