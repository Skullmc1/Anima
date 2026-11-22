package dev.anima.client;

import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tracks light-emitting blocks and items held in the player's main hand OR offhand,
 * updating AnimaDynamicLightEngine and triggering minimal chunk section light refreshes
 * as the player moves.
 */
public final class DynamicLightTracker {

    private static double lastUpdateX = Double.NaN;
    private static double lastUpdateY = Double.NaN;
    private static double lastUpdateZ = Double.NaN;
    private static int lastLuminance = 0;

    private DynamicLightTracker() {}

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        AnimaConfig cfg = AnimaMod.config();

        if (level == null || player == null || !cfg.isActive() || !cfg.dynamicHandheldLight) {
            if (lastLuminance > 0) {
                int oldLum = lastLuminance;
                AnimaDynamicLightEngine.clear();
                lastLuminance = 0;
                if (level != null && !Double.isNaN(lastUpdateX)) {
                    markAffectedSectionsDirty(level, lastUpdateX, lastUpdateY, lastUpdateZ, oldLum);
                }
                lastUpdateX = Double.NaN;
            }
            return;
        }

        boolean submerged = player.isUnderWater();
        int mainLight = getItemLuminance(player.getMainHandItem(), submerged);
        int offLight = getItemLuminance(player.getOffhandItem(), submerged);
        int currentLum = Math.max(mainLight, offLight);

        double px = player.getX();
        double py = player.getEyeY() - 0.35;
        double pz = player.getZ();

        if (currentLum <= 0) {
            if (lastLuminance > 0) {
                int oldLum = lastLuminance;
                AnimaDynamicLightEngine.clear();
                lastLuminance = 0;
                if (!Double.isNaN(lastUpdateX)) {
                    markAffectedSectionsDirty(level, lastUpdateX, lastUpdateY, lastUpdateZ, oldLum);
                }
                lastUpdateX = Double.NaN;
            }
            return;
        }

        // Potato preset updates every 1.15 blocks for maximum FPS; Balanced/Lush every 0.65 blocks for silky movement
        double moveThreshold = (cfg.preset == AnimaConfig.Preset.POTATO) ? 1.15 : 0.65;
        boolean movedEnough = Double.isNaN(lastUpdateX)
                || distSq(px, py, pz, lastUpdateX, lastUpdateY, lastUpdateZ) >= moveThreshold * moveThreshold;
        boolean lumChanged = currentLum != lastLuminance;

        if (movedEnough || lumChanged) {
            double oldX = lastUpdateX;
            double oldY = lastUpdateY;
            double oldZ = lastUpdateZ;
            int oldLum = lastLuminance;

            AnimaDynamicLightEngine.updateSource(px, py, pz, currentLum);

            // Refresh union of previous and new lit chunk sections
            if (oldLum > 0 && !Double.isNaN(oldX)) {
                markUnionSectionsDirty(level, oldX, oldY, oldZ, oldLum, px, py, pz, currentLum);
            } else {
                markAffectedSectionsDirty(level, px, py, pz, currentLum);
            }

            lastUpdateX = px;
            lastUpdateY = py;
            lastUpdateZ = pz;
            lastLuminance = currentLum;
        }
    }

    /**
     * Determines the light emission level (0..15) of any held block or item.
     * Automatically supports ALL vanilla and modded light-emitting blocks via BlockState.getLightEmission()!
     */
    public static int getItemLuminance(ItemStack stack, boolean submerged) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();

        // Non-block luminous items
        if (item == Items.LAVA_BUCKET) return submerged ? 10 : 15;
        if (item == Items.NETHER_STAR || item == Items.END_CRYSTAL) return 12;
        if (item == Items.BLAZE_ROD || item == Items.BLAZE_POWDER) return submerged ? 0 : 10;
        if (item == Items.GLOW_BERRIES) return 10;
        if (item == Items.GLOWSTONE_DUST) return 9;
        if (item == Items.GLOW_INK_SAC || item == Items.SPECTRAL_ARROW) return 8;
        if (item == Items.MAGMA_CREAM || item == Items.ENCHANTED_GOLDEN_APPLE || item == Items.EXPERIENCE_BOTTLE) return 7;

        // Automatic support for ALL light-source blocks (torches, lanterns, glowstone, sea lantern,
        // shroomlight, froglights, jack o'lantern, campfire, end rod, beacon, redstone torch, etc.)
        if (item instanceof BlockItem blockItem) {
            int emission = blockItem.getBlock().defaultBlockState().getLightEmission();
            if (emission > 0) {
                if (submerged && isExtinguishedUnderwater(item)) {
                    return 0;
                }
                return Math.min(15, emission);
            }
        }

        return 0;
    }

    private static boolean isExtinguishedUnderwater(Item item) {
        return item == Items.TORCH
                || item == Items.SOUL_TORCH
                || item == Items.REDSTONE_TORCH
                || item == Items.CAMPFIRE
                || item == Items.SOUL_CAMPFIRE;
    }

    private static void markAffectedSectionsDirty(ClientLevel level, double x, double y, double z, int lum) {
        int r = lum + 1;
        int minSx = ((int) Math.floor(x - r)) >> 4;
        int minSy = ((int) Math.floor(y - r)) >> 4;
        int minSz = ((int) Math.floor(z - r)) >> 4;
        int maxSx = ((int) Math.floor(x + r)) >> 4;
        int maxSy = ((int) Math.floor(y + r)) >> 4;
        int maxSz = ((int) Math.floor(z + r)) >> 4;
        level.setSectionRangeDirty(minSx, minSy, minSz, maxSx, maxSy, maxSz);
    }

    private static void markUnionSectionsDirty(
            ClientLevel level,
            double x1, double y1, double z1, int lum1,
            double x2, double y2, double z2, int lum2) {
        int r1 = lum1 + 1;
        int r2 = lum2 + 1;
        int minSx = Math.min(((int) Math.floor(x1 - r1)) >> 4, ((int) Math.floor(x2 - r2)) >> 4);
        int minSy = Math.min(((int) Math.floor(y1 - r1)) >> 4, ((int) Math.floor(y2 - r2)) >> 4);
        int minSz = Math.min(((int) Math.floor(z1 - r1)) >> 4, ((int) Math.floor(z2 - r2)) >> 4);
        int maxSx = Math.max(((int) Math.floor(x1 + r1)) >> 4, ((int) Math.floor(x2 + r2)) >> 4);
        int maxSy = Math.max(((int) Math.floor(y1 + r1)) >> 4, ((int) Math.floor(y2 + r2)) >> 4);
        int maxSz = Math.max(((int) Math.floor(z1 + r1)) >> 4, ((int) Math.floor(z2 + r2)) >> 4);
        level.setSectionRangeDirty(minSx, minSy, minSz, maxSx, maxSy, maxSz);
    }

    private static double distSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }
}
