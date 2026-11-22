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
        sg.drawString("ANIMA v0.10.0 (Lush) — Pixel-Art Sun & Crystal Wavy Water (Vanilla vs Anima Lush)", pad + 6, 30);
        sg.drawImage(sunVan, pad, headerH + pad, null);
        sg.drawImage(sunAni, pad * 2 + width, headerH + pad, null);
        drawBadge(sg, pad + 14, headerH + pad + 14, "VANILLA — Square Sun & Flat Water", new Color(25, 28, 35, 195), Color.WHITE);
        drawBadge(sg, pad * 2 + width + 14, headerH + pad + 14, "ANIMA v0.10.0 (Lush) — Crystal Water & Pixel-Art Sun", new Color(18, 48, 62, 210), new Color(165, 240, 255));
        sg.dispose();
        Path sunPath = outDir.resolve("sun_showcase.png");
        ImageIO.write(sunSheet, "png", sunPath.toFile());
        System.out.println("[Anima-renderer] Saved sun showcase -> " + sunPath.toAbsolutePath());

        // Dedicated 3-Mode Showcase: Vanilla vs Lush vs Borderlands (Cel-Shaded Comic)
        AnimaAtmosphere modeAtm = new AnimaAtmosphere(5200f, 0f);
        AnimaConfig borderlandsCfg = AnimaConfig.borderlands();
        int mw = 720, mh = 405;
        BufferedImage mVan = renderer.render(mw, mh, camX, camY, camZ, lookX, lookY, lookZ, fov, modeAtm, vanillaCfg, 2.5f);
        BufferedImage mLush = renderer.render(mw, mh, camX, camY, camZ, lookX, lookY, lookZ, fov, modeAtm, animaCfg, 2.5f);
        BufferedImage mBord = renderer.render(mw, mh, camX, camY, camZ, lookX, lookY, lookZ, fov, modeAtm, borderlandsCfg, 2.5f);
        BufferedImage modeSheet = new BufferedImage(mw * 3 + pad * 4, mh + pad * 2 + headerH, BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = modeSheet.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        mg.setColor(new Color(18, 20, 26));
        mg.fillRect(0, 0, modeSheet.getWidth(), modeSheet.getHeight());
        mg.setFont(new Font("SansSerif", Font.BOLD, 20));
        mg.setColor(new Color(235, 240, 248));
        mg.drawString("ANIMA v0.10.0 — Visual Modes: Off (Vanilla)  vs  Lush (Bloom + Filmic)  vs  Borderlands (Depth Outlines)", pad + 6, 30);
        mg.drawImage(mVan, pad, headerH + pad, null);
        mg.drawImage(mLush, pad * 2 + mw, headerH + pad, null);
        mg.drawImage(mBord, pad * 3 + mw * 2, headerH + pad, null);
        drawBadge(mg, pad + 12, headerH + pad + 12, "OFF (Vanilla)", new Color(25, 28, 35, 195), Color.WHITE);
        drawBadge(mg, pad * 2 + mw + 12, headerH + pad + 12, "LUSH (Cinematic)", new Color(18, 48, 62, 210), new Color(165, 240, 255));
        drawBadge(mg, pad * 3 + mw * 2 + 12, headerH + pad + 12, "BORDERLANDS (Cel-Shaded)", new Color(68, 40, 12, 220), new Color(255, 210, 90));
        mg.dispose();
        Path modePath = outDir.resolve("borderlands_showcase.png");
        ImageIO.write(modeSheet, "png", modePath.toFile());
        System.out.println("[Anima-renderer] Saved Borderlands mode showcase -> " + modePath.toAbsolutePath());

        // Optional 3-way Shader Pack Benchmark: Vanilla vs Anima v0.6.0 vs Iris/OptiFine Shader Pack (.zip)
        Path shaderZip = null;
        String shaderName = null;
        for (String arg : args) {
            if (arg.startsWith("--shaderpack=")) {
                shaderZip = Path.of(arg.substring("--shaderpack=".length()));
            } else if (arg.startsWith("--shadername=")) {
                shaderName = arg.substring("--shadername=".length());
            }
        }
        if (shaderZip == null) {
            Path spDir = Path.of("shaderpacks");
            if (Files.isDirectory(spDir)) {
                try (var stream = Files.list(spDir)) {
                    shaderZip = stream.filter(p -> p.toString().toLowerCase().endsWith(".zip")).findFirst().orElse(null);
                }
            }
        }

        if (shaderZip != null && Files.exists(shaderZip)) {
            ShaderPackProfile pack = ShaderPackProfile.loadFromZip(shaderZip, shaderName);
            System.out.println("[Anima-renderer] Loaded Shader Pack: " + pack);
            ShaderPackRenderer spRenderer = new ShaderPackRenderer(world, atlas);

            int cw = 720, ch = 405;
            BufferedImage cmp = new BufferedImage(cw * 3 + pad * 4, ch * scenarios.length + pad * (scenarios.length + 1) + headerH, BufferedImage.TYPE_INT_RGB);
            Graphics2D cg = cmp.createGraphics();
            cg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            cg.setColor(new Color(18, 20, 26));
            cg.fillRect(0, 0, cmp.getWidth(), cmp.getHeight());
            cg.setFont(new Font("SansSerif", Font.BOLD, 20));
            cg.setColor(new Color(235, 240, 248));
            cg.drawString("3-WAY BENCHMARK: Vanilla 26.2  vs  Anima v0.10.0 Lush (Bloom + Filmic)  vs  " + pack.name + " (" + pack.glslFileCount + " GLSL files)", pad + 6, 30);

            for (int i = 0; i < scenarios.length; i++) {
                Scenario s = scenarios[i];
                AnimaAtmosphere atm = new AnimaAtmosphere(s.dayTicks, s.rain);
                int rowY = headerH + pad + i * (ch + pad);

                // Col 1: Vanilla
                AnimaDynamicLightEngine.clear();
                long tv0 = System.currentTimeMillis();
                BufferedImage imgV = renderer.render(cw, ch, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, vanillaCfg, 2.5f);
                long tvMs = System.currentTimeMillis() - tv0;

                // Col 2: Anima v0.6.0
                if (s.holdingTorch) AnimaDynamicLightEngine.updateSource(camX + 1.8, camY - 1.1, camZ + 2.8, 14);
                else AnimaDynamicLightEngine.clear();
                long ta0 = System.currentTimeMillis();
                BufferedImage imgA = renderer.render(cw, ch, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, animaCfg, 2.5f);
                long taMs = System.currentTimeMillis() - ta0;

                // Col 3: Shader Pack (Deferred Shadows + Water SSR Reflections + Bloom + Tonemap)
                long ts0 = System.currentTimeMillis();
                BufferedImage imgS = spRenderer.render(cw, ch, camX, camY, camZ, lookX, lookY, lookZ, fov, atm, pack, 2.5f);
                long tsMs = System.currentTimeMillis() - ts0;
                AnimaDynamicLightEngine.clear();

                cg.drawImage(imgV, pad, rowY, null);
                cg.drawImage(imgA, pad * 2 + cw, rowY, null);
                cg.drawImage(imgS, pad * 3 + cw * 2, rowY, null);

                drawBadge(cg, pad + 10, rowY + 10, "VANILLA — " + s.id.toUpperCase() + " (" + tvMs + "ms)", new Color(25, 28, 35, 195), Color.WHITE);
                drawBadge(cg, pad * 2 + cw + 10, rowY + 10, "ANIMA v0.10.0 LUSH — " + s.id.toUpperCase() + " (" + taMs + "ms)", new Color(18, 48, 62, 210), new Color(165, 240, 255));
                drawBadge(cg, pad * 3 + cw * 2 + 10, rowY + 10, pack.name + " (" + tsMs + "ms)", new Color(58, 28, 18, 210), new Color(255, 215, 155));

                System.out.printf("[Anima-renderer] 3-Way %-7s | Vanilla: %3d ms | Anima: %3d ms | %s: %3d ms%n", s.id, tvMs, taMs, pack.name, tsMs);
            }
            cg.dispose();
            Path cmpPath = outDir.resolve("shader_comparison.png");
            ImageIO.write(cmp, "png", cmpPath.toFile());
            System.out.println("[Anima-renderer] Saved 3-way shader comparison -> " + cmpPath.toAbsolutePath());
        }
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
