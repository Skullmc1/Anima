package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a clean Anima: ON (Lush) / OFF toggle button to the Escape (Pause) Menu.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void anima$addPauseMenuButton(CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        int y = this.height - 28;

        Button toggleBtn = Button.builder(animaToggleLabel(cfg), btn -> {
            if (cfg.isActive()) {
                cfg.applyPreset(AnimaConfig.Preset.VANILLA);
            } else {
                cfg.applyPreset(AnimaConfig.Preset.LUSH);
            }
            cfg.save(AnimaMod.configPath());
            btn.setMessage(animaToggleLabel(cfg));
            AnimaClientMod.refreshSurroundingChunks(Minecraft.getInstance());
        }).bounds(10, y, 136, 20).build();

        this.addRenderableWidget(toggleBtn);
    }

    private static Component animaToggleLabel(AnimaConfig cfg) {
        return Component.literal(cfg.isActive() ? "Anima: ON (Lush)" : "Anima: OFF");
    }
}
