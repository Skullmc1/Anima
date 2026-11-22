package dev.anima.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.anima.AnimaMod;
import dev.anima.client.ui.BorderlandsChatLayout;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the Minecraft chat typing bar directly underneath the bottom-right Borderlands
 * chat box and stylizes it to match the Borderlands HUD.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    @Shadow protected EditBox input;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void anima$positionBorderlandsChatInput(CallbackInfo ci) {
        if (!AnimaMod.config().isBorderlands() || this.input == null || this.minecraft == null) {
            return;
        }
        BorderlandsChatLayout.Box box = BorderlandsChatLayout.compute(this.minecraft, this.width, this.height);
        this.input.setPosition(box.left() + 6, box.inputTop() + 3);
        this.input.setWidth(Math.max(60, box.width() - 12));
    }

    @WrapWithCondition(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
            ),
            require = 0
    )
    private boolean anima$replaceVanillaChatInputBg(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        if (!AnimaMod.config().isBorderlands() || this.minecraft == null) {
            return true;
        }
        BorderlandsChatLayout.Box box = BorderlandsChatLayout.compute(this.minecraft, this.width, this.height);
        BorderlandsChatLayout.drawAttachedInputBar(g, box);
        return false;
    }
}
