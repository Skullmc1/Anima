package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for Anima-renderer.
 * Renders an authentic Minecraft gameplay scene (water, grass, oak tree, torches, sky, clouds)
 * running Anima-mod's actual dev.anima.core visual pipeline vs Vanilla Minecraft.
 */
public final class AnimaRendererMain {

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of("renders");
        Files.createDirectories(outDir);

        int width = 960;
        int height = 540;

        System.out.println("[Anima-renderer] Building Minecraft voxel world (96x48x96)...");
        long t0 = System.currentTimeMillis();
        VoxelWorld world = new VoxelWorld();
        TextureAtlas atlas = new TextureAtlas();
        VoxelRenderer renderer = new VoxelRenderer(world, atlas);
        System.out.printf("[Anima-renderer] World & lighting built in %d ms%n", System.currentTimeMillis() - t0);

        // Classic Minecraft first-person gameplay camera overlooking the grassy bank, pond, pier+torch, and hero oak tree
        float camX = 36.5f, camY = 22.8f, camZ = 25.5f;
        float lookX = 51.0f, lookY = 18.8f, lookZ = 45.0f;
        float fov = 68f;

        record Scenario(String id, String label, float dayTicks, float rain) {}
        Scenario[] scenarios = {
                new Scenario("day", "Midday (6000 ticks)", 5200f, 0f),
                new Scenario("golden", "Golden Hour (11450 ticks)", 11450f, 0f),
                new Scenario("night", "Moonlit Night (18000 ticks)", 18000f, 0f)
        };

        AnimaConfig vanillaCfg = AnimaConfig.vanilla();
        AnimaConfig animaCfg = AnimaConfig.forPreset(AnimaConfig.Preset.LUSH);

        BufferedImage[][] shots = new BufferedImage[scenarios.length][2];

        for (int i = 0; i < scenarios.length; i++) {
            Scenario s = scenarios[i];
            AnimaAtmosphere atm = new AnimaAtmosphere(s.dayTicks, s.rain);

            long s0 = System.currentTimeMillis();
            shots[i][0] = renderer.render(width, height, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, vanillaCfg, 2.5f);
            long tVan = System.currentTimeMillis() - s0;
            ImageIO.write(shots[i][0], "png", outDir.resolve(s.id + "_vanilla.png").toFile());

            long s1 = System.currentTimeMillis();
            shots[i][1] = renderer.render(width, height, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, animaCfg, 2.5f);
            long tAni = System.currentTimeMillis() - s1;
            ImageIO.write(shots[i][1], "png", outDir.resolve(s.id + "_anima.png").toFile());

            System.out.printf("[Anima-renderer] Rendered %-12s | Vanilla: %4d ms | Anima (Lush): %4d ms%n", s.id, tVan, tAni);
        }

        // Compose side-by-side comparison sheet
        int pad = 12;
        int headerH = 44;
        int sheetW = width * 2 + pad * 3;
        int sheetH = height * scenarios.length + pad * (scenarios.length + 1) + headerH;
        BufferedImage sheet = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(18, 20, 26));
        g.fillRect(0, 0, sheetW, sheetH);

        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.setColor(new Color(235, 240, 248));
        g.drawString("ANIMA MOD — Shader-Free Atmospheric Visuals for Minecraft 26.2 (Vanilla vs Anima Lush)", pad + 6, 30);

        for (int i = 0; i < scenarios.length; i++) {
            int y = headerH + pad + i * (height + pad);
            g.drawImage(shots[i][0], pad, y, null);
            g.drawImage(shots[i][1], pad * 2 + width, y, null);

            drawBadge(g, pad + 14, y + 14, "VANILLA — " + scenarios[i].label, new Color(25, 28, 35, 195), Color.WHITE);
            drawBadge(g, pad * 2 + width + 14, y + 14, "ANIMA (LUSH) — " + scenarios[i].label, new Color(18, 48, 62, 210), new Color(165, 240, 255));
        }
        g.dispose();

        Path sheetPath = outDir.resolve("comparison_sheet.png");
        ImageIO.write(sheet, "png", sheetPath.toFile());
        System.out.println("[Anima-renderer] Saved comparison sheet -> " + sheetPath.toAbsolutePath());
    }

    private static void drawBadge(Graphics2D g, int x, int y, String text, Color bg, Color fg) {
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 20;
        int h = fm.getHeight() + 10;
        g.setColor(bg);
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(fg);
        g.drawString(text, x + 10, y + fm.getAscent() + 5);
    }
}
