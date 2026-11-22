package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.ui.BorderlandsHudRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.contextualbar.ContextualBar;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla health, armor, hunger, air, and XP bars with the custom Borderlands
 * bottom-left HUD + stylized matching hotbar whenever Anima's Borderlands mode is active.
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Shadow @Final private Minecraft minecraft;

    @Shadow
    protected abstract Player getCameraPlayer();

    @Shadow
    protected abstract void extractSlot(GuiGraphicsExtractor g, int x, int y, DeltaTracker deltaTracker, Player player, ItemStack stack, int seed);

    @Inject(method = "extractPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void anima$hideVanillaStatusBars(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (AnimaMod.config().isBorderlands()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void anima$renderBorderlandsHotbar(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!AnimaMod.config().isBorderlands()) {
            return;
        }
        Player player = this.getCameraPlayer();
        if (player == null) {
            return;
        }
        BorderlandsHudRenderer.renderHotbar(g, deltaTracker, player, this::extractSlot);
        ci.cancel();
    }

    @Redirect(
            method = "extractHotbarAndDecorations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
            )
    )
    private void anima$hideVanillaXpBarBg(ContextualBar bar, GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        if (AnimaMod.config().isBorderlands() && anima$isExperienceBar(bar)) {
            return;
        }
        bar.extractBackground(g, deltaTracker);
    }

    @Redirect(
            method = "extractHotbarAndDecorations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
            )
    )
    private void anima$hideVanillaXpBarFg(ContextualBar bar, GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        if (AnimaMod.config().isBorderlands() && anima$isExperienceBar(bar)) {
            return;
        }
        bar.extractRenderState(g, deltaTracker);
    }

    @Redirect(
            method = "extractHotbarAndDecorations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z"
            )
    )
    private boolean anima$hideVanillaXpLevelNumber(MultiPlayerGameMode gameMode) {
        if (AnimaMod.config().isBorderlands()) {
            return false;
        }
        return gameMode.hasExperience();
    }

    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"))
    private void anima$renderBorderlandsStatusHud(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (AnimaMod.config().isBorderlands()) {
            BorderlandsHudRenderer.renderStatusHud(g, this.minecraft);
        }
    }

    @Unique
    private static boolean anima$isExperienceBar(ContextualBar bar) {
        return bar != null && bar.getClass().getSimpleName().contains("Experience");
    }
}
