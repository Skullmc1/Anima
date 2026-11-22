package dev.anima.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.anima.AnimaMod;
import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class AnimaClientMod implements ClientModInitializer {

    private static volatile AnimaAtmosphere currentAtmosphere = new AnimaAtmosphere(6000f, 0f);
    private static volatile float elapsedSeconds = 0f;

    private static KeyMapping toggleKey;

    public static AnimaAtmosphere atmosphere() {
        return currentAtmosphere;
    }

    public static float elapsedSeconds() {
        return elapsedSeconds;
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.anima.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                KeyMapping.Category.MISC
        ));

        FabricLoader.getInstance().getModContainer(AnimaMod.MOD_ID).ifPresent(container -> {
            ResourceLoader.registerBuiltinPack(
                    Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "anima_shaders"),
                    container,
                    Component.literal("Anima Core Shaders"),
                    PackActivationType.ALWAYS_ENABLED
            );
            if (FabricLoader.getInstance().isModLoaded("sodium")) {
                ResourceLoader.registerBuiltinPack(
                        Identifier.fromNamespaceAndPath(AnimaMod.MOD_ID, "anima_sodium_shaders"),
                        container,
                        Component.literal("Anima Sodium Shaders"),
                        PackActivationType.ALWAYS_ENABLED
                );
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(AnimaClientMod::onClientTick);
        AnimaMod.LOGGER.info("Anima Lush client pipeline ready (Vanilla & Sodium 26.2, OpenGL & Vulkan compatible)");
    }

    private static void onClientTick(Minecraft client) {
        elapsedSeconds += 0.05f;
        ClientLevel level = client.level;
        if (level != null) {
            float dayTime = level.getOverworldClockTime() % 24000L;
            float rain = level.getRainLevel(1.0f);
            currentAtmosphere = new AnimaAtmosphere(dayTime, rain);
        }
        DynamicLightTracker.tick(client);

        while (toggleKey.consumeClick()) {
            AnimaConfig cfg = AnimaMod.config();
            if (cfg.isActive()) {
                cfg.applyPreset(AnimaConfig.Preset.VANILLA);
            } else {
                cfg.applyPreset(AnimaConfig.Preset.LUSH);
            }
            cfg.save(AnimaMod.configPath());
            refreshSurroundingChunks(client);
            notifyPlayer(client, cfg.isActive() ? "Anima: ON (Lush)" : "Anima: OFF (Vanilla)");
        }
    }

    public static void refreshSurroundingChunks(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        int cx = client.player.getBlockX() >> 4;
        int cy = client.player.getBlockY() >> 4;
        int cz = client.player.getBlockZ() >> 4;
        int radius = Math.min(8, Math.max(4, client.options.renderDistance().get()));
        client.level.setSectionRangeDirty(cx - radius, cy - 4, cz - radius, cx + radius, cy + 4, cz + radius);
    }

    private static void notifyPlayer(Minecraft client, String message) {
        if (client.gui != null) {
            client.gui.chatListener().handleOverlay(Component.literal(message));
        }
        AnimaMod.LOGGER.info(message);
    }
}
