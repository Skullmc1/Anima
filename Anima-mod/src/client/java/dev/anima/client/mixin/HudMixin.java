package dev.anima.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.anima.AnimaMod;
import dev.anima.client.ui.BorderlandsHudRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.contextualbar.ContextualBar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla health, armor, hunger, air, and XP bars with the custom Borderlands
 * bottom-left HUD + stylized matching hotbar whenever Anima's Borderlands mode is active.
 *
 * Uses MixinExtras (@WrapWithCondition / @ModifyExpressionValue) instead of @Redirect so it
 * never conflicts with other HUD mods (AppleSkin, Raised, Fabric HUD API, Sodium Extra, etc.).
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "extractPlayerHealth", at = @At("HEAD"), cancellable = true, require = 0)
    private void anima$hideVanillaStatusBars(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (AnimaMod.config().isBorderlands()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true, require = 0)
    private void anima$renderBorderlandsHotbar(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!AnimaMod.config().isBorderlands()) {
            return;
        }
        Player player = (this.minecraft.getCameraEntity() instanceof Player p) ? p : this.minecraft.player;
        if (player == null) {
            return;
        }
        BorderlandsHudRenderer.renderHotbar(g, this.minecraft, player);
        ci.cancel();
    }

    @WrapWithCondition(
            method = "extractHotbarAndDecorations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
            ),
            require = 0
    )
    private boolean anima$shouldDrawXpBarBg(ContextualBar bar, GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        return !(AnimaMod.config().isBorderlands() && anima$isExperienceBar(bar));
    }

    @WrapWithCondition(
            method = "extractHotbarAndDecorations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"
            ),
            require = 0
    )
    private boolean anima$shouldDrawXpBarFg(ContextualBar bar, GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        return !(AnimaMod.config().isBorderlands() && anima$isExperienceBar(bar));
    }

    @ModifyExpressionValue(
            method = "extractHotbarAndDecorations",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z"
            ),
            require = 0
    )
    private boolean anima$hideVanillaXpLevelNumber(boolean original) {
        return !AnimaMod.config().isBorderlands() && original;
    }

    @Inject(method = "extractHotbarAndDecorations", at = @At("TAIL"), require = 0)
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
