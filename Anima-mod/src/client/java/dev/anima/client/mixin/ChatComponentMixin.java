package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.ui.BorderlandsChatLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Moves Minecraft chat to the bottom-right and wraps all visible lines in a clean,
 * stylized Borderlands frame aligned directly above the attached typing bar.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;

    @Unique private boolean anima$chatShifted = false;

    @Inject(method = "getWidth()I", at = @At("RETURN"), cancellable = true, require = 0)
    private void anima$clampBorderlandsChatWidth(CallbackInfoReturnable<Integer> cir) {
        if (!AnimaMod.config().isBorderlands() || this.minecraft == null || this.minecraft.getWindow() == null) {
            return;
        }
        int guiW = this.minecraft.getWindow().getGuiScaledWidth();
        int guiH = this.minecraft.getWindow().getGuiScaledHeight();
        BorderlandsChatLayout.Box box = BorderlandsChatLayout.compute(this.minecraft, guiW, guiH);
        double scale = Math.max(0.1, this.minecraft.options.chatScale().get());
        int maxLogicalW = Math.max(80, (int) Math.floor(box.scaledWidth() / scale));
        if (cir.getReturnValue() > maxLogicalW) {
            cir.setReturnValue(maxLogicalW);
        }
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
            at = @At("HEAD"),
            require = 0
    )
    private void anima$beginBorderlandsChat(
            GuiGraphicsExtractor g,
            Font font,
            int ticks,
            int mouseX,
            int mouseY,
            ChatComponent.DisplayMode displayMode,
            boolean changeCursorOnInsert,
            CallbackInfo ci) {
        this.anima$chatShifted = false;
        if (!AnimaMod.config().isBorderlands()) {
            return;
        }

        boolean isFocused = displayMode != null && displayMode.foreground;
        BorderlandsChatLayout.Box box = BorderlandsChatLayout.compute(this.minecraft, g.guiWidth(), g.guiHeight());

        double scale = this.minecraft.options.chatScale().get();
        int lineHeight = (int) Math.round(9.0 * (this.minecraft.options.chatLineSpacing().get() + 1.0));
        int maxHeight = ChatComponent.getHeight(isFocused
                ? this.minecraft.options.chatHeightFocused().get()
                : this.minecraft.options.chatHeightUnfocused().get());
        int maxLines = Math.max(1, maxHeight / Math.max(1, lineHeight));

        int visible = 0;
        int total = this.trimmedMessages.size();
        for (int i = 0; i + this.chatScrollbarPos < total && i < maxLines; i++) {
            GuiMessage.Line line = this.trimmedMessages.get(i + this.chatScrollbarPos);
            int age = ticks - line.addedTime();
            if (isFocused || age < 200) {
                visible++;
            }
        }

        if (visible > 0 || isFocused) {
            int drawLines = Math.max(visible, isFocused ? 1 : 0);
            int contentH = (int) Math.ceil(drawLines * lineHeight * scale) + 8;
            int boxTop = Math.max(14, box.chatBottom() - contentH);
            BorderlandsChatLayout.drawChatFrame(g, box, boxTop, isFocused);
        }

        g.pose().pushMatrix();
        g.pose().translate((float) box.shiftX(), 0.0f);
        this.anima$chatShifted = true;
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
            at = @At("RETURN"),
            require = 0
    )
    private void anima$endBorderlandsChat(
            GuiGraphicsExtractor g,
            Font font,
            int ticks,
            int mouseX,
            int mouseY,
            ChatComponent.DisplayMode displayMode,
            boolean changeCursorOnInsert,
            CallbackInfo ci) {
        if (this.anima$chatShifted) {
            g.pose().popMatrix();
            this.anima$chatShifted = false;
        }
    }
}
