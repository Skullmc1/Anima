package dev.anima.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.anima.AnimaMod;
import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class AnimaClientMod implements ClientModInitializer {

    private static volatile AnimaAtmosphere currentAtmosphere = new AnimaAtmosphere(6000f, 0f);
    private static volatile float elapsedSeconds = 0f;

    private static KeyMapping toggleKey;
    private static KeyMapping cyclePresetKey;

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
        cyclePresetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.anima.cycle_preset",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(AnimaClientMod::onClientTick);
        AnimaMod.LOGGER.info("Anima client pipeline ready (OpenGL & Vulkan compatible, shader-free)");
    }

    private static void onClientTick(Minecraft client) {
        elapsedSeconds += 0.05f;
        ClientLevel level = client.level;
        if (level != null) {
            float dayTime = level.getOverworldClockTime() % 24000L;
            float rain = level.getRainLevel(1.0f);
            currentAtmosphere = new AnimaAtmosphere(dayTime, rain);
        }

        while (toggleKey.consumeClick()) {
            AnimaConfig cfg = AnimaMod.config();
            cfg.enabled = !cfg.enabled;
            cfg.save(AnimaMod.configPath());
            notifyPlayer(client, "Anima: " + (cfg.isActive() ? "ON (" + cfg.preset.displayName() + ")" : "OFF (Vanilla)"));
        }

        while (cyclePresetKey.consumeClick()) {
            AnimaConfig cfg = AnimaMod.config();
            cfg.applyPreset(cfg.preset.next());
            cfg.save(AnimaMod.configPath());
            notifyPlayer(client, "Anima Preset: " + cfg.preset.displayName());
        }
    }

    private static void notifyPlayer(Minecraft client, String message) {
        if (client.gui != null) {
            client.gui.chatListener().handleOverlay(Component.literal(message));
        }
        AnimaMod.LOGGER.info(message);
    }
}
