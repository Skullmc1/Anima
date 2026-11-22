package dev.anima.client.mixin;

import dev.anima.AnimaMod;
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

import java.util.List;

/**
 * Moves Minecraft chat to the bottom-right and wraps it in a stylized Borderlands ECHO-Comms
 * frame whenever Anima's Borderlands mode is active.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;

    @Unique private boolean anima$chatShifted = false;

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
            boolean isFocused,
            CallbackInfo ci) {
        this.anima$chatShifted = false;
        if (!AnimaMod.config().isBorderlands()) {
            return;
        }

        double scale = this.minecraft.options.chatScale().get();
        int rawWidth = ChatComponent.getWidth(this.minecraft.options.chatWidth().get());
        int scaledWidth = Math.max(80, (int) Math.ceil(rawWidth * scale));
        int shiftX = Math.max(0, g.guiWidth() - scaledWidth - 14);

        // Count currently visible lines to size the stylized Borderlands ECHO-Comms frame cleanly
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
            int boxLines = Math.max(visible, isFocused ? 2 : 0);
            int boxH = (int) Math.ceil(boxLines * lineHeight * scale) + 10;
            int boxBottom = g.guiHeight() - 36;
            int boxTop = Math.max(16, boxBottom - boxH);
            int boxLeft = shiftX - 4;
            int boxRight = Math.min(g.guiWidth() - 6, shiftX + scaledWidth + 6);

            // Stylized Borderlands ECHO-Comms chat panel (comic ink + slate + steel-cyan border + gold accent)
            g.fill(boxLeft - 1, boxTop - 1, boxRight + 1, boxBottom + 1, 0xEC06080D);
            g.fillGradient(boxLeft, boxTop, boxRight, boxBottom, 0xCC111824, 0xCC0A0F18);
            g.outline(boxLeft, boxTop, boxRight - boxLeft, boxBottom - boxTop, 0xFF334E68);
            // Right golden-amber vertical accent bar + top cyan sheen
            g.fill(boxRight - 2, boxTop + 2, boxRight, boxBottom - 2, 0xFFFBBF24);
            g.fill(boxLeft + 2, boxTop, boxRight - 4, boxTop + 1, 0x5538BDF8);
            // Compact "ECHO COMMS" header tag on top-right
            String tag = "ECHO // COMMS";
            int tw = font.width(tag);
            int tagLeft = boxRight - tw - 10;
            int tagTop = boxTop - 10;
            if (tagTop >= 4) {
                g.fill(tagLeft - 1, tagTop - 1, boxRight + 1, boxTop + 1, 0xEC06080D);
                g.fill(tagLeft, tagTop, boxRight, boxTop, 0xE2101824);
                g.fill(boxRight - 2, tagTop, boxRight, boxTop, 0xFFFBBF24);
                g.text(font, tag, tagLeft + 4, tagTop + 1, 0xFF94C5EB, false);
            }
        }

        g.pose().pushMatrix();
        g.pose().translate((float) shiftX, 0.0f);
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
            boolean isFocused,
            CallbackInfo ci) {
        if (this.anima$chatShifted) {
            g.pose().popMatrix();
            this.anima$chatShifted = false;
        }
    }
}
