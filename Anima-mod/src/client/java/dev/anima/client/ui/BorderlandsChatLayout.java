package dev.anima.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;

/**
 * Shared pixel-exact geometry & styling for Borderlands mode's bottom-right chat panel
 * and attached typing input bar so ChatComponentMixin and ChatScreenMixin align seamlessly.
 */
public final class BorderlandsChatLayout {

    public static final int COL_INK        = 0xF206080D;
    public static final int COL_BG_TOP     = 0xE0101724;
    public static final int COL_BG_BOT     = 0xE00A0F18;
    public static final int COL_INPUT_BG   = 0xEE090D15;
    public static final int COL_EDGE       = 0xFF334E68;
    public static final int COL_GOLD       = 0xFFFBBF24;
    public static final int COL_CYAN_SHEEN = 0x5538BDF8;

    public record Box(int shiftX, int scaledWidth, int left, int right, int chatBottom, int inputTop, int inputBottom) {
        public int width() {
            return right - left;
        }
    }

    private BorderlandsChatLayout() {}

    public static Box compute(Minecraft mc, int guiWidth, int guiHeight) {
        double scale = mc.options.chatScale().get();
        int rawWidth = ChatComponent.getWidth(mc.options.chatWidth().get());
        int scaledWidth = Math.max(110, (int) Math.ceil(rawWidth * scale));
        int right = guiWidth - 10;
        int hotbarRight = (guiWidth / 2) + 95;
        int availRightW = right - hotbarRight - 6;
        // Fit cleanly to the right of the hotbar; on very narrow windows use up to 44% width lifted above hotbar
        int panelWidth = (availRightW >= 120)
                ? Math.min(scaledWidth + 14, availRightW)
                : Math.min(scaledWidth + 14, Math.max(120, (int) (guiWidth * 0.44f)));
        int left = right - panelWidth;
        int shiftX = left + 2;

        // Vanilla ChatComponent renders lines ending at (guiHeight - 40); attach typing bar right below!
        int chatBottom = guiHeight - 38;
        int inputTop = chatBottom;
        int inputBottom = inputTop + 15;
        return new Box(shiftX, panelWidth - 14, left, right, chatBottom, inputTop, inputBottom);
    }

    /**
     * Draws the stylized Borderlands chat history frame (no ECHO//COMMS header).
     */
    public static void drawChatFrame(GuiGraphicsExtractor g, Box box, int top, boolean attachedInput) {
        int bottom = attachedInput ? box.inputTop() + 1 : box.chatBottom();
        // Outer comic ink border
        g.fill(box.left() - 1, top - 1, box.right() + 1, bottom + 1, COL_INK);
        // Dark metallic slate body
        g.fillGradient(box.left(), top, box.right(), bottom, COL_BG_TOP, COL_BG_BOT);
        // Steel-cyan outline + top highlight sheen
        g.outline(box.left(), top, box.width(), bottom - top, COL_EDGE);
        g.fill(box.left() + 2, top, box.right() - 4, top + 1, COL_CYAN_SHEEN);
        // Right golden-amber vertical accent bar
        g.fill(box.right() - 2, top + 2, box.right(), bottom - 2, COL_GOLD);
    }

    /**
     * Draws the stylized Borderlands typing bar seamlessly attached to the bottom of the chat frame.
     */
    public static void drawAttachedInputBar(GuiGraphicsExtractor g, Box box) {
        int top = box.inputTop();
        int bottom = box.inputBottom();
        // Outer comic ink border
        g.fill(box.left() - 1, top, box.right() + 1, bottom + 1, COL_INK);
        // Deep obsidian input track
        g.fill(box.left(), top, box.right(), bottom, COL_INPUT_BG);
        // Crisp steel-cyan frame + subtle divider line separating chat history from typing bar
        g.outline(box.left(), top, box.width(), bottom - top, COL_EDGE);
        g.fill(box.left() + 1, top, box.right() - 1, top + 1, 0x7738BDF8);
        // Left golden-amber prompt accent + right golden accent matching the chat panel above
        g.fill(box.left(), top + 1, box.left() + 3, bottom - 1, COL_GOLD);
        g.fill(box.right() - 2, top + 1, box.right(), bottom - 1, COL_GOLD);
    }
}
