package dev.anima.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration and quality presets for Anima.
 * Every preset is shader-free and designed for ultra-high FPS even on integrated GPUs.
 */
public final class AnimaConfig {

    public enum Preset {
        VANILLA("Vanilla (Off)"),
        POTATO("Potato (Ultra-Fast)"),
        BALANCED("Balanced"),
        LUSH("Lush (Cinematic)");

        private final String displayName;

        Preset(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public Preset next() {
            Preset[] values = values();
            return values[(ordinal() + 1) % values.length];
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

    public float warmthStrength = 1.0f;
    public float shadowDepth = 0.85f;
    public float fogDensity = 0.72f;
    public float waterClarity = 0.85f;
    public float saturationBoost = 1.14f;
    public float filmicStrength = 0.55f;
    public float torchFlicker = 0.35f;

    public static AnimaConfig vanilla() {
        AnimaConfig c = new AnimaConfig();
        c.applyPreset(Preset.VANILLA);
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
            case POTATO -> {
                enabled = true;
                atmosphericLightmap = true;
                skyEnhancement = true;
                atmosphericFog = true;
                waterEnhancement = true;
                directionalShading = false;
                foliageEnhancement = true;
                dynamicHandheldLight = true;
                warmthStrength = 0.82f;
                shadowDepth = 0.65f;
                fogDensity = 0.55f;
                waterClarity = 0.70f;
                saturationBoost = 1.08f;
                filmicStrength = 0.38f;
                torchFlicker = 0.0f;
            }
            case BALANCED -> {
                enabled = true;
                atmosphericLightmap = true;
                skyEnhancement = true;
                atmosphericFog = true;
                waterEnhancement = true;
                directionalShading = true;
                foliageEnhancement = true;
                dynamicHandheldLight = true;
                warmthStrength = 0.92f;
                shadowDepth = 0.78f;
                fogDensity = 0.68f;
                waterClarity = 0.80f;
                saturationBoost = 1.11f;
                filmicStrength = 0.48f;
                torchFlicker = 0.25f;
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
                fogDensity = 0.76f;
                waterClarity = 0.88f;
                saturationBoost = 1.15f;
                filmicStrength = 0.56f;
                torchFlicker = 0.35f;
            }
        }
    }

    public boolean isActive() {
        return enabled && preset != Preset.VANILLA;
    }

    public void load(Path path) {
        if (!Files.exists(path)) {
            save(path);
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            String pName = props.getProperty("preset", Preset.LUSH.name());
            try {
                applyPreset(Preset.valueOf(pName.toUpperCase()));
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
                props.store(out, "Anima Mod Configuration - Shader-free atmospheric visuals");
            }
        } catch (IOException ignored) {
        }
    }
}
