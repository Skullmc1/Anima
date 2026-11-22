package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import dev.anima.core.ColorMath;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Full Deferred Shader-Pack Renderer for Anima-renderer.
 * Simulates an OptiFine / Iris shader pack pipeline (e.g., Complementary Reimagined)
 * driven by color constants and features parsed from a real shader pack .zip:
 * - Directional sun shadow mapping (real cast shadows from trees, pier, terrain)
 * - Real 3D water reflections (SSR/planar-style reflection of trees, pier, hills, clouds, sky)
 * - Beer-Lambert water depth absorption + caustics + sun specular path
 * - Subsurface scattering (SSS) on oak leaves & tall grass
 * - Volumetric-style sunlit clouds + Mie sky scattering + radiant sun
 * - Post-processing Bloom pass + ACES filmic tonemapping
 */
public final class ShaderPackRenderer {

    private final VoxelWorld world;
    private final TextureAtlas atlas;

    public ShaderPackRenderer(VoxelWorld world, TextureAtlas atlas) {
        this.world = world;
        this.atlas = atlas;
    }

    public BufferedImage render(
            int width,
            int height,
            float camX, float camY, float camZ,
            float lookX, float lookY, float lookZ,
            float fovDeg,
            AnimaAtmosphere atm,
            ShaderPackProfile pack,
            float timeSeconds) {

        float fx = lookX - camX;
        float fy = lookY - camY;
        float fz = lookZ - camZ;
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fLen; fy /= fLen; fz /= fLen;

        float rx = -fz, rz = fx;
        float rLen = (float) Math.sqrt(rx * rx + rz * rz);
        rx /= rLen; rz /= rLen;

        float ux = -rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy;

        float aspect = (float) width / (float) height;
        float halfTan = (float) Math.tan(Math.toRadians(fovDeg * 0.5));

        final float fFx = fx, fFy = fy, fFz = fz;
        final float fRx = rx, fRz = rz;
        final float fUx = ux, fUy = uy, fUz = uz;

        // HDR linear framebuffer (float[width * height * 3]) for real Bloom + Filmic Tonemapping!
        float[] hdr = new float[width * height * 3];
        AnimaConfig vanillaTexCfg = AnimaConfig.vanilla();

        IntStream.range(0, height).parallel().forEach(py -> {
            float[] rgb = new float[3];
            float ndcY = 1f - 2f * ((py + 0.5f) / height);
            for (int px = 0; px < width; px++) {
                float ndcX = 2f * ((px + 0.5f) / width) - 1f;
                float dx = fFx + ndcX * aspect * halfTan * fRx + ndcY * halfTan * fUx;
                float dy = fFy + ndcY * halfTan * fUy;
                float dz = fFz + ndcX * aspect * halfTan * fRz + ndcY * halfTan * fUz;
                float dLen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= dLen; dy /= dLen; dz /= dLen;

                traceRay(camX, camY, camZ, dx, dy, dz, atm, pack, vanillaTexCfg, timeSeconds, true, rgb);
                int idx = (py * width + px) * 3;
                hdr[idx] = rgb[0];
                hdr[idx + 1] = rgb[1];
                hdr[idx + 2] = rgb[2];
            }
        });

        // Post-process Bloom pass (separable box/Gaussian approximation on HDR highlights > 1.0)
        if (pack.hasBloom) {
            applyBloom(hdr, width, height);
        }

        // ACES Filmic Tonemapping + Output sRGB
        int[] pixels = new int[width * height];
        IntStream.range(0, height).parallel().forEach(py -> {
            for (int px = 0; px < width; px++) {
                int idx = (py * width + px) * 3;
                float r = ColorMath.filmicCurve(hdr[idx] * 0.92f);
                float g = ColorMath.filmicCurve(hdr[idx + 1] * 0.92f);
                float b = ColorMath.filmicCurve(hdr[idx + 2] * 0.92f);
                pixels[py * width + px] = ColorMath.packRgb(r, g, b);
            }
        });

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private void traceRay(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            ShaderPackProfile pack,
            AnimaConfig texCfg,
            float timeSeconds,
            boolean allowReflections,
            float[] outRgb) {

        float maxDist = allowReflections ? 92f : 56f;
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
        float waterHitX = 0f, waterHitY = 0f, waterHitZ = 0f;
        int waterSkyL = 15;
        float waterBlkL = 0f;

        int maxSteps = allowReflections ? 220 : 110;
        for (int step = 0; step < maxSteps; step++) {
            if (t > maxDist) break;

            if (world.inBounds(vx, vy, vz)) {
                BlockType block = world.getBlock(vx, vy, vz);

                if (block == BlockType.WATER && !hitWater && allowReflections) {
                    hitWater = true;
                    waterHitT = t;
                    waterHitX = ox + dx * t;
                    waterHitY = oy + dy * t;
                    waterHitZ = oz + dz * t;
                    int aboveY = Math.min(VoxelWorld.SIZE_Y - 1, vy + 1);
                    waterSkyL = world.getSkyLight(vx, aboveY, vz);
                    waterBlkL = Math.max(world.getBlockLight(vx, vy, vz), world.getBlockLight(vx, aboveY, vz));
                    if (AnimaDynamicLightEngine.isActive()) {
                        waterBlkL = Math.max(waterBlkL, AnimaDynamicLightEngine.computeDynamicBlockLightFloat(waterHitX, waterHitY + 0.5, waterHitZ));
                    }
                } else if (block.crossModel) {
                    float[] plantRgb = new float[3];
                    float plantT = intersectCross(ox, oy, oz, dx, dy, dz, vx, vy, vz, block, atm, texCfg, plantRgb);
                    if (plantT >= 0f) {
                        shadeCross(vx, vy, vz, block, plantRgb, atm, pack, outRgb);
                        applyDeferredFog(plantT, oy + dy * plantT, dx, dy, dz, atm, pack, outRgb);
                        return;
                    }
                } else if (block.solid) {
                    float hx = ox + dx * t;
                    float hy = oy + dy * t;
                    float hz = oz + dz * t;
                    float u = (ny != 0) ? (hx - vx) : ((nx != 0) ? (hz - vz) : (hx - vx));
                    float v = (ny != 0) ? (hz - vz) : (hy - vy);
                    u = ColorMath.clamp01(u);
                    v = ColorMath.clamp01(v);

                    int texel = atlas.sample(block, ny, u, v, atm, texCfg);
                    if (((texel >>> 24) & 0xFF) > 128) {
                        float[] surf = new float[3];
                        shadeSolid(vx, vy, vz, nx, ny, nz, hx, hy, hz, u, v, block, texel, atm, pack, surf);

                        if (hitWater) {
                            shadeShaderPackWater(surf, Math.max(0.1f, t - waterHitT), waterHitX, waterHitY, waterHitZ, dx, dy, dz, waterSkyL / 15f, waterBlkL / 15f, timeSeconds, atm, pack, texCfg, outRgb);
                            applyDeferredFog(waterHitT, waterHitY, dx, dy, dz, atm, pack, outRgb);
                        } else {
                            outRgb[0] = surf[0]; outRgb[1] = surf[1]; outRgb[2] = surf[2];
                            applyDeferredFog(t, hy, dx, dy, dz, atm, pack, outRgb);
                        }
                        return;
                    }
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { t = tMaxX; tMaxX += tDeltaX; vx += stepX; nx = -stepX; ny = 0; nz = 0; }
                else { t = tMaxZ; tMaxZ += tDeltaZ; vz += stepZ; nx = 0; ny = 0; nz = -stepZ; }
            } else {
                if (tMaxY < tMaxZ) { t = tMaxY; tMaxY += tDeltaY; vy += stepY; nx = 0; ny = -stepY; nz = 0; }
                else { t = tMaxZ; tMaxZ += tDeltaZ; vz += stepZ; nx = 0; ny = 0; nz = -stepZ; }
            }
        }

        if (hitWater) {
            float[] deep = {0.03f, 0.12f, 0.22f};
            shadeShaderPackWater(deep, 6.0f, waterHitX, waterHitY, waterHitZ, dx, dy, dz, waterSkyL / 15f, waterBlkL / 15f, timeSeconds, atm, pack, texCfg, outRgb);
            applyDeferredFog(waterHitT, waterHitY, dx, dy, dz, atm, pack, outRgb);
            return;
        }

        sampleDeferredSky(dx, dy, dz, atm, pack, outRgb);
        if (pack.hasVolumetricClouds) {
            blendVolumetricClouds(ox, oy, oz, dx, dy, dz, atm, pack, outRgb);
        }
    }

    private void shadeSolid(
            int vx, int vy, int vz,
            int nx, int ny, int nz,
            float hx, float hy, float hz,
            float u, float v,
            BlockType block,
            int texel,
            AnimaAtmosphere atm,
            ShaderPackProfile pack,
            float[] outRgb) {

        float[] albedo = new float[3];
        TextureAtlas.unpackRgba(texel, albedo);

        int lx = vx + nx, ly = vy + ny, lz = vz + nz;
        float skyNorm = world.getSkyLight(lx, ly, lz) / 15f;
        float blkNorm = world.getBlockLight(lx, ly, lz) / 15f;
        if (AnimaDynamicLightEngine.isActive()) {
            blkNorm = Math.max(blkNorm, AnimaDynamicLightEngine.computeDynamicBlockLightFloat(hx + nx * 0.25, hy + ny * 0.25, hz + nz * 0.25) / 15f);
        }

        // Direct sun / moon light color from parsed shader pack GLSL!
        float[] directCol = new float[3];
        float[] dayCol = new float[3];
        ColorMath.lerpRgb(pack.noonLightColor, pack.sunsetLightColor, atm.goldenHourFactor, dayCol);
        ColorMath.lerpRgb(pack.nightLightColor, dayCol, atm.dayFactor, directCol);

        // Ambient sky bounce
        float[] ambCol = new float[3];
        float[] dayAmb = {0.42f, 0.56f, 0.82f};
        float[] sunsetAmb = {0.48f, 0.36f, 0.56f};
        float[] tmp = new float[3];
        ColorMath.lerpRgb(dayAmb, sunsetAmb, atm.goldenHourFactor, tmp);
        ColorMath.lerpRgb(pack.nightAmbientColor, tmp, atm.dayFactor, ambCol);

        // Real directional shadow map trace
        boolean inShadow = false;
        if (pack.hasShadowPass && atm.sunDirY > 0.04f) {
            inShadow = traceSunShadow(hx + nx * 0.02f, hy + ny * 0.02f, hz + nz * 0.02f, atm.sunDirX, atm.sunDirY, atm.sunDirZ);
        }

        float ndotl = Math.max(0f, nx * atm.sunDirX + ny * atm.sunDirY + nz * atm.sunDirZ);
        float sunVis = inShadow ? 0f : ndotl * skyNorm;

        // Subsurface scattering for sunlit leaves & foliage
        float sss = (block.foliage && !inShadow && atm.dayFactor > 0.1f) ? (0.28f + 0.25f * atm.goldenHourFactor) * skyNorm : 0f;

        float ao = computeSmoothAO(lx, ly, lz, nx, ny, nz, u, v);
        ao = (float) Math.pow(ao, 1.35f);

        float blkInt = (float) Math.pow(blkNorm, 1.45f) * (1.55f + 0.65f * atm.nightFactor);

        float lr = ambCol[0] * (0.25f + 0.75f * skyNorm) * ao + directCol[0] * (sunVis * 0.88f + sss * 0.55f) + pack.blockLightColor[0] * blkInt;
        float lg = ambCol[1] * (0.25f + 0.75f * skyNorm) * ao + directCol[1] * (sunVis * 0.88f + sss * 0.72f) + pack.blockLightColor[1] * blkInt;
        float lb = ambCol[2] * (0.25f + 0.75f * skyNorm) * ao + directCol[2] * (sunVis * 0.88f + sss * 0.18f) + pack.blockLightColor[2] * blkInt;

        outRgb[0] = albedo[0] * lr;
        outRgb[1] = albedo[1] * lg;
        outRgb[2] = albedo[2] * lb;
    }

    private void shadeCross(int vx, int vy, int vz, BlockType block, float[] albedo, AnimaAtmosphere atm, ShaderPackProfile pack, float[] outRgb) {
        if (block == BlockType.TORCH && albedo[0] > 0.85f && albedo[1] > 0.55f) {
            // HDR emissive flame triggers Bloom!
            outRgb[0] = albedo[0] * 2.4f;
            outRgb[1] = albedo[1] * 2.0f;
            outRgb[2] = albedo[2] * 1.4f;
            return;
        }
        float skyNorm = world.getSkyLight(vx, vy, vz) / 15f;
        float blkNorm = world.getBlockLight(vx, vy, vz) / 15f;
        if (AnimaDynamicLightEngine.isActive()) {
            blkNorm = Math.max(blkNorm, AnimaDynamicLightEngine.computeDynamicBlockLightFloat(vx + 0.5, vy + 0.5, vz + 0.5) / 15f);
        }
        float[] direct = new float[3];
        ColorMath.lerpRgb(pack.noonLightColor, pack.sunsetLightColor, atm.goldenHourFactor, direct);
        ColorMath.lerpRgb(pack.nightLightColor, direct, atm.dayFactor, direct);
        float blkInt = (float) Math.pow(blkNorm, 1.4f) * (1.5f + 0.6f * atm.nightFactor);

        outRgb[0] = albedo[0] * (direct[0] * skyNorm * 0.85f + 0.22f * atm.dayFactor + pack.blockLightColor[0] * blkInt);
        outRgb[1] = albedo[1] * (direct[1] * skyNorm * 0.85f + 0.25f * atm.dayFactor + pack.blockLightColor[1] * blkInt);
        outRgb[2] = albedo[2] * (direct[2] * skyNorm * 0.85f + 0.32f * atm.dayFactor + pack.blockLightColor[2] * blkInt);
    }

    private void shadeShaderPackWater(
            float[] bedRgb,
            float depth,
            float wx, float wy, float wz,
            float dx, float dy, float dz,
            float skyNorm, float blkNorm,
            float timeSeconds,
            AnimaAtmosphere atm,
            ShaderPackProfile pack,
            AnimaConfig texCfg,
            float[] outRgb) {

        // Multi-octave water wave normal
        float nx = (float) (Math.sin(wx * 2.8 + wz * 1.9 + timeSeconds * 2.4) * 0.055 + Math.cos(wx * 6.1 - wz * 4.7 - timeSeconds * 3.1) * 0.025);
        float nz = (float) (Math.cos(wx * 2.3 - wz * 3.1 + timeSeconds * 2.1) * 0.055 + Math.sin(wx * 5.4 + wz * 6.3 + timeSeconds * 2.8) * 0.025);
        float ny = 1.0f;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= nl; ny /= nl; nz /= nl;

        // Beer-Lambert depth absorption + shallow caustics
        float ar = (float) Math.exp(-depth * 0.52f);
        float ag = (float) Math.exp(-depth * 0.21f);
        float ab = (float) Math.exp(-depth * 0.14f);
        float caustic = 1.0f;
        if (depth < 4.5f && atm.dayFactor > 0.15f) {
            float c = Math.abs((float) (Math.sin(wx * 4.6 + wz * 3.4 + timeSeconds * 2.0) * Math.cos(wx * 3.8 - wz * 5.0 + timeSeconds * 1.7)));
            caustic = 1.0f + ColorMath.smoothstep(0.42f, 0.96f, c) * 0.45f * (1f - depth / 4.5f) * skyNorm * atm.dayFactor;
        }
        float[] waterTint = {0.04f * atm.dayFactor + 0.01f, 0.24f * atm.dayFactor + 0.04f, 0.42f * atm.dayFactor + 0.08f};
        float refrR = ColorMath.lerp(waterTint[0], bedRgb[0] * ar * caustic, ar * 0.85f + 0.15f);
        float refrG = ColorMath.lerp(waterTint[1], bedRgb[1] * ag * caustic, ag * 0.85f + 0.15f);
        float refrB = ColorMath.lerp(waterTint[2], bedRgb[2] * ab * caustic, ab * 0.85f + 0.15f);

        // Real 3D World Reflection (SSR / Planar-style reflection of oak tree, pier, hills, clouds, sky!)
        float dotVN = dx * nx + dy * ny + dz * nz;
        float rx = dx - 2f * dotVN * nx;
        float ry = Math.max(0.01f, dy - 2f * dotVN * ny);
        float rz = dz - 2f * dotVN * nz;

        float[] reflRgb = new float[3];
        if (pack.hasWaterReflections) {
            traceRay(wx + rx * 0.08f, wy + 0.06f, wz + rz * 0.08f, rx, ry, rz, atm, pack, texCfg, timeSeconds, false, reflRgb);
        } else {
            sampleDeferredSky(rx, ry, rz, atm, pack, reflRgb);
        }

        float cosTheta = Math.max(0.02f, -(dx * nx + dy * ny + dz * nz));
        float fresnel = ColorMath.fresnelSchlick(cosTheta, 0.05f) * (0.55f + 0.42f * skyNorm);

        outRgb[0] = ColorMath.lerp(refrR, reflRgb[0], fresnel);
        outRgb[1] = ColorMath.lerp(refrG, reflRgb[1], fresnel);
        outRgb[2] = ColorMath.lerp(refrB, reflRgb[2], fresnel);

        // HDR sun specular glints (triggers bloom!)
        float sunDotR = rx * atm.sunDirX + ry * atm.sunDirY + rz * atm.sunDirZ;
        if (atm.dayFactor > 0.05f && sunDotR > 0.90f) {
            float spec = (float) Math.pow((sunDotR - 0.90f) / 0.10f, 3.5) * (1.8f + 1.2f * atm.goldenHourFactor) * atm.dayFactor * skyNorm;
            outRgb[0] += atm.sunColor[0] * spec;
            outRgb[1] += atm.sunColor[1] * spec;
            outRgb[2] += atm.sunColor[2] * spec;
        }

        // Torch shimmer on water
        if (blkNorm > 0.05f) {
            float tg = blkNorm * blkNorm * (0.65f + 0.85f * atm.nightFactor);
            outRgb[0] += pack.blockLightColor[0] * tg;
            outRgb[1] += pack.blockLightColor[1] * tg;
            outRgb[2] += pack.blockLightColor[2] * tg;
        }
    }

    private void sampleDeferredSky(float dx, float dy, float dz, AnimaAtmosphere atm, ShaderPackProfile pack, float[] outRgb) {
        float elev = ColorMath.clamp01(dy);
        float h = (float) Math.pow(1f - elev, 2.2f);
        ColorMath.lerpRgb(atm.zenithColor, atm.horizonColor, h, outRgb);

        float sunDot = dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ;
        if (atm.dayFactor > 0.05f) {
            float mie = (float) Math.pow(Math.max(0f, sunDot), 6.0) * (0.35f + 0.75f * atm.goldenHourFactor) * atm.dayFactor;
            float disc = ColorMath.smoothstep(0.9965f, 0.9985f, sunDot) * 2.8f * atm.dayFactor; // HDR sun triggers bloom
            outRgb[0] += atm.sunColor[0] * mie + 1.0f * disc;
            outRgb[1] += atm.sunColor[1] * mie + 0.95f * disc;
            outRgb[2] += atm.sunColor[2] * mie + 0.85f * disc;
        }
        if (atm.nightFactor > 0.08f && dy > 0.06f) {
            int ix = (int) Math.floor(dx * 165f), iy = (int) Math.floor(dy * 165f), iz = (int) Math.floor(dz * 165f);
            int hash = ix * 374761393 + iy * 668265263 + iz * 2147483647;
            hash = (hash ^ (hash >> 13)) * 1274126177;
            float st = ((hash ^ (hash >> 16)) & 0xFFFF) / 65535f;
            if (st > 0.996f) {
                float b = ((st - 0.996f) / 0.004f) * atm.nightFactor * 1.4f;
                outRgb[0] += b * 0.85f; outRgb[1] += b * 0.92f; outRgb[2] += b;
            }
        }
    }

    private void blendVolumetricClouds(float ox, float oy, float oz, float dx, float dy, float dz, AnimaAtmosphere atm, ShaderPackProfile pack, float[] skyRgb) {
        if (dy <= 0.025f) return;
        float tEnter = (37f - oy) / dy;
        float tExit = (40.5f - oy) / dy;
        if (tEnter <= 0f || tEnter > 240f) return;
        float cell = 8.5f;
        boolean hitBot = cloudNoise((int) Math.floor((ox + dx * tEnter) / cell), (int) Math.floor((oz + dz * tEnter) / cell)) >= 0.56f;
        boolean hitSide = false;
        float hitT = tEnter;
        if (!hitBot) {
            for (int s = 1; s <= 4; s++) {
                float ts = tEnter + (tExit - tEnter) * (s / 4f);
                if (cloudNoise((int) Math.floor((ox + dx * ts) / cell), (int) Math.floor((oz + dz * ts) / cell)) >= 0.56f) {
                    hitSide = true; hitT = ts; break;
                }
            }
        }
        if (!hitBot && !hitSide) return;

        float[] top = {ColorMath.lerp(0.24f, ColorMath.lerp(1.15f, 1.25f, atm.goldenHourFactor), atm.dayFactor),
                       ColorMath.lerp(0.30f, ColorMath.lerp(1.12f, 0.82f, atm.goldenHourFactor), atm.dayFactor),
                       ColorMath.lerp(0.48f, ColorMath.lerp(1.08f, 0.58f, atm.goldenHourFactor), atm.dayFactor)};
        float[] bot = {ColorMath.lerp(0.10f, ColorMath.lerp(0.68f, 0.54f, atm.goldenHourFactor), atm.dayFactor),
                       ColorMath.lerp(0.14f, ColorMath.lerp(0.76f, 0.44f, atm.goldenHourFactor), atm.dayFactor),
                       ColorMath.lerp(0.26f, ColorMath.lerp(0.92f, 0.58f, atm.goldenHourFactor), atm.dayFactor)};
        float[] c = new float[3];
        ColorMath.lerpRgb(bot, top, hitSide ? 0.85f : 0.28f, c);
        float alpha = 0.90f * ColorMath.smoothstep(0.025f, 0.18f, dy) * (1f - ColorMath.smoothstep(145f, 235f, hitT));
        ColorMath.lerpRgb(skyRgb, c, alpha, skyRgb);
    }

    private void applyDeferredFog(float dist, float hy, float dx, float dy, float dz, AnimaAtmosphere atm, ShaderPackProfile pack, float[] rgb) {
        float f = 1.0f - (float) Math.exp(-Math.pow(dist / 78f * (1.05f + 0.5f * atm.goldenHourFactor), 1.65));
        float[] fogCol = {atm.fogColor[0], atm.fogColor[1], atm.fogColor[2]};
        float sunDot = Math.max(0f, dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ);
        float scatter = (float) Math.pow(sunDot, 4.5) * (0.28f + 0.55f * atm.goldenHourFactor) * atm.dayFactor;
        fogCol[0] += atm.sunColor[0] * scatter;
        fogCol[1] += atm.sunColor[1] * scatter;
        fogCol[2] += atm.sunColor[2] * scatter;
        ColorMath.lerpRgb(rgb, fogCol, ColorMath.clamp01(f * 0.72f), rgb);
    }

    /**
     * Separable 2-pass Bloom on HDR highlights (> 0.95) for authentic shader-pack glow!
     */
    private static void applyBloom(float[] hdr, int w, int h) {
        int bw = w / 2, bh = h / 2;
        float[] b1 = new float[bw * bh * 3];
        float[] b2 = new float[bw * bh * 3];

        // Downsample + bright-pass extract
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int si = ((y * 2) * w + (x * 2)) * 3;
                int di = (y * bw + x) * 3;
                float r = Math.max(0f, hdr[si] - 0.88f);
                float g = Math.max(0f, hdr[si + 1] - 0.88f);
                float b = Math.max(0f, hdr[si + 2] - 0.88f);
                b1[di] = r; b1[di + 1] = g; b1[di + 2] = b;
            }
        }
        // Horizontal blur (radius 6)
        int rad = 6;
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                float sr = 0, sg = 0, sb = 0, wt = 0;
                for (int k = -rad; k <= rad; k++) {
                    int sx = Math.max(0, Math.min(bw - 1, x + k));
                    float gW = 1f - Math.abs(k) / (float) (rad + 1);
                    int idx = (y * bw + sx) * 3;
                    sr += b1[idx] * gW; sg += b1[idx + 1] * gW; sb += b1[idx + 2] * gW; wt += gW;
                }
                int di = (y * bw + x) * 3;
                b2[di] = sr / wt; b2[di + 1] = sg / wt; b2[di + 2] = sb / wt;
            }
        }
        // Vertical blur + additive composite back onto HDR buffer
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                float sr = 0, sg = 0, sb = 0, wt = 0;
                for (int k = -rad; k <= rad; k++) {
                    int sy = Math.max(0, Math.min(bh - 1, y + k));
                    float gW = 1f - Math.abs(k) / (float) (rad + 1);
                    int idx = (sy * bw + x) * 3;
                    sr += b2[idx] * gW; sg += b2[idx + 1] * gW; sb += b2[idx + 2] * gW; wt += gW;
                }
                sr = (sr / wt) * 0.42f; sg = (sg / wt) * 0.42f; sb = (sb / wt) * 0.42f;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int py = y * 2 + dy, px = x * 2 + dx;
                        if (py < h && px < w) {
                            int hi = (py * w + px) * 3;
                            hdr[hi] += sr; hdr[hi + 1] += sg; hdr[hi + 2] += sb;
                        }
                    }
                }
            }
        }
    }

    private boolean traceSunShadow(float ox, float oy, float oz, float dx, float dy, float dz) {
        int vx = (int) Math.floor(ox), vy = (int) Math.floor(oy), vz = (int) Math.floor(oz);
        int stepX = dx >= 0 ? 1 : -1, stepY = dy >= 0 ? 1 : -1, stepZ = dz >= 0 ? 1 : -1;
        float tDeltaX = dx == 0 ? 1e30f : Math.abs(1f / dx);
        float tDeltaY = dy == 0 ? 1e30f : Math.abs(1f / dy);
        float tDeltaZ = dz == 0 ? 1e30f : Math.abs(1f / dz);
        float tMaxX = dx == 0 ? 1e30f : ((dx > 0 ? (vx + 1 - ox) : (ox - vx)) * tDeltaX);
        float tMaxY = dy == 0 ? 1e30f : ((dy > 0 ? (vy + 1 - oy) : (oy - vy)) * tDeltaY);
        float tMaxZ = dz == 0 ? 1e30f : ((dz > 0 ? (vz + 1 - oz) : (oz - vz)) * tDeltaZ);
        for (int i = 0; i < 42; i++) {
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { tMaxX += tDeltaX; vx += stepX; } else { tMaxZ += tDeltaZ; vz += stepZ; }
            } else {
                if (tMaxY < tMaxZ) { tMaxY += tDeltaY; vy += stepY; } else { tMaxZ += tDeltaZ; vz += stepZ; }
            }
            if (vy >= VoxelWorld.SIZE_Y || !world.inBounds(vx, vy, vz)) return false;
            if (world.getBlock(vx, vy, vz).solid) return true;
        }
        return false;
    }

    private float intersectCross(float ox, float oy, float oz, float dx, float dy, float dz, int vx, int vy, int vz, BlockType block, AnimaAtmosphere atm, AnimaConfig cfg, float[] outAlbedo) {
        float bestT = Float.MAX_VALUE; int bestTex = 0;
        float d1 = dx - dz;
        if (Math.abs(d1) > 1e-5f) {
            float t1 = ((oz - vz) - (ox - vx)) / d1;
            if (t1 > 0.01f && t1 < bestT) {
                float hx = ox + dx * t1 - vx, hy = oy + dy * t1 - vy, hz = oz + dz * t1 - vz;
                if (hx >= 0.05f && hx <= 0.95f && hy >= 0f && hy <= 1f && hz >= 0.05f && hz <= 0.95f) {
                    int tex = atlas.sample(block, 0, hx, hy, atm, cfg);
                    if (((tex >>> 24) & 0xFF) > 128) { bestT = t1; bestTex = tex; }
                }
            }
        }
        float d2 = dx + dz;
        if (Math.abs(d2) > 1e-5f) {
            float t2 = (1f - (ox - vx) - (oz - vz)) / d2;
            if (t2 > 0.01f && t2 < bestT) {
                float hx = ox + dx * t2 - vx, hy = oy + dy * t2 - vy, hz = oz + dz * t2 - vz;
                if (hx >= 0.05f && hx <= 0.95f && hy >= 0f && hy <= 1f && hz >= 0.05f && hz <= 0.95f) {
                    int tex = atlas.sample(block, 0, hx, hy, atm, cfg);
                    if (((tex >>> 24) & 0xFF) > 128) { bestT = t2; bestTex = tex; }
                }
            }
        }
        if (bestTex != 0) { TextureAtlas.unpackRgba(bestTex, outAlbedo); return bestT; }
        return -1f;
    }

    private float computeSmoothAO(int lx, int ly, int lz, int nx, int ny, int nz, float u, float v) {
        int ux = (ny != 0 || nz != 0) ? 1 : 0, uy = 0, uz = (nx != 0) ? 1 : 0;
        int vx = 0, vy = (ny == 0) ? 1 : 0, vz = (ny != 0) ? 1 : 0;
        float a00 = cornerAO(lx, ly, lz, -ux, -uy, -uz, -vx, -vy, -vz);
        float a10 = cornerAO(lx, ly, lz,  ux,  uy,  uz, -vx, -vy, -vz);
        float a01 = cornerAO(lx, ly, lz, -ux, -uy, -uz,  vx,  vy,  vz);
        float a11 = cornerAO(lx, ly, lz,  ux,  uy,  uz,  vx,  vy,  vz);
        return ColorMath.lerp(ColorMath.lerp(a00, a10, u), ColorMath.lerp(a01, a11, u), v);
    }

    private float cornerAO(int lx, int ly, int lz, int s1x, int s1y, int s1z, int s2x, int s2y, int s2z) {
        boolean b1 = isOpaque(lx + s1x, ly + s1y, lz + s1z);
        boolean b2 = isOpaque(lx + s2x, ly + s2y, lz + s2z);
        boolean c = isOpaque(lx + s1x + s2x, ly + s1y + s2y, lz + s1z + s2z);
        int occ = (b1 && b2) ? 3 : ((b1 ? 1 : 0) + (b2 ? 1 : 0) + (c ? 1 : 0));
        return switch (occ) { case 0 -> 1.0f; case 1 -> 0.80f; case 2 -> 0.60f; default -> 0.40f; };
    }

    private boolean isOpaque(int x, int y, int z) {
        BlockType b = world.getBlock(x, y, z);
        return b.solid && !b.transparent;
    }

    private static float cloudNoise(int x, int z) {
        return hashC(x, z) * 0.44f + (hashC(x + 1, z) + hashC(x, z + 1) + hashC(x - 1, z) + hashC(x, z - 1)) * 0.14f;
    }

    private static float hashC(int x, int z) {
        int h = x * 374761393 + z * 668265263 + 911;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0xFFFF) / 65535f;
    }
}
