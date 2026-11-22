package dev.anima;

import dev.anima.core.AnimaConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class AnimaMod implements ModInitializer {
    public static final String MOD_ID = "anima";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final AnimaConfig CONFIG = new AnimaConfig();

    public static AnimaConfig config() {
        return CONFIG;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("anima.properties");
    }

    @Override
    public void onInitialize() {
        CONFIG.load(configPath());
        LOGGER.info("Anima initialized (preset: {}, enabled: {})", CONFIG.preset.displayName(), CONFIG.enabled);
    }
}
