package dev.anima.renderer;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Generates an authentic Minecraft plains/riverbank gameplay scene with:
 * - Rolling grassy hills & dirt/stone strata
 * - A clear pond/stream with submerged sandy riverbed
 * - A hero Oak Tree near the shore + background oak trees
 * - Tall grass, poppies, dandelions, a small wooden pier, and glowing torches
 * - Real Minecraft 0..15 flood-fill sky light & block light propagation
 */
public final class VoxelWorld {

    public static final int SIZE_X = 96;
    public static final int SIZE_Y = 48;
    public static final int SIZE_Z = 96;
    public static final int WATER_LEVEL = 16;

    private final BlockType[] blocks = new BlockType[SIZE_X * SIZE_Y * SIZE_Z];
    private final byte[] skyLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];
    private final byte[] blockLight = new byte[SIZE_X * SIZE_Y * SIZE_Z];

    public VoxelWorld() {
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = BlockType.AIR;
        }
        generateTerrain();
        placeHeroFeatures();
        computeLighting();
    }

    private int index(int x, int y, int z) {
        return (y * SIZE_Z + z) * SIZE_X + x;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE_X && y >= 0 && y < SIZE_Y && z >= 0 && z < SIZE_Z;
    }

    public BlockType getBlock(int x, int y, int z) {
        if (!inBounds(x, y, z)) return BlockType.AIR;
        return blocks[index(x, y, z)];
    }

    public void setBlock(int x, int y, int z, BlockType type) {
        if (inBounds(x, y, z)) {
            blocks[index(x, y, z)] = type;
        }
    }

    public int getSkyLight(int x, int y, int z) {
        if (y >= SIZE_Y) return 15;
        if (!inBounds(x, y, z)) return 15;
        return skyLight[index(x, y, z)] & 0xFF;
    }

    public int getBlockLight(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0;
        return blockLight[index(x, y, z)] & 0xFF;
    }

    private static float hash2(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0xFFFF) / 65535f;
    }

    private static float smoothNoise(float x, float z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        float fx = x - ix;
        float fz = z - iz;
        fx = fx * fx * (3f - 2f * fx);
        fz = fz * fz * (3f - 2f * fz);

        float a = hash2(ix, iz);
        float b = hash2(ix + 1, iz);
        float c = hash2(ix, iz + 1);
        float d = hash2(ix + 1, iz + 1);
        float ab = a + (b - a) * fx;
        float cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    private static float fbm(float x, float z) {
        return smoothNoise(x * 0.055f, z * 0.055f) * 0.62f
                + smoothNoise(x * 0.12f, z * 0.12f) * 0.28f
                + smoothNoise(x * 0.25f, z * 0.25f) * 0.10f;
    }

    private void generateTerrain() {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                // Base rolling Minecraft plains hills
                float n = fbm(x + 12f, z + 7f);
                float baseHeight = 16.5f + (n - 0.45f) * 9.5f;

                // Gentle distant mountain ridge in the background (+Z / +X) for aerial perspective fog
                float distRidge = Math.max(0f, (z - 62f) / 30f);
                baseHeight += distRidge * distRidge * 12.0f * (0.5f + smoothNoise(x * 0.08f, z * 0.08f));

                // Carve a scenic meandering pond/river right through the foreground/midground
                float riverCenterX = 46f + (float) Math.sin(z * 0.095) * 6.5f;
                float dx = x - riverCenterX;
                float dzPond = (z - 42f) * 0.62f;
                float pondDist = (float) Math.sqrt(dx * dx + dzPond * dzPond);
                float riverCarve = Math.max(0f, 1f - Math.abs(dx) / 9.0f) * 4.5f;
                float pondCarve = Math.max(0f, 1f - pondDist / 12.5f) * 5.8f;

                float hFloat = baseHeight - Math.max(riverCarve, pondCarve);
                int h = Math.max(4, Math.min(SIZE_Y - 10, Math.round(hFloat)));

                for (int y = 0; y <= h; y++) {
                    if (y < h - 3) {
                        setBlock(x, y, z, BlockType.STONE);
                    } else if (y < h) {
                        setBlock(x, y, z, (h <= WATER_LEVEL + 1) ? BlockType.SAND : BlockType.DIRT);
                    } else {
                        if (h <= WATER_LEVEL) {
                            setBlock(x, y, z, (hash2(x + 5, z + 9) > 0.72f) ? BlockType.GRAVEL : BlockType.SAND);
                        } else if (h == WATER_LEVEL + 1 && pondDist < 13.5f) {
                            setBlock(x, y, z, BlockType.SAND);
                        } else {
                            setBlock(x, y, z, BlockType.GRASS);
                        }
                    }
                }

                // Fill water up to WATER_LEVEL
                if (h < WATER_LEVEL) {
                    for (int y = h + 1; y <= WATER_LEVEL; y++) {
                        setBlock(x, y, z, BlockType.WATER);
                    }
                }
            }
        }
    }

    private int surfaceY(int x, int z) {
        for (int y = SIZE_Y - 1; y >= 0; y--) {
            BlockType b = getBlock(x, y, z);
            if (b != BlockType.AIR && !b.crossModel) {
                return y;
            }
        }
        return 0;
    }

    private void placeHeroFeatures() {
        // 1. Hero Oak Tree on the right grassy knoll overlooking the pond
        int heroX = 57;
        int heroZ = 44;
        int heroY = surfaceY(heroX, heroZ);
        if (getBlock(heroX, heroY, heroZ) == BlockType.GRASS || getBlock(heroX, heroY, heroZ) == BlockType.SAND) {
            setBlock(heroX, heroY, heroZ, BlockType.DIRT);
            placeOakTree(heroX, heroY + 1, heroZ, 5);
        }

        // 2. Second lush Oak Tree on the left bank slightly further back
        placeTreeOnGrass(34, 52, 6);
        // 3. Distant trees on the hills for depth & silhouette
        placeTreeOnGrass(66, 61, 5);
        placeTreeOnGrass(26, 64, 6);
        placeTreeOnGrass(50, 68, 5);
        placeTreeOnGrass(72, 47, 5);

        // 4. Small cozy wooden pier jutting into the pond + glowing torch post!
        int pierZ = 41;
        for (int px = 50; px <= 54; px++) {
            for (int pz = pierZ; pz <= pierZ + 1; pz++) {
                setBlock(px, WATER_LEVEL + 1, pz, BlockType.OAK_PLANKS);
            }
        }
        // Support posts down into the riverbed
        for (int y = 10; y <= WATER_LEVEL; y++) {
            if (getBlock(50, y, pierZ) == BlockType.WATER) setBlock(50, y, pierZ, BlockType.OAK_LOG);
            if (getBlock(50, y, pierZ + 1) == BlockType.WATER) setBlock(50, y, pierZ + 1, BlockType.OAK_LOG);
        }
        // Torch post on the pier corner so warm block light dances on water, planks, and grass!
        setBlock(50, WATER_LEVEL + 2, pierZ, BlockType.OAK_LOG);
        setBlock(50, WATER_LEVEL + 3, pierZ, BlockType.TORCH);

        // Second warm torch near the base of the hero oak tree
        int tx = 54, tz = 45;
        int ty = surfaceY(tx, tz);
        if (getBlock(tx, ty, tz) == BlockType.GRASS) {
            setBlock(tx, ty + 1, tz, BlockType.COBBLESTONE);
            setBlock(tx, ty + 2, tz, BlockType.TORCH);
        }

        // 5. Natural tall grass & wildflowers across the grassy plains
        for (int x = 14; x < SIZE_X - 14; x++) {
            for (int z = 14; z < SIZE_Z - 14; z++) {
                int y = surfaceY(x, z);
                if (getBlock(x, y, z) == BlockType.GRASS && getBlock(x, y + 1, z) == BlockType.AIR) {
                    float r = hash2(x * 3 + 19, z * 5 + 31);
                    if (r > 0.84f) {
                        setBlock(x, y + 1, z, BlockType.TALL_GRASS);
                    } else if (r > 0.815f) {
                        setBlock(x, y + 1, z, BlockType.POPPY);
                    } else if (r > 0.79f) {
                        setBlock(x, y + 1, z, BlockType.DANDELION);
                    }
                }
            }
        }
    }

    private void placeTreeOnGrass(int x, int z, int trunkHeight) {
        int y = surfaceY(x, z);
        if (getBlock(x, y, z) == BlockType.GRASS) {
            setBlock(x, y, z, BlockType.DIRT);
            placeOakTree(x, y + 1, z, trunkHeight);
        }
    }

    private void placeOakTree(int x, int baseY, int z, int trunkHeight) {
        // Classic Minecraft oak canopy
        int topY = baseY + trunkHeight;
        for (int dy = -2; dy <= 1; dy++) {
            int radius = (dy <= -1) ? 2 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius) {
                        if (dy == 1 || hash2(x + dx + dy * 7, z + dz) > 0.45f) continue;
                    }
                    int lx = x + dx;
                    int ly = topY + dy;
                    int lz = z + dz;
                    if (getBlock(lx, ly, lz) == BlockType.AIR) {
                        setBlock(lx, ly, lz, BlockType.OAK_LEAVES);
                    }
                }
            }
        }
        // Trunk placed last so it rises cleanly into canopy
        for (int i = 0; i < trunkHeight; i++) {
            setBlock(x, baseY + i, z, BlockType.OAK_LOG);
        }
    }

    /**
     * Authentic Minecraft BFS voxel lighting propagation (sky light + block light).
     */
    private void computeLighting() {
        Queue<int[]> skyQueue = new ArrayDeque<>();
        Queue<int[]> blockQueue = new ArrayDeque<>();

        // 1. Seed direct vertical sky light from top of world
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                int light = 15;
                for (int y = SIZE_Y - 1; y >= 0; y--) {
                    BlockType b = getBlock(x, y, z);
                    if (b.occludesLight()) {
                        light = 0;
                    } else if (b == BlockType.OAK_LEAVES || b == BlockType.WATER) {
                        light = Math.max(0, light - 1);
                    }
                    if (light > 0) {
                        skyLight[index(x, y, z)] = (byte) light;
                        if (light < 15 || (y > 0 && getBlock(x, y - 1, z).occludesLight())) {
                            skyQueue.add(new int[]{x, y, z, light});
                        }
                    }
                    if (b.emissiveLight > 0) {
                        blockLight[index(x, y, z)] = (byte) b.emissiveLight;
                        blockQueue.add(new int[]{x, y, z, b.emissiveLight});
                    }
                }
            }
        }

        // Also seed columns adjacent to shaded areas for horizontal sky-light bleeding under trees
        for (int x = 1; x < SIZE_X - 1; x++) {
            for (int z = 1; z < SIZE_Z - 1; z++) {
                for (int y = 1; y < SIZE_Y - 1; y++) {
                    int sl = getSkyLight(x, y, z);
                    if (sl == 15) {
                        if (getSkyLight(x + 1, y, z) < 14 || getSkyLight(x - 1, y, z) < 14
                                || getSkyLight(x, y, z + 1) < 14 || getSkyLight(x, y, z - 1) < 14) {
                            skyQueue.add(new int[]{x, y, z, 15});
                        }
                    }
                }
            }
        }

        int[][] dirs = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};

        // 2. Flood-fill sky light
        while (!skyQueue.isEmpty()) {
            int[] cur = skyQueue.poll();
            int nextLight = cur[3] - 1;
            if (nextLight <= 0) continue;
            for (int[] d : dirs) {
                int nx = cur[0] + d[0], ny = cur[1] + d[1], nz = cur[2] + d[2];
                if (!inBounds(nx, ny, nz)) continue;
                BlockType b = getBlock(nx, ny, nz);
                if (b.occludesLight()) continue;
                int atten = (b == BlockType.WATER || b == BlockType.OAK_LEAVES) ? Math.max(0, nextLight - 1) : nextLight;
                int idx = index(nx, ny, nz);
                if (atten > (skyLight[idx] & 0xFF)) {
                    skyLight[idx] = (byte) atten;
                    skyQueue.add(new int[]{nx, ny, nz, atten});
                }
            }
        }

        // 3. Flood-fill block light (torches)
        while (!blockQueue.isEmpty()) {
            int[] cur = blockQueue.poll();
            int nextLight = cur[3] - 1;
            if (nextLight <= 0) continue;
            for (int[] d : dirs) {
                int nx = cur[0] + d[0], ny = cur[1] + d[1], nz = cur[2] + d[2];
                if (!inBounds(nx, ny, nz)) continue;
                BlockType b = getBlock(nx, ny, nz);
                if (b.occludesLight()) continue;
                int idx = index(nx, ny, nz);
                if (nextLight > (blockLight[idx] & 0xFF)) {
                    blockLight[idx] = (byte) nextLight;
                    blockQueue.add(new int[]{nx, ny, nz, nextLight});
                }
            }
        }
    }
}
