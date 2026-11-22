package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaShadingEngine;
import dev.anima.core.ColorMath;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads REAL 16x16 Minecraft block textures directly from the Minecraft 26.2 client JAR
 * (including authentic Plains biome tinting for grayscale grass/foliage textures and
 * grass_block_side_overlay compositing).
 */
public final class TextureAtlas {

    public static final int SIZE = 16;

    // Authentic Minecraft Plains biome tint colors
    private static final int PLAINS_GRASS_TINT = 0x91BD59;
    private static final int PLAINS_FOLIAGE_TINT = 0x77AB2F;
    private static final int PLAINS_WATER_TINT = 0x3F76E4;

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
    private final int[] waterStill = new int[SIZE * SIZE];
    private BufferedImage animaSunImg;

    public final boolean loadedRealTextures;
    public final String textureSource;

    public TextureAtlas() throws IOException {
        Path mcJar = locateMinecraftClientJar();
        if (mcJar == null || !Files.exists(mcJar)) {
            throw new IOException("Could not locate Minecraft 26.2 client JAR in Anima-mod/.gradle/loom-cache. "
                    + "Build Anima-mod first or pass -Dmc.jar=<path-to-minecraft-jar>.");
        }

        try (ZipFile zip = new ZipFile(mcJar.toFile())) {
            loadTinted(zip, "assets/minecraft/textures/block/grass_block_top.png", PLAINS_GRASS_TINT, grassTop);
            loadGrassSide(zip, grassSide);
            loadDirect(zip, "assets/minecraft/textures/block/dirt.png", dirt);
            loadDirect(zip, "assets/minecraft/textures/block/stone.png", stone);
            loadDirect(zip, "assets/minecraft/textures/block/cobblestone.png", cobble);
            loadDirect(zip, "assets/minecraft/textures/block/sand.png", sand);
            loadDirect(zip, "assets/minecraft/textures/block/gravel.png", gravel);
            loadDirect(zip, "assets/minecraft/textures/block/oak_log.png", oakLogSide);
            loadDirect(zip, "assets/minecraft/textures/block/oak_log_top.png", oakLogTop);
            loadTinted(zip, "assets/minecraft/textures/block/oak_leaves.png", PLAINS_FOLIAGE_TINT, oakLeaves);
            loadDirect(zip, "assets/minecraft/textures/block/oak_planks.png", oakPlanks);
            loadTinted(zip, "assets/minecraft/textures/block/short_grass.png", PLAINS_GRASS_TINT, tallGrass);
            loadDirect(zip, "assets/minecraft/textures/block/poppy.png", poppy);
            loadDirect(zip, "assets/minecraft/textures/block/dandelion.png", dandelion);
            loadDirect(zip, "assets/minecraft/textures/block/torch.png", torchTex);
            loadDirect(zip, "assets/minecraft/textures/block/water_still.png", waterStill);
        }

        // Load Anima-mod's actual 256x256 radiant sun.png texture
        Path[] sunCandidates = {
                Path.of("..", "Anima-mod", "src", "client", "resources", "resourcepacks", "anima_shaders", "assets", "minecraft", "textures", "environment", "celestial", "sun.png"),
                Path.of("Anima-mod", "src", "client", "resources", "resourcepacks", "anima_shaders", "assets", "minecraft", "textures", "environment", "celestial", "sun.png")
        };
        for (Path sp : sunCandidates) {
            if (Files.exists(sp)) {
                animaSunImg = ImageIO.read(sp.toFile());
                break;
            }
        }

        this.loadedRealTextures = true;
        this.textureSource = mcJar.toAbsolutePath().toString();
    }

    private static Path locateMinecraftClientJar() throws IOException {
        String prop = System.getProperty("mc.jar");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.exists(p)) return p;
        }

        // Auto-discover from Anima-mod's Fabric Loom cache
        Path[] searchRoots = {
                Path.of("..", "Anima-mod", ".gradle", "loom-cache"),
                Path.of("Anima-mod", ".gradle", "loom-cache")
        };

        for (Path root : searchRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                Optional<Path> found = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("minecraft-clientOnly-") && name.endsWith(".jar") && !name.contains("sources");
                        })
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        return null;
    }

    private static BufferedImage readPng(ZipFile zip, String entryPath) throws IOException {
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) {
            throw new IOException("Missing Minecraft texture in JAR: " + entryPath);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IOException("Failed to decode PNG: " + entryPath);
            }
            return img;
        }
    }

    private static void loadDirect(ZipFile zip, String entryPath, int[] dst) throws IOException {
        BufferedImage img = readPng(zip, entryPath);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                dst[y * SIZE + x] = img.getRGB(x % img.getWidth(), y % img.getHeight());
            }
        }
    }

    private static void loadTinted(ZipFile zip, String entryPath, int tintRgb, int[] dst) throws IOException {
        BufferedImage img = readPng(zip, entryPath);
        int tr = (tintRgb >> 16) & 0xFF;
        int tg = (tintRgb >> 8) & 0xFF;
        int tb = tintRgb & 0xFF;

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int argb = img.getRGB(x % img.getWidth(), y % img.getHeight());
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    dst[y * SIZE + x] = 0;
                    continue;
                }
                int r = (((argb >> 16) & 0xFF) * tr) / 255;
                int g = (((argb >> 8) & 0xFF) * tg) / 255;
                int b = ((argb & 0xFF) * tb) / 255;
                dst[y * SIZE + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void loadGrassSide(ZipFile zip, int[] dst) throws IOException {
        BufferedImage base = readPng(zip, "assets/minecraft/textures/block/grass_block_side.png");
        BufferedImage overlay = readPng(zip, "assets/minecraft/textures/block/grass_block_side_overlay.png");

        int tr = (PLAINS_GRASS_TINT >> 16) & 0xFF;
        int tg = (PLAINS_GRASS_TINT >> 8) & 0xFF;
        int tb = PLAINS_GRASS_TINT & 0xFF;

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int bArgb = base.getRGB(x, y);
                int oArgb = overlay.getRGB(x, y);
                int oa = (oArgb >>> 24) & 0xFF;
                if (oa > 128) {
                    int r = (((oArgb >> 16) & 0xFF) * tr) / 255;
                    int g = (((oArgb >> 8) & 0xFF) * tg) / 255;
                    int b = ((oArgb & 0xFF) * tb) / 255;
                    dst[y * SIZE + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                } else {
                    dst[y * SIZE + x] = bArgb;
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

        boolean isGrassGreen = (type == BlockType.GRASS && (faceNormalY > 0 || ty < 5))
                || type == BlockType.OAK_LEAVES
                || type == BlockType.TALL_GRASS;
        if (isGrassGreen && config.isActive() && config.foliageEnhancement) {
            int enhanced = AnimaShadingEngine.enhanceFoliageColor(argb & 0xFFFFFF, atm, config);
            return (a << 24) | enhanced;
        }
        return argb;
    }

    /**
     * Samples Minecraft 26.2's real water_still.png tinted by BiomeColors.getAverageWaterColor
     * (enhanced by AnimaWaterEngine.enhanceBiomeWaterColor when Anima is enabled).
     */
    public void sampleWater(float u, float v, AnimaAtmosphere atm, AnimaConfig config, float[] outRgb) {
        int tx = Math.max(0, Math.min(SIZE - 1, (int) (u * SIZE)));
        int ty = Math.max(0, Math.min(SIZE - 1, (int) ((1f - v) * SIZE)));
        int texel = waterStill[ty * SIZE + tx];

        int waterTint = dev.anima.core.AnimaWaterEngine.enhanceBiomeWaterColor(PLAINS_WATER_TINT, atm, config);
        float tr = ((waterTint >> 16) & 0xFF) / 255f;
        float tg = ((waterTint >> 8) & 0xFF) / 255f;
        float tb = (waterTint & 0xFF) / 255f;

        outRgb[0] = (((texel >> 16) & 0xFF) / 255f) * tr;
        outRgb[1] = (((texel >> 8) & 0xFF) / 255f) * tg;
        outRgb[2] = ((texel & 0xFF) / 255f) * tb;
    }

    /**
     * Samples Anima-mod's actual 256x256 radiant sun.png at UV in [0, 1] (bilinear filtered).
     */
    public void sampleAnimaSun(float u, float v, float[] outRgb) {
        if (animaSunImg == null || u < 0f || u > 1f || v < 0f || v > 1f) {
            outRgb[0] = 0f; outRgb[1] = 0f; outRgb[2] = 0f;
            return;
        }
        int w = animaSunImg.getWidth();
        int h = animaSunImg.getHeight();
        int px = Math.max(0, Math.min(w - 1, (int) (u * w)));
        int py = Math.max(0, Math.min(h - 1, (int) (v * h)));
        int argb = animaSunImg.getRGB(px, py);
        outRgb[0] = ((argb >> 16) & 0xFF) / 255f;
        outRgb[1] = ((argb >> 8) & 0xFF) / 255f;
        outRgb[2] = (argb & 0xFF) / 255f;
    }

    public static void unpackRgba(int argb, float[] outRgb) {
        ColorMath.unpackRgb(argb & 0xFFFFFF, outRgb);
    }
}
