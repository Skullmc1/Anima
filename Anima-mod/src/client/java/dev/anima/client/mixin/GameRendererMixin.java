package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives Anima's lightweight fullscreen PostChain passes (Lush Bloom + Filmic Grading,
 * Borderlands Miasma-style Laplacian Depth Outlines + Cel-Shading) natively through
 * Minecraft 26.2's GameRenderer — 100% compatible with BOTH Sodium and Vanilla!
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Unique
    private static final Identifier ANIMA_LUSH_POST = Identifier.fromNamespaceAndPath("anima", "lush");
    @Unique
    private static final Identifier ANIMA_BORDERLANDS_POST = Identifier.fromNamespaceAndPath("anima", "borderlands");

    @Shadow
    private Identifier postEffectId;

    @Shadow
    private void setPostEffect(Identifier id) {}

    @Shadow
    public abstract void clearPostEffect();

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void anima$syncPostEffect(DeltaTracker deltaTracker, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        Identifier desired = null;
        if (cfg.isActive()) {
            desired = cfg.isBorderlands() ? ANIMA_BORDERLANDS_POST : ANIMA_LUSH_POST;
        }

        if (desired == null) {
            if (ANIMA_LUSH_POST.equals(this.postEffectId) || ANIMA_BORDERLANDS_POST.equals(this.postEffectId)) {
                this.clearPostEffect();
            }
        } else if (!desired.equals(this.postEffectId)) {
            this.setPostEffect(desired);
        }
    }
}
