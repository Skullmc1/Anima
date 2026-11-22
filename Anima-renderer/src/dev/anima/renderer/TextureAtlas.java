package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaShadingEngine;
import dev.anima.core.ColorMath;

/**
 * Procedural 16x16 Minecraft-style pixel-art textures for every block face.
 * Nearest-neighbor sampled so blocks retain authentic crisp Minecraft pixel art.
 */
public final class TextureAtlas {

    public static final int SIZE = 16;

    private final int[] grassTop = new int[SIZE * SIZE];
    private final int[] grassSide = new int[SIZE * SIZE];
    private final int[] dirt = new int[SIZE * SIZE];
    private final int[] stone = new int[SIZE * SIZE];
    private final int[] cobble = new int[SIZE * SIZE];
    private final int[] sand = new int[SIZE * SIZE];
    private final int[] gravel = new int[SIZE * SIZE];
    private final int[] oakLogSide = new int[SIZE * SIZE];
    private final int[] oakLogTop = new int[SIZE * SIZE];
    private final int[] oakLeaves = new int[SIZE * SIZE];
    private final int[] oakPlanks = new int[SIZE * SIZE];
    private final int[] tallGrass = new int[SIZE * SIZE];
    private final int[] poppy = new int[SIZE * SIZE];
    private final int[] dandelion = new int[SIZE * SIZE];
    private final int[] torchTex = new int[SIZE * SIZE];

    public TextureAtlas() {
        generateDirt();
        generateGrassTop();
        generateGrassSide();
        generateStone();
        generateCobble();
        generateSand();
        generateGravel();
        generateOakLog();
        generateOakLeaves();
        generateOakPlanks();
        generateTallGrass();
        generateFlower(poppy, 0xFFE02828, 0xFFFF5A3C);
        generateFlower(dandelion, 0xFFF2D422, 0xFFFFEA55);
        generateTorch();
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0xFFFF) / 65535f;
    }

    private static int pack(int a, int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void generateDirt() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float n = hash(x, y, 11);
                int r = 132 + (int) ((n - 0.5f) * 34);
                int g = 94 + (int) ((n - 0.5f) * 26);
                int b = 64 + (int) ((n - 0.5f) * 20);
                if (hash(x, y, 17) > 0.88f) {
                    // tiny pebble speckle
                    r += 22; g += 20; b += 18;
                }
                dirt[y * SIZE + x] = pack(255, r, g, b);
            }
        }
    }

    private void generateGrassTop() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float n = hash(x, y, 23);
                float clump = hash(x / 2, y / 2, 29);
                float v = n * 0.65f + clump * 0.35f;
                int r = 108 + (int) ((v - 0.5f) * 30);
                int g = 172 + (int) ((v - 0.5f) * 36);
                int b = 76 + (int) ((v - 0.5f) * 20);
                grassTop[y * SIZE + x] = pack(255, r, g, b);
            }
        }
    }

    private void generateGrassSide() {
        int[] edgeDepth = {3, 4, 3, 2, 4, 5, 3, 3, 4, 2, 3, 5, 4, 3, 4, 3};
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (y < edgeDepth[x]) {
                    grassSide[y * SIZE + x] = grassTop[y * SIZE + x];
                } else if (y == edgeDepth[x]) {
                    // subtle shadow under grass overhang
                    int d = dirt[y * SIZE + x];
                    int r = (int) (((d >> 16) & 0xFF) * 0.80f);
                    int g = (int) (((d >> 8) & 0xFF) * 0.80f);
                    int b = (int) ((d & 0xFF) * 0.80f);
                    grassSide[y * SIZE + x] = pack(255, r, g, b);
                } else {
                    grassSide[y * SIZE + x] = dirt[y * SIZE + x];
                }
            }
        }
    }

    private void generateStone() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float n = hash(x, y, 31) * 0.55f + hash(x / 2, y, 37) * 0.45f;
                int base = 122 + (int) ((n - 0.5f) * 34);
                stone[y * SIZE + x] = pack(255, base, base + 2, base + 5);
            }
        }
    }

    private void generateCobble() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean mortar = (y % 4 == 0) || ((x + (y / 4) * 3) % 5 == 0);
                float n = hash(x, y, 41);
                int base = mortar ? 82 + (int) (n * 16) : 124 + (int) ((n - 0.5f) * 38);
                cobble[y * SIZE + x] = pack(255, base, base + 1, base + 3);
            }
        }
    }

    private void generateSand() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float n = hash(x, y, 47);
                int r = 218 + (int) ((n - 0.5f) * 24);
                int g = 204 + (int) ((n - 0.5f) * 22);
                int b = 154 + (int) ((n - 0.5f) * 24);
                sand[y * SIZE + x] = pack(255, r, g, b);
            }
        }
    }

    private void generateGravel() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float n = hash(x, y, 53);
                int base = 112 + (int) ((n - 0.5f) * 52);
                gravel[y * SIZE + x] = pack(255, base + 4, base + 2, base);
            }
        }
    }

    private void generateOakLog() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // Vertical bark grooves
                float streak = hash(x, y / 3, 61);
                float detail = hash(x, y, 67);
                float v = streak * 0.7f + detail * 0.3f;
                int r = 104 + (int) ((v - 0.5f) * 38);
                int g = 82 + (int) ((v - 0.5f) * 30);
                int b = 52 + (int) ((v - 0.5f) * 22);
                oakLogSide[y * SIZE + x] = pack(255, r, g, b);

                // Concentric wood rings on top
                float dx = x - 7.5f;
                float dy = y - 7.5f;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 6.6f) {
                    oakLogTop[y * SIZE + x] = oakLogSide[y * SIZE + x];
                } else {
                    boolean ring = ((int) dist) % 2 == 0;
                    int tr = (ring ? 168 : 146) + (int) ((detail - 0.5f) * 18);
                    int tg = (ring ? 136 : 114) + (int) ((detail - 0.5f) * 16);
                    int tb = (ring ? 88 : 72) + (int) ((detail - 0.5f) * 12);
                    oakLogTop[y * SIZE + x] = pack(255, tr, tg, tb);
                }
            }
        }
    }

    private void generateOakLeaves() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float hole = hash(x, y, 71);
                // Fancy leaves have airy cutouts (~18% transparent pixels)
                if (hole < 0.17f && (x > 0 && x < 15 && y > 0 && y < 15)) {
                    oakLeaves[y * SIZE + x] = 0x00000000;
                } else {
                    float n = hash(x, y, 73) * 0.65f + hash(x / 2, y / 2, 79) * 0.35f;
                    int r = 74 + (int) ((n - 0.5f) * 32);
                    int g = 142 + (int) ((n - 0.5f) * 44);
                    int b = 52 + (int) ((n - 0.5f) * 24);
                    oakLeaves[y * SIZE + x] = pack(255, r, g, b);
                }
            }
        }
    }

    private void generateOakPlanks() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean seam = (y % 4 == 0) || ((y / 4 == 1 || y / 4 == 3) && x == 8) || ((y / 4 == 0 || y / 4 == 2) && x == 0);
                float n = hash(x / 2, y, 83);
                int r = (seam ? 118 : 164) + (int) ((n - 0.5f) * 22);
                int g = (seam ? 92 : 132) + (int) ((n - 0.5f) * 18);
                int b = (seam ? 56 : 82) + (int) ((n - 0.5f) * 14);
                oakPlanks[y * SIZE + x] = pack(255, r, g, b);
            }
        }
    }

    private void generateTallGrass() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int blades = 0;
                // 5 organic grass blades rising from bottom (y=15) upward
                if (x == 3 && y >= 6) blades = 1;
                if (x == 4 && y >= 9) blades = 1;
                if (x == 6 && y >= 4) blades = 1;
                if (x == 7 && y >= 7) blades = 1;
                if (x == 9 && y >= 5) blades = 1;
                if (x == 10 && y >= 8) blades = 1;
                if (x == 12 && y >= 6) blades = 1;
                if (x == 2 && y >= 11) blades = 1;
                if (x == 13 && y >= 10) blades = 1;
                if (blades == 1) {
                    float tip = 1.0f - (y / 15f);
                    int r = 78 + (int) (tip * 36);
                    int g = 144 + (int) (tip * 44);
                    int b = 52 + (int) (tip * 18);
                    tallGrass[y * SIZE + x] = pack(255, r, g, b);
                } else {
                    tallGrass[y * SIZE + x] = 0x00000000;
                }
            }
        }
    }

    private void generateFlower(int[] target, int petalMain, int petalHighlight) {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((x == 7 || x == 8) && y >= 9 && y <= 15) {
                    target[y * SIZE + x] = pack(255, 68, 134, 48);
                } else if ((x == 6 && y == 12) || (x == 9 && y == 11)) {
                    target[y * SIZE + x] = pack(255, 82, 152, 54);
                } else if (x >= 6 && x <= 9 && y >= 5 && y <= 8) {
                    boolean center = (x == 7 || x == 8) && (y == 6 || y == 7);
                    target[y * SIZE + x] = center ? petalHighlight : petalMain;
                } else {
                    target[y * SIZE + x] = 0x00000000;
                }
            }
        }
    }

    private void generateTorch() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if ((x == 7 || x == 8) && y >= 7 && y <= 15) {
                    torchTex[y * SIZE + x] = pack(255, 132, 92, 52);
                } else if ((x == 7 || x == 8) && y >= 4 && y <= 6) {
                    // Glowing ember & flame
                    torchTex[y * SIZE + x] = (y == 4) ? pack(255, 255, 245, 170) : pack(255, 255, 176, 52);
                } else {
                    torchTex[y * SIZE + x] = 0x00000000;
                }
            }
        }
    }

    /**
     * Samples the ARGB texel at UV (u, v in 0..1) for a block face, applying Anima's
     * foliage vibrancy enhancement when appropriate.
     */
    public int sample(BlockType type, int faceNormalY, float u, float v, AnimaAtmosphere atm, AnimaConfig config) {
        int tx = Math.max(0, Math.min(SIZE - 1, (int) (u * SIZE)));
        int ty = Math.max(0, Math.min(SIZE - 1, (int) ((1f - v) * SIZE)));
        int idx = ty * SIZE + tx;

        int argb = switch (type) {
            case GRASS -> faceNormalY > 0 ? grassTop[idx] : (faceNormalY < 0 ? dirt[idx] : grassSide[idx]);
            case DIRT -> dirt[idx];
            case STONE -> stone[idx];
            case COBBLESTONE -> cobble[idx];
            case SAND -> sand[idx];
            case GRAVEL -> gravel[idx];
            case OAK_LOG -> faceNormalY != 0 ? oakLogTop[idx] : oakLogSide[idx];
            case OAK_LEAVES -> oakLeaves[idx];
            case OAK_PLANKS -> oakPlanks[idx];
            case TALL_GRASS -> tallGrass[idx];
            case POPPY -> poppy[idx];
            case DANDELION -> dandelion[idx];
            case TORCH -> torchTex[idx];
            default -> 0xFFFF00FF;
        };

        int a = (argb >>> 24) & 0xFF;
        if (a == 0) return 0;

        // Apply Anima's shader-free foliage enhancement to grass & leaves
        boolean isGrassGreen = (type == BlockType.GRASS && (faceNormalY > 0 || ty < 4))
                || type == BlockType.OAK_LEAVES
                || type == BlockType.TALL_GRASS;
        if (isGrassGreen && config.isActive() && config.foliageEnhancement) {
            int enhanced = AnimaShadingEngine.enhanceFoliageColor(argb & 0xFFFFFF, atm, config);
            return (a << 24) | enhanced;
        }
        return argb;
    }

    public static void unpackRgba(int argb, float[] outRgb) {
        ColorMath.unpackRgb(argb & 0xFFFFFF, outRgb);
    }
}
