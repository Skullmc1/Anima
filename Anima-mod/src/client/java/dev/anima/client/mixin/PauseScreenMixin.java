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
 * Adds live Anima Toggle (ON/OFF) and Preset selector buttons directly to the
 * Escape (Pause) Menu so players can compare Vanilla vs Anima instantly.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void anima$addPauseMenuButtons(CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        int y = this.height - 28;

        Button[] presetBtnRef = new Button[1];

        Button toggleBtn = Button.builder(animaToggleLabel(cfg), btn -> {
            cfg.enabled = !cfg.enabled;
            if (cfg.enabled && cfg.preset == AnimaConfig.Preset.VANILLA) {
                cfg.applyPreset(AnimaConfig.Preset.LUSH);
            }
            cfg.save(AnimaMod.configPath());
            btn.setMessage(animaToggleLabel(cfg));
            if (presetBtnRef[0] != null) {
                presetBtnRef[0].setMessage(animaPresetLabel(cfg));
            }
            AnimaClientMod.refreshSurroundingChunks(Minecraft.getInstance());
        }).bounds(10, y, 108, 20).build();

        Button presetBtn = Button.builder(animaPresetLabel(cfg), btn -> {
            cfg.applyPreset(cfg.preset.next());
            cfg.save(AnimaMod.configPath());
            btn.setMessage(animaPresetLabel(cfg));
            toggleBtn.setMessage(animaToggleLabel(cfg));
            AnimaClientMod.refreshSurroundingChunks(Minecraft.getInstance());
        }).bounds(124, y, 152, 20).build();

        presetBtnRef[0] = presetBtn;
        this.addRenderableWidget(toggleBtn);
        this.addRenderableWidget(presetBtn);
    }

    private static Component animaToggleLabel(AnimaConfig cfg) {
        return Component.literal("Anima: " + (cfg.isActive() ? "ON" : "OFF"));
    }

    private static Component animaPresetLabel(AnimaConfig cfg) {
        return Component.literal("Preset: " + cfg.preset.displayName());
    }
}
