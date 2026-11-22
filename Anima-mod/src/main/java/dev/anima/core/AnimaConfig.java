package dev.anima.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration for Anima visual modes: Off (Vanilla), Lush, and Borderlands (Cel-Shaded).
 */
public final class AnimaConfig {

    public enum Preset {
        VANILLA("Off", "Vanilla"),
        LUSH("Lush", "Cinematic"),
        BORDERLANDS("Borderlands", "Cel-Shaded"),
        COLOR_PENCIL("Color Pencil", "Sketch"),
        BLUEPRINT("Blueprint", "Technical");

        private final String displayName;
        private final String subtitle;

        Preset(String displayName, String subtitle) {
            this.displayName = displayName;
            this.subtitle = subtitle;
        }

        public String displayName() {
            return displayName;
        }

        public String subtitle() {
            return subtitle;
        }
    }

    public boolean enabled = true;
    public Preset preset = Preset.LUSH;

    public boolean atmosphericLightmap = true;
    public boolean skyEnhancement = true;
    public boolean atmosphericFog = true;
    public boolean waterEnhancement = true;
    public boolean directionalShading = true;
    public boolean foliageEnhancement = true;
    public boolean dynamicHandheldLight = true;

    public float warmthStrength = 1.05f;
    public float shadowDepth = 0.88f;
    public float fogDensity = 0.62f;
    public float waterClarity = 0.92f;
    public float saturationBoost = 1.14f;
    public float filmicStrength = 0.58f;
    public float torchFlicker = 0.35f;

    public static AnimaConfig vanilla() {
        AnimaConfig c = new AnimaConfig();
        c.applyPreset(Preset.VANILLA);
        return c;
    }

    public static AnimaConfig lush() {
        AnimaConfig c = new AnimaConfig();
        c.applyPreset(Preset.LUSH);
        return c;
    }

    public static AnimaConfig borderlands() {
        AnimaConfig c = new AnimaConfig();
        c.applyPreset(Preset.BORDERLANDS);
        return c;
    }

    public static AnimaConfig forPreset(Preset preset) {
        AnimaConfig c = new AnimaConfig();
        c.applyPreset(preset);
        return c;
    }

    public void applyPreset(Preset p) {
        this.preset = p;
        switch (p) {
            case VANILLA -> {
                enabled = false;
                atmosphericLightmap = false;
                skyEnhancement = false;
                atmosphericFog = false;
                waterEnhancement = false;
                directionalShading = false;
                foliageEnhancement = false;
                dynamicHandheldLight = false;
                warmthStrength = 0f;
                shadowDepth = 0f;
                fogDensity = 0f;
                waterClarity = 0f;
                saturationBoost = 1.0f;
                filmicStrength = 0f;
                torchFlicker = 0f;
            }
            case LUSH -> {
                enabled = true;
                atmosphericLightmap = true;
                skyEnhancement = true;
                atmosphericFog = true;
                waterEnhancement = true;
                directionalShading = true;
                foliageEnhancement = true;
                dynamicHandheldLight = true;
                warmthStrength = 1.05f;
                shadowDepth = 0.88f;
                fogDensity = 0.62f;
                waterClarity = 0.92f;
                saturationBoost = 1.14f;
                filmicStrength = 0.58f;
                torchFlicker = 0.35f;
            }
            case BORDERLANDS -> {
                enabled = true;
                atmosphericLightmap = true;
                skyEnhancement = true;
                atmosphericFog = true;
                waterEnhancement = true;
                directionalShading = true;
                foliageEnhancement = true;
                dynamicHandheldLight = true;
                warmthStrength = 1.12f;
                shadowDepth = 0.96f;
                fogDensity = 0.38f;
                waterClarity = 0.85f;
                saturationBoost = 1.16f;
                filmicStrength = 0.45f;
                torchFlicker = 0.25f;
            }
            case COLOR_PENCIL -> {
                enabled = true;
                atmosphericLightmap = true;
                skyEnhancement = true;
                atmosphericFog = true;
                waterEnhancement = true;
                directionalShading = true;
                foliageEnhancement = true;
                dynamicHandheldLight = true;
                warmthStrength = 1.02f;
                shadowDepth = 0.85f;
                fogDensity = 0.40f;
                waterClarity = 0.85f;
                saturationBoost = 1.18f;
                filmicStrength = 0.35f;
                torchFlicker = 0.20f;
            }
            case BLUEPRINT -> {
                enabled = true;
                atmosphericLightmap = true;
                skyEnhancement = false;
                atmosphericFog = false;
                waterEnhancement = false;
                directionalShading = false;
                foliageEnhancement = false;
                dynamicHandheldLight = true;
                warmthStrength = 0.50f;
                shadowDepth = 0.70f;
                fogDensity = 0.20f;
                waterClarity = 0.50f;
                saturationBoost = 1.0f;
                filmicStrength = 0.20f;
                torchFlicker = 0.15f;
            }
        }
    }

    public boolean isActive() {
        return enabled && preset != Preset.VANILLA;
    }

    public boolean isBorderlands() {
        return enabled && preset == Preset.BORDERLANDS;
    }

    public boolean isColorPencil() {
        return enabled && preset == Preset.COLOR_PENCIL;
    }

    public boolean isBlueprint() {
        return enabled && preset == Preset.BLUEPRINT;
    }

    public float gpuModeFraction() {
        if (!isActive()) return 0.0f;
        return switch (preset) {
            case LUSH, COLOR_PENCIL -> 0.35f;
            case BORDERLANDS -> 0.75f;
            case BLUEPRINT -> 0.0f;
            default -> 0.0f;
        };
    }

    public void load(Path path) {
        if (!Files.exists(path)) {
            save(path);
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            String pName = props.getProperty("preset", Preset.LUSH.name()).toUpperCase();
            try {
                applyPreset(Preset.valueOf(pName));
            } catch (IllegalArgumentException ignored) {
                applyPreset(Preset.LUSH);
            }
            enabled = Boolean.parseBoolean(props.getProperty("enabled", String.valueOf(enabled)));
            atmosphericLightmap = Boolean.parseBoolean(props.getProperty("atmosphericLightmap", String.valueOf(atmosphericLightmap)));
            skyEnhancement = Boolean.parseBoolean(props.getProperty("skyEnhancement", String.valueOf(skyEnhancement)));
            atmosphericFog = Boolean.parseBoolean(props.getProperty("atmosphericFog", String.valueOf(atmosphericFog)));
            waterEnhancement = Boolean.parseBoolean(props.getProperty("waterEnhancement", String.valueOf(waterEnhancement)));
            directionalShading = Boolean.parseBoolean(props.getProperty("directionalShading", String.valueOf(directionalShading)));
            foliageEnhancement = Boolean.parseBoolean(props.getProperty("foliageEnhancement", String.valueOf(foliageEnhancement)));
            dynamicHandheldLight = Boolean.parseBoolean(props.getProperty("dynamicHandheldLight", String.valueOf(dynamicHandheldLight)));
        } catch (IOException ignored) {
        }
    }

    public void save(Path path) {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("preset", preset.name());
        props.setProperty("atmosphericLightmap", String.valueOf(atmosphericLightmap));
        props.setProperty("skyEnhancement", String.valueOf(skyEnhancement));
        props.setProperty("atmosphericFog", String.valueOf(atmosphericFog));
        props.setProperty("waterEnhancement", String.valueOf(waterEnhancement));
        props.setProperty("directionalShading", String.valueOf(directionalShading));
        props.setProperty("foliageEnhancement", String.valueOf(foliageEnhancement));
        props.setProperty("dynamicHandheldLight", String.valueOf(dynamicHandheldLight));
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Anima Mod Configuration");
            }
        } catch (IOException ignored) {
        }
    }
}
