package dev.anima.client.ui;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Sleek custom dark glassmorphic dropdown UI for selecting Anima visual modes
 * directly from the Escape (Pause) Menu — completely bypassing Minecraft's default gray buttons.
 */
public final class AnimaDropdownWidget extends AbstractWidget {

    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 16;
    private static final int PANEL_GAP = 4;

    private boolean expanded = false;

    public AnimaDropdownWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal("Anima Mode"));
    }

    private int menuHeight() {
        return HEADER_HEIGHT + AnimaConfig.Preset.values().length * ROW_HEIGHT + 4;
    }

    private int menuTop() {
        return getY() - PANEL_GAP - menuHeight();
    }

    private static int accentColor(AnimaConfig.Preset preset) {
        return switch (preset) {
            case VANILLA -> 0xFF64748B;      // Slate gray
            case LUSH -> 0xFF38BDF8;         // Crisp cyan-sky
            case BORDERLANDS -> 0xFFF59E0B;  // Vibrant Borderlands amber-gold
            case COLOR_PENCIL -> 0xFFF472B6; // Sketchbook coral-pink
            case BLUEPRINT -> 0xFF60A5FA;    // Technical blueprint blue
        };
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        AnimaConfig cfg = AnimaMod.config();
        AnimaConfig.Preset active = cfg.isActive() ? cfg.preset : AnimaConfig.Preset.VANILLA;

        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        boolean pillHover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int accent = accentColor(active);

        // 1. Main sleek dark pill container
        int bgTop = (pillHover || expanded) ? 0xF0151C28 : 0xE20D1119;
        int bgBot = (pillHover || expanded) ? 0xF00F141E : 0xE2090C12;
        int border = expanded ? accent : (pillHover ? 0xFF475569 : 0xFF263042);

        // Subtle drop shadow
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x55000000);
        g.fillGradient(x, y, x + w, y + h, bgTop, bgBot);
        g.outline(x, y, w, h, border);
        // Subtle 1px inner top sheen
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x18FFFFFF);

        // Left accent bar + status dot
        g.fill(x + 1, y + 4, x + 3, y + h - 4, accent);
        g.fill(x + 9, y + (h / 2) - 2, x + 13, y + (h / 2) + 2, accent);

        // Brand tag "ANIMA" + active mode label + chevron
        int textY = y + (h - 8) / 2;
        g.text(font, "ANIMA", x + 18, textY, 0xFF94A3B8, false);
        int nameX = x + 56;
        g.text(font, active.displayName(), nameX, textY, 0xFFF8FAFC, false);

        String chevron = expanded ? "v" : "^";
        g.text(font, chevron, x + w - 14, textY, expanded ? accent : 0xFF94A3B8, false);

        // 2. Expanded upward sleek dark dropdown menu
        if (expanded) {
            int my = menuTop();
            int mh = menuHeight();

            // Elevated shadow + dark obsidian card
            g.fill(x + 3, my + 3, x + w + 3, my + mh + 3, 0x77000000);
            g.fillGradient(x, my, x + w, my + mh, 0xF5111722, 0xF50A0E16);
            g.outline(x, my, w, mh, 0xFF334155);
            g.fill(x + 1, my + 1, x + w - 1, my + 2, 0x1AFFFFFF);

            // Header label
            g.text(font, "VISUAL MODE", x + 9, my + 5, 0xFF64748B, false);
            g.fill(x + 6, my + HEADER_HEIGHT - 1, x + w - 6, my + HEADER_HEIGHT, 0xFF1E293B);

            AnimaConfig.Preset[] modes = AnimaConfig.Preset.values();
            for (int i = 0; i < modes.length; i++) {
                AnimaConfig.Preset mode = modes[i];
                int ry = my + HEADER_HEIGHT + 2 + i * ROW_HEIGHT;
                boolean rowHover = mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= ry && mouseY < ry + ROW_HEIGHT;
                boolean selected = (mode == active);
                int rowAccent = accentColor(mode);

                if (rowHover) {
                    g.fill(x + 3, ry, x + w - 3, ry + ROW_HEIGHT - 1, 0xFF1E293B);
                } else if (selected) {
                    g.fill(x + 3, ry, x + w - 3, ry + ROW_HEIGHT - 1, 0xAA162032);
                }

                // Active left indicator bar
                if (selected) {
                    g.fill(x + 3, ry + 3, x + 5, ry + ROW_HEIGHT - 4, rowAccent);
                }

                // Mode color dot
                g.fill(x + 10, ry + (ROW_HEIGHT / 2) - 2, x + 14, ry + (ROW_HEIGHT / 2) + 2, rowAccent);

                // Mode name + subtle subtitle on the right
                int rTextY = ry + (ROW_HEIGHT - 8) / 2;
                g.text(font, mode.displayName(), x + 20, rTextY, selected ? 0xFFFFFFFF : 0xFFCBD5E1, false);
                String sub = mode.subtitle();
                int subW = font.width(sub);
                g.text(font, sub, x + w - subW - 9, rTextY, selected ? rowAccent : 0xFF64748B, false);
            }
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            return true;
        }
        if (expanded) {
            int my = menuTop();
            int mh = menuHeight();
            return mouseX >= x && mouseX < x + w && mouseY >= my && mouseY < my + mh;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.active || !this.visible || event.button() != 0) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        // Clicked on the main pill -> toggle dropdown
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            expanded = !expanded;
            playDownSound(Minecraft.getInstance().getSoundManager());
            return true;
        }

        // Clicked inside the expanded dropdown list
        if (expanded) {
            int top = menuTop();
            int mh = menuHeight();
            if (mx >= x && mx < x + w && my >= top && my < top + mh) {
                AnimaConfig.Preset[] modes = AnimaConfig.Preset.values();
                for (int i = 0; i < modes.length; i++) {
                    int ry = top + HEADER_HEIGHT + 2 + i * ROW_HEIGHT;
                    if (my >= ry && my < ry + ROW_HEIGHT) {
                        AnimaConfig cfg = AnimaMod.config();
                        cfg.applyPreset(modes[i]);
                        cfg.save(AnimaMod.configPath());
                        AnimaClientMod.refreshSurroundingChunks(Minecraft.getInstance());
                        expanded = false;
                        playDownSound(Minecraft.getInstance().getSoundManager());
                        return true;
                    }
                }
                return true;
            } else {
                // Clicked outside -> close dropdown cleanly
                expanded = false;
            }
        }

        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
