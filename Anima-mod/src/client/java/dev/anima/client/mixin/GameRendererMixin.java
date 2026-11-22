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
 * Drives Anima's lightweight fullscreen PostChain passes (Lush, Borderlands,
 * Color Pencil, Blueprint) natively through Minecraft 26.2's GameRenderer —
 * 100% compatible with BOTH Sodium and Vanilla!
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Unique private static final Identifier ANIMA_LUSH_POST = Identifier.fromNamespaceAndPath("anima", "lush");
    @Unique private static final Identifier ANIMA_BORDERLANDS_POST = Identifier.fromNamespaceAndPath("anima", "borderlands");
    @Unique private static final Identifier ANIMA_COLOR_PENCIL_POST = Identifier.fromNamespaceAndPath("anima", "color_pencil");
    @Unique private static final Identifier ANIMA_BLUEPRINT_POST = Identifier.fromNamespaceAndPath("anima", "blueprint");

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
            desired = switch (cfg.preset) {
                case LUSH -> ANIMA_LUSH_POST;
                case BORDERLANDS -> ANIMA_BORDERLANDS_POST;
                case COLOR_PENCIL -> ANIMA_COLOR_PENCIL_POST;
                case BLUEPRINT -> ANIMA_BLUEPRINT_POST;
                default -> null;
            };
        }

        if (desired == null) {
            if (isAnimaPostEffect(this.postEffectId)) {
                this.clearPostEffect();
            }
        } else if (!desired.equals(this.postEffectId)) {
            this.setPostEffect(desired);
        }
    }

    @Unique
    private static boolean isAnimaPostEffect(Identifier id) {
        return ANIMA_LUSH_POST.equals(id)
                || ANIMA_BORDERLANDS_POST.equals(id)
                || ANIMA_COLOR_PENCIL_POST.equals(id)
                || ANIMA_BLUEPRINT_POST.equals(id);
    }
}
