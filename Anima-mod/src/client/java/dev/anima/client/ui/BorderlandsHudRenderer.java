package dev.anima.client.ui;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;

/**
 * Custom Borderlands-styled HUD rendered when Anima's Borderlands mode is active:
 *  - Bottom-left: Chamfered diamond EXP level badge (with golden XP progress border) +
 *    slanted dark comic backplate housing Armor (cyan shield bar), Health (crimson bar + absorption),
 *    Hunger (amber bar + thin golden Saturation subline), and Underwater Respiration bubbles.
 *  - Bottom-center: Stylized matching Borderlands hotbar + offhand slot (replacing vanilla bars).
 */
public final class BorderlandsHudRenderer {

    // Borderlands palette (high-contrast comic ink + metallic slate + signature amber/cyan/crimson)
    private static final int COL_INK          = 0xF206080D;
    private static final int COL_PANEL_BG     = 0xD80E141E;
    private static final int COL_PANEL_EDGE   = 0xFF334E68;
    private static final int COL_GOLD         = 0xFFFBBF24;
    private static final int COL_GOLD_DIM     = 0xFF5A4518;
    private static final int COL_ARMOR_TOP    = 0xFF38BDF8;
    private static final int COL_ARMOR_BOT    = 0xFF0284C7;
    private static final int COL_ARMOR_TRACK  = 0xCC0A1C2A;
    private static final int COL_HP_TOP       = 0xFFEF4444;
    private static final int COL_HP_BOT       = 0xFFB91C1C;
    private static final int COL_HP_TRACK     = 0xCC280D12;
    private static final int COL_ABSORB       = 0xFFFACC15;
    private static final int COL_FOOD_TOP     = 0xFFF59E0B;
    private static final int COL_FOOD_BOT     = 0xFFB45309;
    private static final int COL_FOOD_TRACK   = 0xCC261808;
    private static final int COL_SAT_FILL     = 0xFFFDE047;
    private static final int COL_SAT_TRACK    = 0xBB1E190C;
    private static final int COL_AIR_FULL     = 0xFF38BDF8;
    private static final int COL_AIR_HILITE   = 0xFFBAE6FD;
    private static final int COL_AIR_LOW      = 0xFFF97316;
    private static final int COL_AIR_EMPTY    = 0x99122536;

    private BorderlandsHudRenderer() {}

    /**
     * Renders the bottom-left Borderlands status panel (EXP diamond, Armor, Health, Hunger+Saturation, Air bubbles).
     */
    public static void renderStatusHud(GuiGraphicsExtractor g, Minecraft mc) {
        Player player = mc.player;
        if (player == null || player.isSpectator()) {
            return;
        }
        Font font = mc.font;
        int screenH = g.guiHeight();

        // Anchor in bottom-left with comfortable breathing room
        int diamondRadius = 20;
        int dCx = 14 + diamondRadius;
        int dCy = screenH - 14 - diamondRadius;

        // Slanted metallic-dark backplate extending to the right of the diamond
        int panelX = dCx + 8;
        int panelY = dCy - 21;
        int panelW = 152;
        int panelH = 44;
        float slant = -0.22f; // Subtle Borderlands forward lean

        // 1. Underwater Respiration Bar (floats cleanly right above the panel when underwater or recovering air)
        int maxAir = Math.max(1, player.getMaxAirSupply());
        int air = Mth.clamp(player.getAirSupply(), 0, maxAir);
        boolean showAir = player.isEyeInFluid(FluidTags.WATER) || air < maxAir;
        if (showAir) {
            renderAirBubblesPanel(g, panelX + 8, panelY - 14, 134, air, maxAir, slant);
        }

        // 2. Main Slanted Backplate (outer dark comic ink + dark slate body + steel-cyan trim)
        drawSlantedRect(g, panelX - 1, panelY - 1, panelW + 2, panelH + 2, slant, COL_INK);
        drawSlantedRect(g, panelX, panelY, panelW, panelH, slant, COL_PANEL_BG);
        drawSlantedBorderTopBottom(g, panelX, panelY, panelW, panelH, slant, COL_PANEL_EDGE);

        // Bar layout inside the backplate
        int iconX = panelX + 16;
        int barX = panelX + 28;
        int barW = 114;

        // --- Row 1: Armor (Shield-style Cyan Bar) ---
        int armor = player.getArmorValue();
        float armorFrac = Mth.clamp(armor / 20.0f, 0.0f, 1.0f);
        int armorY = panelY + 4;
        drawShieldIcon(g, iconX, armorY + 1, armor > 0 ? 0xFFE2F5FF : 0xFF64748B);
        drawSlantedBar(g, barX, armorY, barW, 9, slant, armorFrac, COL_ARMOR_TRACK, COL_ARMOR_TOP, COL_ARMOR_BOT);
        g.text(font, String.valueOf(armor), barX + 5, armorY + 1, armor > 0 ? 0xFFFFFFFF : 0xFF94A3B8, true);

        // --- Row 2: Health (Crimson Red Bar + Golden Absorption Overlay) ---
        float hp = Math.max(0.0f, player.getHealth());
        float maxHp = Math.max(1.0f, player.getMaxHealth());
        float absorb = Math.max(0.0f, player.getAbsorptionAmount());
        float hpFrac = Mth.clamp(hp / maxHp, 0.0f, 1.0f);
        int hpY = panelY + 15;
        drawCrossIcon(g, iconX, hpY + 1, 0xFFFFFFFF);
        drawSlantedBar(g, barX, hpY, barW, 9, slant, hpFrac, COL_HP_TRACK, COL_HP_TOP, COL_HP_BOT);
        if (absorb > 0.0f) {
            float absFrac = Mth.clamp(absorb / maxHp, 0.0f, 1.0f);
            int absW = Math.max(2, Math.round(barW * absFrac));
            drawSlantedRect(g, barX, hpY, absW, 2, slant, COL_ABSORB);
        }
        int displayHp = Mth.ceil(hp + absorb);
        g.text(font, String.valueOf(displayHp), barX + 5, hpY + 1, absorb > 0.0f ? 0xFFFEF08A : 0xFFFFFFFF, true);

        // --- Row 3: Hunger (Amber Bar) + Thin Golden Saturation Subline ---
        FoodData foodData = player.getFoodData();
        int food = Mth.clamp(foodData.getFoodLevel(), 0, 20);
        float sat = Mth.clamp(foodData.getSaturationLevel(), 0.0f, 20.0f);
        float foodFrac = food / 20.0f;
        float satFrac = sat / 20.0f;
        int foodY = panelY + 26;
        drawHungerIcon(g, iconX, foodY, 0xFFFDE68A);
        drawSlantedBar(g, barX, foodY, barW, 7, slant, foodFrac, COL_FOOD_TRACK, COL_FOOD_TOP, COL_FOOD_BOT);
        g.text(font, String.valueOf(food), barX + 5, foodY - 1, 0xFFFFFFFF, true);

        // Saturation subline directly underneath the hunger bar
        int satY = foodY + 8;
        drawSlantedRect(g, barX - 1, satY, barW, 2, slant, COL_SAT_TRACK);
        int satFillW = Math.round(barW * satFrac);
        if (satFillW > 0) {
            drawSlantedRect(g, barX - 1, satY, satFillW, 2, slant, COL_SAT_FILL);
        }

        // Subtle player name footer on bottom edge (like Borderlands class subtitle)
        String subtitle = player.getName().getString();
        g.text(font, subtitle, panelX + 14, panelY + panelH + 2, 0xFFD0DCE8, true);

        // 3. Tilted Chamfered Diamond EXP Level Badge (drawn on top so it overlaps the left edge of the panel!)
        drawExpDiamondBadge(g, font, dCx, dCy, diamondRadius, player.experienceLevel, player.experienceProgress);
    }

    /**
     * Renders the stylized Borderlands bottom-center Hotbar (and offhand slot if occupied).
     */
    public static void renderHotbar(GuiGraphicsExtractor g, Minecraft mc, Player player) {
        Font font = mc.font;
        int centerX = g.guiWidth() / 2;
        int hotbarW = 182;
        int hotbarH = 22;
        int left = centerX - 91;
        int top = g.guiHeight() - hotbarH - 2;

        // Outer comic ink shadow & frame
        g.fill(left - 2, top - 2, left + hotbarW + 2, top + hotbarH + 2, COL_INK);
        g.fillGradient(left - 1, top - 1, left + hotbarW + 1, top + hotbarH + 1, 0xE6131A26, 0xE60A0F17);
        g.outline(left - 1, top - 1, hotbarW + 2, hotbarH + 2, COL_PANEL_EDGE);
        // Subtle cyan top highlight bar
        g.fill(left + 1, top, left + hotbarW - 1, top + 1, 0x4438BDF8);

        // Crisp vertical slot dividers
        for (int i = 1; i < 9; i++) {
            int dx = left + 1 + i * 20;
            g.fill(dx, top + 3, dx + 1, top + hotbarH - 3, 0x3A64748B);
        }

        // Selected slot golden Borderlands highlight
        int selected = player.getInventory().getSelectedSlot();
        int selX = left + selected * 20;
        g.fill(selX, top, selX + 22, top + hotbarH, 0x28FBBF24);
        g.outline(selX - 1, top - 1, 24, hotbarH + 2, COL_GOLD);
        // Signature Borderlands top & bottom golden accent ticks
        g.fill(selX + 4, top - 2, selX + 18, top, COL_GOLD);
        g.fill(selX + 4, top + hotbarH, selX + 18, top + hotbarH + 2, COL_GOLD);

        // Offhand slot in matching Borderlands style
        ItemStack offhand = player.getOffhandItem();
        HumanoidArm offhandArm = player.getMainArm().getOpposite();
        if (!offhand.isEmpty()) {
            int ox = (offhandArm == HumanoidArm.LEFT) ? (left - 29) : (left + hotbarW + 7);
            g.fill(ox - 2, top - 2, ox + 24, top + hotbarH + 2, COL_INK);
            g.fillGradient(ox - 1, top - 1, ox + 23, top + hotbarH + 1, 0xE6131A26, 0xE60A0F17);
            g.outline(ox - 1, top - 1, 24, hotbarH + 2, COL_PANEL_EDGE);
            g.fill(ox + 1, top, ox + 21, top + 1, 0x4438BDF8);
        }

        // Render all 9 hotbar items + offhand item via public GuiGraphicsExtractor API
        int seed = 1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                int ix = left + 3 + slot * 20;
                int iy = top + 3;
                g.item(player, stack, ix, iy, seed++);
                g.itemDecorations(font, stack, ix, iy);
            }
        }
        if (!offhand.isEmpty()) {
            int ox = (offhandArm == HumanoidArm.LEFT) ? (left - 26) : (left + hotbarW + 10);
            g.item(player, offhand, ox, top + 3, seed);
            g.itemDecorations(font, offhand, ox, top + 3);
        }
    }

    // -------------------------------------------------------------------------
    // Geometric Drawing Helpers (zero texture dependencies, razor-sharp at any GUI scale)
    // -------------------------------------------------------------------------

    private static void renderAirBubblesPanel(GuiGraphicsExtractor g, int x, int y, int w, int air, int maxAir, float slant) {
        int h = 11;
        drawSlantedRect(g, x - 1, y - 1, w + 2, h + 2, slant, COL_INK);
        drawSlantedRect(g, x, y, w, h, slant, 0xCC0A1826);
        drawSlantedBorderTopBottom(g, x, y, w, h, slant, 0xFF38BDF8);

        // Droplet icon on left
        drawDropletIcon(g, x + 5, y + 2, 0xFF7DD3FC);

        // 10 crisp respiration bubbles
        float airFrac = Mth.clamp((float) air / maxAir, 0.0f, 1.0f);
        float filledBubbles = airFrac * 10.0f;
        boolean lowAir = airFrac < 0.25f;
        int startX = x + 17;
        for (int i = 0; i < 10; i++) {
            int bx = startX + i * 11;
            int by = y + 2;
            float fill = Mth.clamp(filledBubbles - i, 0.0f, 1.0f);
            int bubbleCol = fill >= 0.5f ? (lowAir ? COL_AIR_LOW : COL_AIR_FULL) : COL_AIR_EMPTY;
            // Rounded 7x7 bubble shape
            g.fill(bx + 1, by, bx + 6, by + 7, COL_INK);
            g.fill(bx, by + 1, bx + 7, by + 6, COL_INK);
            g.fill(bx + 1, by + 1, bx + 6, by + 6, bubbleCol);
            if (fill >= 0.5f) {
                // Specular bubble glint
                g.fill(bx + 2, by + 2, bx + 4, by + 3, COL_AIR_HILITE);
            }
        }
    }

    private static void drawExpDiamondBadge(GuiGraphicsExtractor g, Font font, int cx, int cy, int r, int level, float progress) {
        // Outer dark comic ink octagon-diamond
        drawChamferedDiamond(g, cx, cy, r + 2, 4, COL_INK);
        // Outer XP border ring (dim gold track)
        drawChamferedDiamond(g, cx, cy, r, 4, COL_GOLD_DIM);
        // Active golden XP progress ring (fills from bottom upward smoothly)
        int progTop = cy + r - Math.round(Mth.clamp(progress, 0.0f, 1.0f) * (r * 2));
        drawChamferedDiamondClipped(g, cx, cy, r, 4, progTop, COL_GOLD);
        // Dark ink separator + inner obsidian-slate core
        drawChamferedDiamond(g, cx, cy, r - 3, 3, COL_INK);
        drawChamferedDiamond(g, cx, cy, r - 4, 3, 0xF20D131D);

        // Subtle inner top highlight
        g.fill(cx - 4, cy - r + 6, cx + 5, cy - r + 7, 0x30FBBF24);

        // Crisp centered EXP level number
        String lvlText = String.valueOf(level);
        int tw = font.width(lvlText);
        g.text(font, lvlText, cx - tw / 2, cy - 4, 0xFFF8FAFC, true);
    }

    private static void drawChamferedDiamond(GuiGraphicsExtractor g, int cx, int cy, int r, int chamfer, int color) {
        drawChamferedDiamondClipped(g, cx, cy, r, chamfer, cy - r, color);
    }

    private static void drawChamferedDiamondClipped(GuiGraphicsExtractor g, int cx, int cy, int r, int chamfer, int minY, int color) {
        int maxSpan = r - chamfer;
        for (int dy = -r; dy <= r; dy++) {
            int py = cy + dy;
            if (py < minY) continue;
            int halfW = Math.min(r - Math.abs(dy) + chamfer / 2, maxSpan + chamfer);
            halfW = Math.max(0, Math.min(r - Math.abs(dy), r - chamfer) + (Math.abs(dy) <= chamfer ? (chamfer - Math.abs(dy)) / 2 : 0));
            // Clean chamfered diamond: |dx| + |dy| <= r, clipped to |dx| <= r - chamfer/2 and |dy| <= r - chamfer/2
            if (Math.abs(dy) > r - 1) continue;
            int span = Math.min(r - Math.abs(dy), r - chamfer / 2);
            if (span >= 0) {
                g.fill(cx - span, py, cx + span + 1, py + 1, color);
            }
        }
    }

    private static void drawSlantedBar(
            GuiGraphicsExtractor g, int x, int y, int w, int h, float slant,
            float frac, int trackCol, int fillTop, int fillBot) {
        drawSlantedRect(g, x - 1, y - 1, w + 2, h + 2, slant, COL_INK);
        drawSlantedRect(g, x, y, w, h, slant, trackCol);
        int fillW = Math.round(w * Mth.clamp(frac, 0.0f, 1.0f));
        if (fillW > 0) {
            int mid = Math.max(1, h / 2);
            drawSlantedRect(g, x, y, fillW, mid, slant, fillTop);
            drawSlantedRect(g, x + Math.round(mid * slant), y + mid, fillW, h - mid, slant, fillBot);
            // Bright 1px specular sheen on top edge
            drawSlantedRect(g, x, y, fillW, 1, slant, 0x45FFFFFF);
        }
    }

    private static void drawSlantedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, float slant, int color) {
        for (int row = 0; row < h; row++) {
            int dx = Math.round(row * slant);
            g.fill(x + dx, y + row, x + dx + w, y + row + 1, color);
        }
    }

    private static void drawSlantedBorderTopBottom(GuiGraphicsExtractor g, int x, int y, int w, int h, float slant, int color) {
        g.fill(x, y, x + w, y + 1, color);
        int bdx = Math.round((h - 1) * slant);
        g.fill(x + bdx, y + h - 1, x + bdx + w, y + h, color);
        for (int row = 0; row < h; row++) {
            int dx = Math.round(row * slant);
            g.fill(x + dx + w - 1, y + row, x + dx + w, y + row + 1, color);
        }
    }

    // Crisp 7x7 vector-pixel icons matching the Borderlands reference HUD
    private static void drawShieldIcon(GuiGraphicsExtractor g, int x, int y, int col) {
        g.fill(x + 1, y, x + 6, y + 1, col);
        g.fill(x, y + 1, x + 7, y + 4, col);
        g.fill(x + 1, y + 4, x + 6, y + 5, col);
        g.fill(x + 2, y + 5, x + 5, y + 6, col);
        g.fill(x + 3, y + 6, x + 4, y + 7, col);
        g.fill(x + 2, y + 2, x + 5, y + 4, COL_PANEL_BG);
    }

    private static void drawCrossIcon(GuiGraphicsExtractor g, int x, int y, int col) {
        g.fill(x + 2, y, x + 5, y + 7, col);
        g.fill(x, y + 2, x + 7, y + 5, col);
    }

    private static void drawHungerIcon(GuiGraphicsExtractor g, int x, int y, int col) {
        // Stylized drumstick/meat icon
        g.fill(x + 2, y, x + 6, y + 4, col);
        g.fill(x + 1, y + 1, x + 7, y + 3, col);
        g.fill(x + 1, y + 4, x + 3, y + 6, 0xFFE2E8F0);
        g.fill(x, y + 5, x + 2, y + 7, 0xFFE2E8F0);
    }

    private static void drawDropletIcon(GuiGraphicsExtractor g, int x, int y, int col) {
        g.fill(x + 3, y, x + 4, y + 2, col);
        g.fill(x + 2, y + 2, x + 5, y + 4, col);
        g.fill(x + 1, y + 4, x + 6, y + 6, col);
        g.fill(x + 2, y + 6, x + 5, y + 7, col);
    }
}
