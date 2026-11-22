package dev.anima.renderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads and parses an OptiFine / Iris shader pack (.zip), extracting its real GLSL
 * lighting/sky/blocklight color constants and deferred pipeline capabilities (shadow pass,
 * water SSR reflections, bloom, volumetric clouds) for side-by-side benchmarking in Anima-renderer.
 */
public final class ShaderPackProfile {

    public final String name;
    public final int glslFileCount;

    public final boolean hasShadowPass;
    public final boolean hasWaterReflections;
    public final boolean hasBloom;
    public final boolean hasVolumetricClouds;

    // Extracted RGB constants (defaults match Complementary Reimagined r5.10 if a generic pack omits them)
    public final float[] noonLightColor = {1.33f, 1.13f, 0.77f};
    public final float[] sunsetLightColor = {1.25f, 0.62f, 0.28f};
    public final float[] nightLightColor = {0.22f, 0.26f, 0.42f};
    public final float[] nightAmbientColor = {0.14f, 0.19f, 0.28f};
    public final float[] blockLightColor = {1.00f, 0.58f, 0.28f};

    private ShaderPackProfile(String name, int glslFileCount, boolean shadows, boolean refl, boolean bloom, boolean volClouds) {
        this.name = name;
        this.glslFileCount = glslFileCount;
        this.hasShadowPass = shadows;
        this.hasWaterReflections = refl;
        this.hasBloom = bloom;
        this.hasVolumetricClouds = volClouds;
    }

    public static ShaderPackProfile loadFromZip(Path zipPath, String displayName) throws IOException {
        int glslCount = 0;
        boolean shadows = false;
        boolean refl = false;
        boolean bloom = false;
        boolean volClouds = false;

        String lightAndAmbient = null;
        String blocklight = null;

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                String lower = n.toLowerCase();
                if (lower.endsWith(".glsl") || lower.endsWith(".vsh") || lower.endsWith(".fsh")) {
                    glslCount++;
                }
                if (lower.contains("shadow.")) shadows = true;
                if (lower.contains("reflection") || lower.contains("gbuffers_water")) refl = true;
                if (lower.contains("bloom")) bloom = true;
                if (lower.contains("cloud")) volClouds = true;

                if (lower.endsWith("lightandambientcolors.glsl")) {
                    lightAndAmbient = readText(zip, e);
                } else if (lower.endsWith("blocklightcolors.glsl")) {
                    blocklight = readText(zip, e);
                }
            }
        }

        String packName = (displayName != null && !displayName.isBlank())
                ? displayName
                : zipPath.getFileName().toString().replace(".zip", "");

        ShaderPackProfile profile = new ShaderPackProfile(packName, glslCount, shadows, refl, bloom, volClouds);

        if (lightAndAmbient != null) {
            float[] noon = parseVec3After(lightAndAmbient, "noonClearLightColor");
            if (noon != null) {
                // Complementary multiplies noonClearLightColor by ~2.05
                float scale = lightAndAmbient.contains("* 2.05") ? 2.05f : 1.0f;
                profile.noonLightColor[0] = noon[0] * scale;
                profile.noonLightColor[1] = noon[1] * scale;
                profile.noonLightColor[2] = noon[2] * scale;
            }
            float[] nightL = parseVec3After(lightAndAmbient, "nightClearLightColor");
            if (nightL != null) {
                profile.nightLightColor[0] = nightL[0] * 1.4f;
                profile.nightLightColor[1] = nightL[1] * 1.5f;
                profile.nightLightColor[2] = nightL[2] * 1.8f;
            }
            float[] nightA = parseVec3After(lightAndAmbient, "nightClearAmbientColor");
            if (nightA != null) {
                profile.nightAmbientColor[0] = nightA[0] * 1.4f;
                profile.nightAmbientColor[1] = nightA[1] * 1.4f;
                profile.nightAmbientColor[2] = nightA[2] * 1.5f;
            }
        }

        if (blocklight != null) {
            float[] blk = parseVec3After(blocklight, "blocklightCol");
            if (blk != null && blk[0] > 0.001f) {
                // Normalize so red channel peaks at 1.0 while preserving exact hue ratio
                float inv = 1.0f / blk[0];
                profile.blockLightColor[0] = 1.0f;
                profile.blockLightColor[1] = Math.min(1.0f, blk[1] * inv);
                profile.blockLightColor[2] = Math.min(1.0f, blk[2] * inv);
            }
        }

        return profile;
    }

    private static String readText(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static float[] parseVec3After(String src, String symbol) {
        int idx = src.indexOf(symbol);
        if (idx < 0) return null;
        String sub = src.substring(idx, Math.min(src.length(), idx + 180));
        Pattern p = Pattern.compile("vec3\\(\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)");
        Matcher m = p.matcher(sub);
        if (m.find()) {
            try {
                return new float[]{
                        Float.parseFloat(m.group(1)),
                        Float.parseFloat(m.group(2)),
                        Float.parseFloat(m.group(3))
                };
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("%s (%d GLSL files | shadows=%b, waterSSR=%b, bloom=%b, volClouds=%b)",
                name, glslFileCount, hasShadowPass, hasWaterReflections, hasBloom, hasVolumetricClouds);
    }
}
