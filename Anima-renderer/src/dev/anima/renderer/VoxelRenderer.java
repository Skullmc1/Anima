package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import dev.anima.core.AnimaFogEngine;
import dev.anima.core.AnimaLightmapEngine;
import dev.anima.core.AnimaShadingEngine;
import dev.anima.core.AnimaSkyEngine;
import dev.anima.core.AnimaWaterEngine;
import dev.anima.core.ColorMath;

import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Multi-threaded 3D Minecraft Voxel Rendering Engine that executes Anima-mod's
 * actual dev.anima.core visual pipeline directly on a live Minecraft gameplay scene.
 */
public final class VoxelRenderer {

    private final VoxelWorld world;
    private final TextureAtlas atlas;

    public VoxelRenderer(VoxelWorld world, TextureAtlas atlas) {
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
            AnimaConfig config,
            float timeSeconds) {

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Build camera basis
        float fx = lookX - camX;
        float fy = lookY - camY;
        float fz = lookZ - camZ;
        float fLen = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fLen; fy /= fLen; fz /= fLen;

        // Right = normalize(cross(forward, up(0,1,0)))
        float rx = -fz;
        float ry = 0f;
        float rz = fx;
        float rLen = (float) Math.sqrt(rx * rx + rz * rz);
        rx /= rLen; rz /= rLen;

        // Up = cross(right, forward)
        float ux = ry * fz - rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy - ry * fx;

        float aspect = (float) width / (float) height;
        float halfTan = (float) Math.tan(Math.toRadians(fovDeg * 0.5));

        final float fFx = fx, fFy = fy, fFz = fz;
        final float fRx = rx, fRz = rz;
        final float fUx = ux, fUy = uy, fUz = uz;

        int[] pixels = new int[width * height];

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

                tracePrimaryRay(camX, camY, camZ, dx, dy, dz, atm, config, timeSeconds, rgb);
                pixels[py * width + px] = ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
            }
        });

        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private void tracePrimaryRay(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float timeSeconds,
            float[] outRgb) {

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
        float waterHitX = 0f, waterHitY = 0f, waterHitZ = 0f;
        int waterSkyLight = 15;
        int waterBlockLight = 0;

        for (int step = 0; step < 220; step++) {
            if (t > maxDist) break;

            if (world.inBounds(vx, vy, vz)) {
                BlockType block = world.getBlock(vx, vy, vz);

                if (block == BlockType.WATER && !hitWater) {
                    hitWater = true;
                    waterHitT = t;
                    waterHitX = ox + dx * t;
                    waterHitY = oy + dy * t;
                    waterHitZ = oz + dz * t;
                    int aboveY = Math.min(VoxelWorld.SIZE_Y - 1, vy + 1);
                    waterSkyLight = world.getSkyLight(vx, aboveY, vz);
                    waterBlockLight = Math.max(world.getBlockLight(vx, vy, vz), world.getBlockLight(vx, aboveY, vz));
                    if (config.isActive() && config.dynamicHandheldLight && AnimaDynamicLightEngine.isActive()) {
                        waterBlockLight = Math.max(waterBlockLight, AnimaDynamicLightEngine.computeDynamicBlockLight(waterHitX, waterHitY + 0.5, waterHitZ));
                    }
                } else if (block.crossModel) {
                    float[] plantRgb = new float[3];
                    float plantT = intersectCrossModel(ox, oy, oz, dx, dy, dz, vx, vy, vz, block, atm, config, plantRgb);
                    if (plantT >= 0f) {
                        shadeCrossPlant(vx, vy, vz, block, plantRgb, atm, config, timeSeconds, outRgb);
                        applyFog(ox + dx * plantT, oy + dy * plantT, oz + dz * plantT, plantT, dx, dy, dz, atm, config, outRgb);
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
                        shadeSolidHit(vx, vy, vz, nx, ny, nz, hx, hy, hz, u, v, block, texel, atm, config, timeSeconds, surfaceRgb);

                        if (hitWater) {
                            float waterDepth = Math.max(0.12f, t - waterHitT);
                            AnimaWaterEngine.shadeWaterSurface(
                                    surfaceRgb,
                                    waterDepth,
                                    waterHitX, waterHitZ,
                                    dx, dy, dz,
                                    waterSkyLight / 15f,
                                    waterBlockLight / 15f,
                                    timeSeconds,
                                    atm, config,
                                    outRgb);
                            applyFog(waterHitX, waterHitY, waterHitZ, waterHitT, dx, dy, dz, atm, config, outRgb);
                        } else {
                            outRgb[0] = surfaceRgb[0];
                            outRgb[1] = surfaceRgb[1];
                            outRgb[2] = surfaceRgb[2];
                            applyFog(hx, hy, hz, t, dx, dy, dz, atm, config, outRgb);
                        }
                        return;
                    }
                }
            }

            // Advance DDA to next voxel
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    vx += stepX;
                    nx = -stepX; ny = 0; nz = 0;
                } else {
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    vz += stepZ;
                    nx = 0; ny = 0; nz = -stepZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    t = tMaxY;
                    tMaxY += tDeltaY;
                    vy += stepY;
                    nx = 0; ny = -stepY; nz = 0;
                } else {
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    vz += stepZ;
                    nx = 0; ny = 0; nz = -stepZ;
                }
            }
        }

        // Ray missed geometry -> render Sky + 3D Blocky Minecraft Clouds
        if (hitWater) {
            float[] deepBed = {0.06f, 0.16f, 0.24f};
            AnimaWaterEngine.shadeWaterSurface(deepBed, 6.0f, waterHitX, waterHitZ, dx, dy, dz, waterSkyLight / 15f, waterBlockLight / 15f, timeSeconds, atm, config, outRgb);
            applyFog(waterHitX, waterHitY, waterHitZ, waterHitT, dx, dy, dz, atm, config, outRgb);
            return;
        }

        AnimaSkyEngine.sampleSky(dx, dy, dz, atm, config, outRgb);
        blendClouds(ox, oy, oz, dx, dy, dz, atm, config, outRgb);
    }

    private void shadeSolidHit(
            int vx, int vy, int vz,
            int nx, int ny, int nz,
            float hx, float hy, float hz,
            float u, float v,
            BlockType block,
            int texel,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float timeSeconds,
            float[] outRgb) {

        float[] albedo = new float[3];
        TextureAtlas.unpackRgba(texel, albedo);

        // Light sample from the adjacent air/water cell facing the hit face
        int lx = vx + nx;
        int ly = vy + ny;
        int lz = vz + nz;
        int skyL = world.getSkyLight(lx, ly, lz);
        int blkL = world.getBlockLight(lx, ly, lz);
        if (config.isActive() && config.dynamicHandheldLight && AnimaDynamicLightEngine.isActive()) {
            blkL = Math.max(blkL, AnimaDynamicLightEngine.computeDynamicBlockLight(hx + nx * 0.25, hy + ny * 0.25, hz + nz * 0.25));
        }

        float[] lightRgb = new float[3];
        AnimaLightmapEngine.computeLight(skyL, blkL, atm, config, timeSeconds, lightRgb);

        // Fast sun-shadow ray when Anima directional shading is active
        boolean inShadow = false;
        if (config.isActive() && config.directionalShading && atm.sunDirY > 0.04f) {
            inShadow = traceSunShadow(hx + nx * 0.02f, hy + ny * 0.02f, hz + nz * 0.02f, atm.sunDirX, atm.sunDirY, atm.sunDirZ);
        }

        float[] dirShade = new float[3];
        AnimaShadingEngine.computeDirectionalShade(nx, ny, nz, block.foliage, inShadow, atm, config, dirShade);

        // 4-corner smooth Minecraft vertex Ambient Occlusion
        float ao = computeSmoothAO(lx, ly, lz, nx, ny, nz, u, v);
        ao = AnimaShadingEngine.shapeAmbientOcclusion(ao, config);

        outRgb[0] = albedo[0] * lightRgb[0] * dirShade[0] * ao;
        outRgb[1] = albedo[1] * lightRgb[1] * dirShade[1] * ao;
        outRgb[2] = albedo[2] * lightRgb[2] * dirShade[2] * ao;

        if (config.isActive()) {
            ColorMath.applyFilmicPolish(outRgb, config.filmicStrength * 0.30f);
        }
        outRgb[0] = ColorMath.clamp01(outRgb[0]);
        outRgb[1] = ColorMath.clamp01(outRgb[1]);
        outRgb[2] = ColorMath.clamp01(outRgb[2]);
    }

    private void shadeCrossPlant(
            int vx, int vy, int vz,
            BlockType block,
            float[] albedo,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float timeSeconds,
            float[] outRgb) {

        if (block == BlockType.TORCH) {
            // Emissive glowing flame on top of torch!
            if (albedo[0] > 0.85f && albedo[1] > 0.55f) {
                outRgb[0] = Math.min(1f, albedo[0] * 1.15f);
                outRgb[1] = Math.min(1f, albedo[1] * 1.12f);
                outRgb[2] = Math.min(1f, albedo[2] * 1.05f);
                return;
            }
        }

        int skyL = world.getSkyLight(vx, vy, vz);
        int blkL = world.getBlockLight(vx, vy, vz);
        if (config.isActive() && config.dynamicHandheldLight && AnimaDynamicLightEngine.isActive()) {
            blkL = Math.max(blkL, AnimaDynamicLightEngine.computeDynamicBlockLight(vx + 0.5, vy + 0.5, vz + 0.5));
        }
        float[] lightRgb = new float[3];
        AnimaLightmapEngine.computeLight(skyL, blkL, atm, config, timeSeconds, lightRgb);

        float[] dirShade = new float[3];
        AnimaShadingEngine.computeDirectionalShade(0f, 1f, 0f, block.foliage, false, atm, config, dirShade);

        outRgb[0] = ColorMath.clamp01(albedo[0] * lightRgb[0] * dirShade[0]);
        outRgb[1] = ColorMath.clamp01(albedo[1] * lightRgb[1] * dirShade[1]);
        outRgb[2] = ColorMath.clamp01(albedo[2] * lightRgb[2] * dirShade[2]);
    }

    /**
     * Intersects the two diagonal X-planes inside a voxel for Minecraft tall grass, flowers, and torches.
     */
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

        // Plane 1: (x - vx) = (z - vz)  ->  (ox - vx - (oz - vz)) + t*(dx - dz) = 0
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

        // Plane 2: (x - vx) + (z - vz) = 1
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

    /**
     * Short shadow ray towards the sun to detect cast shadows from trees and terrain cliffs.
     */
    private boolean traceSunShadow(float ox, float oy, float oz, float dx, float dy, float dz) {
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

        for (int i = 0; i < 42; i++) {
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { tMaxX += tDeltaX; vx += stepX; }
                else { tMaxZ += tDeltaZ; vz += stepZ; }
            } else {
                if (tMaxY < tMaxZ) { tMaxY += tDeltaY; vy += stepY; }
                else { tMaxZ += tDeltaZ; vz += stepZ; }
            }
            if (vy >= VoxelWorld.SIZE_Y) return false;
            if (!world.inBounds(vx, vy, vz)) return false;
            BlockType b = world.getBlock(vx, vy, vz);
            if (b.solid) return true;
        }
        return false;
    }

    /**
     * Exact Minecraft 4-corner smooth Ambient Occlusion for crisp voxel crevices.
     */
    private float computeSmoothAO(int lx, int ly, int lz, int nx, int ny, int nz, float u, float v) {
        // Tangent vectors along u and v
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
            case 1 -> 0.82f;
            case 2 -> 0.64f;
            default -> 0.46f;
        };
    }

    private boolean isOpaque(int x, int y, int z) {
        BlockType b = world.getBlock(x, y, z);
        return b.solid && !b.transparent;
    }

    private void applyFog(
            float hx, float hy, float hz,
            float dist,
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] rgb) {

        float renderDist = config.isActive() ? 78f : 110f;
        float fogFactor = AnimaFogEngine.computeFogFactor(dist, hy, VoxelWorld.WATER_LEVEL, renderDist, atm, config);
        if (fogFactor <= 0.001f) return;

        float[] fogCol = new float[3];
        AnimaFogEngine.computeFogColor(dx, dy, dz, atm, config, fogCol);
        ColorMath.lerpRgb(rgb, fogCol, fogFactor, rgb);
    }

    /**
     * Renders true 3D blocky Minecraft clouds (slab from Y=37..40) with sunlit side/top faces
     * and cool shaded undersides for volumetric-style depth without volumetric shaders.
     */
    private void blendClouds(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] skyRgb) {

        if (dy <= 0.025f) return;
        float cloudBottom = 37f;
        float cloudTop = 40.2f;
        float tEnter = (cloudBottom - oy) / dy;
        float tExit = (cloudTop - oy) / dy;
        if (tEnter <= 0f || tEnter > 240f) return;

        float cellSize = 8.5f;
        float cx = ox + dx * tEnter;
        float cz = oz + dz * tEnter;
        int cellX = (int) Math.floor(cx / cellSize);
        int cellZ = (int) Math.floor(cz / cellSize);

        boolean hitBottom = cloudNoise(cellX, cellZ) >= 0.56f;
        boolean hitSide = false;
        float hitT = tEnter;

        if (!hitBottom) {
            // Check 3 samples along the slab for 3D vertical cloud side faces
            for (int s = 1; s <= 4; s++) {
                float ts = tEnter + (tExit - tEnter) * (s / 4f);
                int sx = (int) Math.floor((ox + dx * ts) / cellSize);
                int sz = (int) Math.floor((oz + dz * ts) / cellSize);
                if (cloudNoise(sx, sz) >= 0.56f) {
                    hitSide = true;
                    hitT = ts;
                    cellX = sx;
                    cellZ = sz;
                    break;
                }
            }
        }

        if (!hitBottom && !hitSide) return;

        float[] topCol = new float[3];
        float[] botCol = new float[3];
        AnimaSkyEngine.computeCloudColors(atm, config, topCol, botCol);

        float[] cloudCol = new float[3];
        if (hitSide) {
            // Bright sunlit vertical side face of 3D cloud
            float sunSide = 0.55f + 0.45f * ColorMath.clamp01(dx * atm.sunDirX + dz * atm.sunDirZ + 0.4f);
            ColorMath.lerpRgb(botCol, topCol, sunSide, cloudCol);
        } else {
            // Shaded cloud underside with subtle warm rim near the sun
            float sunRim = (float) Math.pow(Math.max(0f, dx * atm.sunDirX + dy * atm.sunDirY + dz * atm.sunDirZ), 3.0) * 0.45f;
            ColorMath.lerpRgb(botCol, topCol, 0.22f + sunRim, cloudCol);
        }

        float horizonFade = ColorMath.smoothstep(0.025f, 0.18f, dy) * (1f - ColorMath.smoothstep(145f, 235f, hitT));
        float alpha = 0.90f * horizonFade;
        ColorMath.lerpRgb(skyRgb, cloudCol, alpha, skyRgb);
    }

    private static float cloudNoise(int x, int z) {
        // Smooth 2x2 clustered blocky cloud shapes
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
}
