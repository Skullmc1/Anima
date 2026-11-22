package dev.anima.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives Anima's lightweight fullscreen PostChain passes (Lush, Borderlands,
 * Color Pencil, Blueprint) right after the 3D world renders and BEFORE Minecraft
 * clears the Reverse-Z depth buffer for the first-person hand.
 * 100% compatible with BOTH Sodium and Vanilla!
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Unique private static final Identifier ANIMA_LUSH_POST = Identifier.fromNamespaceAndPath("anima", "lush");
    @Unique private static final Identifier ANIMA_BORDERLANDS_POST = Identifier.fromNamespaceAndPath("anima", "borderlands");
    @Unique private static final Identifier ANIMA_COLOR_PENCIL_POST = Identifier.fromNamespaceAndPath("anima", "color_pencil");
    @Unique private static final Identifier ANIMA_BLUEPRINT_POST = Identifier.fromNamespaceAndPath("anima", "blueprint");

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void anima$runWorldPostChain(DeltaTracker deltaTracker, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive()) {
            return;
        }
        Identifier desired = switch (cfg.preset) {
            case LUSH -> ANIMA_LUSH_POST;
            case BORDERLANDS -> ANIMA_BORDERLANDS_POST;
            case COLOR_PENCIL -> ANIMA_COLOR_PENCIL_POST;
            case BLUEPRINT -> ANIMA_BLUEPRINT_POST;
            default -> null;
        };
        if (desired == null) {
            return;
        }
        PostChain chain = this.minecraft.getShaderManager().getPostChain(desired, LevelTargetBundle.MAIN_TARGETS);
        if (chain != null) {
            chain.process(this.mainRenderTarget, this.resourcePool);
        }
    }
}
