package dev.anima.renderer;

import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;

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
        System.out.println("[Anima-renderer] Loaded REAL Minecraft 26.2 textures from: " + atlas.textureSource);
        VoxelRenderer renderer = new VoxelRenderer(world, atlas);
        System.out.printf("[Anima-renderer] World & lighting built in %d ms%n", System.currentTimeMillis() - t0);

        // Classic Minecraft first-person gameplay camera overlooking the grassy bank, pond, pier+torch, hero oak tree, and sky/sun
        float camX = 35.5f, camY = 22.4f, camZ = 24.5f;
        float lookX = 50.5f, lookY = 21.8f, lookZ = 44.0f;
        float fov = 68f;

        record Scenario(String id, String label, float dayTicks, float rain, boolean holdingTorch) {}
        Scenario[] scenarios = {
                new Scenario("day", "Midday (5200 ticks)", 5200f, 0f, false),
                new Scenario("golden", "Golden Hour + Radiant Sun (11050 ticks)", 11050f, 0f, false),
                new Scenario("night", "Moonlit Night + Offhand Torch (18000 ticks)", 18000f, 0f, true)
        };

        AnimaConfig vanillaCfg = AnimaConfig.vanilla();
        AnimaConfig animaCfg = AnimaConfig.forPreset(AnimaConfig.Preset.LUSH);

        BufferedImage[][] shots = new BufferedImage[scenarios.length][2];

        for (int i = 0; i < scenarios.length; i++) {
            Scenario s = scenarios[i];
            AnimaAtmosphere atm = new AnimaAtmosphere(s.dayTicks, s.rain);

            AnimaDynamicLightEngine.clear();
            long s0 = System.currentTimeMillis();
            shots[i][0] = renderer.render(width, height, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, vanillaCfg, 2.5f);
            long tVan = System.currentTimeMillis() - s0;
            ImageIO.write(shots[i][0], "png", outDir.resolve(s.id + "_vanilla.png").toFile());

            if (s.holdingTorch) {
                // Simulate player holding a Torch (luminance 14) in their offhand slightly forward/left of camera
                AnimaDynamicLightEngine.updateSource(camX + 1.8, camY - 1.1, camZ + 2.8, 14);
            } else {
                AnimaDynamicLightEngine.clear();
            }
            long s1 = System.currentTimeMillis();
            shots[i][1] = renderer.render(width, height, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, animaCfg, 2.5f);
            long tAni = System.currentTimeMillis() - s1;
            ImageIO.write(shots[i][1], "png", outDir.resolve(s.id + "_anima.png").toFile());
            AnimaDynamicLightEngine.clear();

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
        g.drawString("ANIMA MOD — Accurate Minecraft 26.2 Native Pipeline Preview (Vanilla vs Anima Lush)", pad + 6, 30);

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

        // Render 4-preset comparison at Golden Hour (Vanilla vs Potato vs Balanced vs Lush)
        AnimaAtmosphere goldenAtm = new AnimaAtmosphere(11350f, 0f);
        AnimaConfig.Preset[] presets = AnimaConfig.Preset.values();
        int pw = 640, ph = 360;

        // Warm up JIT across presets so displayed timings accurately reflect steady-state cost
        for (AnimaConfig.Preset p : presets) {
            renderer.render(160, 90, camX, camY, camZ, lookX, lookY, lookZ, fov, goldenAtm, AnimaConfig.forPreset(p), 2.5f);
        }

        BufferedImage presetSheet = new BufferedImage(pw * 2 + pad * 3, ph * 2 + pad * 3 + headerH, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = presetSheet.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        pg.setColor(new Color(18, 20, 26));
        pg.fillRect(0, 0, presetSheet.getWidth(), presetSheet.getHeight());
        pg.setFont(new Font("SansSerif", Font.BOLD, 18));
        pg.setColor(new Color(235, 240, 248));
        pg.drawString("ANIMA MOD — Quality Presets Comparison (Golden Hour, Zero GPU Shaders)", pad + 6, 30);

        for (int i = 0; i < presets.length; i++) {
            AnimaConfig pCfg = AnimaConfig.forPreset(presets[i]);
            long ps0 = System.currentTimeMillis();
            BufferedImage pImg = renderer.render(pw, ph, camX, camY, camZ, lookX, lookY, lookZ, fov, goldenAtm, pCfg, 2.5f);
            long pMs = System.currentTimeMillis() - ps0;
            int col = i % 2;
            int row = i / 2;
            int px = pad + col * (pw + pad);
            int py = headerH + pad + row * (ph + pad);
            pg.drawImage(pImg, px, py, null);
            Color badgeBg = (presets[i] == AnimaConfig.Preset.VANILLA) ? new Color(25, 28, 35, 195) : new Color(18, 48, 62, 210);
            Color badgeFg = (presets[i] == AnimaConfig.Preset.VANILLA) ? Color.WHITE : new Color(165, 240, 255);
            drawBadge(pg, px + 12, py + 12, presets[i].displayName() + " (" + pMs + " ms)", badgeBg, badgeFg);
        }
        pg.dispose();
        Path presetPath = outDir.resolve("presets_sheet.png");
        ImageIO.write(presetSheet, "png", presetPath.toFile());
        System.out.println("[Anima-renderer] Saved presets sheet -> " + presetPath.toAbsolutePath());

        // Dedicated Sun & Wavy Water comparison (camera aimed directly toward the Golden Hour sun over the pond!)
        AnimaAtmosphere sunAtm = new AnimaAtmosphere(10900f, 0f);
        float sCamX = 48.0f, sCamY = 21.5f, sCamZ = 22.0f;
        float sLookX = sCamX + sunAtm.sunDirX * 30f;
        float sLookY = sCamY + sunAtm.sunDirY * 22f - 2.0f;
        float sLookZ = sCamZ + sunAtm.sunDirZ * 30f;
        BufferedImage sunVan = renderer.render(width, height, sCamX, sCamY, sCamZ, sLookX, sLookY, sLookZ, 68f, sunAtm, vanillaCfg, 2.5f);
        BufferedImage sunAni = renderer.render(width, height, sCamX, sCamY, sCamZ, sLookX, sLookY, sLookZ, 68f, sunAtm, animaCfg, 2.5f);

        BufferedImage sunSheet = new BufferedImage(width * 2 + pad * 3, height + pad * 2 + headerH, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = sunSheet.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        sg.setColor(new Color(18, 20, 26));
        sg.fillRect(0, 0, sunSheet.getWidth(), sunSheet.getHeight());
        sg.setFont(new Font("SansSerif", Font.BOLD, 20));
        sg.setColor(new Color(235, 240, 248));
        sg.drawString("ANIMA v0.6.0 — Radiant Sun & Seamless Wavy Water (Vanilla vs Anima Lush)", pad + 6, 30);
        sg.drawImage(sunVan, pad, headerH + pad, null);
        sg.drawImage(sunAni, pad * 2 + width, headerH + pad, null);
        drawBadge(sg, pad + 14, headerH + pad + 14, "VANILLA — Square Sun & Flat Water", new Color(25, 28, 35, 195), Color.WHITE);
        drawBadge(sg, pad * 2 + width + 14, headerH + pad + 14, "ANIMA v0.6.0 — Radiant Sun & Seamless Wavy Water", new Color(18, 48, 62, 210), new Color(165, 240, 255));
        sg.dispose();
        Path sunPath = outDir.resolve("sun_showcase.png");
        ImageIO.write(sunSheet, "png", sunPath.toFile());
        System.out.println("[Anima-renderer] Saved sun showcase -> " + sunPath.toAbsolutePath());
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
