package dev.anima.client;

import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Tracks light-emitting blocks/items held in the player's main hand or offhand
 * AND nearby dropped luminous ItemEntities, driving smooth fading transitions
 * in AnimaDynamicLightEngine and refreshing affected chunk sections.
 */
public final class DynamicLightTracker {

    private static final double[] droppedX = new double[AnimaDynamicLightEngine.MAX_SOURCES];
    private static final double[] droppedY = new double[AnimaDynamicLightEngine.MAX_SOURCES];
    private static final double[] droppedZ = new double[AnimaDynamicLightEngine.MAX_SOURCES];
    private static final float[] droppedLum = new float[AnimaDynamicLightEngine.MAX_SOURCES];

    private static boolean wasActive = false;
    private static int tickCounter = 0;

    private DynamicLightTracker() {}

    public static void tick(Minecraft client) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        AnimaConfig cfg = AnimaMod.config();

        if (level == null || player == null || !cfg.isActive() || !cfg.dynamicHandheldLight) {
            if (wasActive) {
                AnimaDynamicLightEngine.clear();
                wasActive = false;
                if (level != null && player != null) {
                    markSectionsAround(level, player.getX(), player.getY(), player.getZ(), 16);
                }
            }
            return;
        }

        tickCounter++;

        // 1. Player main hand & offhand
        boolean submerged = player.isUnderWater();
        int mainLight = getItemLuminance(player.getMainHandItem(), submerged);
        int offLight = getItemLuminance(player.getOffhandItem(), submerged);
        int targetPlayerLum = Math.max(mainLight, offLight);

        double px = player.getX();
        double py = player.getEyeY() - 0.35;
        double pz = player.getZ();

        // 2. Scan nearby dropped luminous ItemEntities (within 22 blocks)
        int dCount = 0;
        AABB searchBox = player.getBoundingBox().inflate(22.0);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, searchBox);
        for (int i = 0; i < items.size() && dCount < AnimaDynamicLightEngine.MAX_SOURCES - 1; i++) {
            ItemEntity ie = items.get(i);
            int lum = getItemLuminance(ie.getItem(), ie.isUnderWater());
            if (lum > 0) {
                droppedX[dCount] = ie.getX();
                droppedY[dCount] = ie.getY() + 0.25;
                droppedZ[dCount] = ie.getZ();
                droppedLum[dCount] = lum;
                dCount++;
            }
        }

        // 3. Smoothly fade luminance & glide position in AnimaDynamicLightEngine
        boolean changed = AnimaDynamicLightEngine.updateFrame(
                px, py, pz, targetPlayerLum,
                droppedX, droppedY, droppedZ, droppedLum, dCount
        );

        boolean nowActive = AnimaDynamicLightEngine.isActive();

        // Throttle slightly on Potato preset (every 2 ticks), smooth every tick on Balanced/Lush
        boolean allowedThisTick = (cfg.preset != AnimaConfig.Preset.POTATO) || ((tickCounter & 1) == 0);

        if ((changed || wasActive != nowActive) && allowedThisTick) {
            if (nowActive || wasActive) {
                markSectionsAround(level, px, py, pz, Math.max(targetPlayerLum, 14) + 2);
                for (int i = 0; i < dCount; i++) {
                    markSectionsAround(level, droppedX[i], droppedY[i], droppedZ[i], (int) droppedLum[i] + 2);
                }
            }
        }

        wasActive = nowActive;
    }

    /**
     * Determines the light emission level (0..15) of any held or dropped block/item.
     * Automatically supports ALL vanilla and modded light-emitting blocks via BlockState.getLightEmission().
     */
    public static int getItemLuminance(ItemStack stack, boolean submerged) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        Item item = stack.getItem();

        if (item == Items.LAVA_BUCKET) return submerged ? 10 : 15;
        if (item == Items.NETHER_STAR || item == Items.END_CRYSTAL) return 12;
        if (item == Items.BLAZE_ROD || item == Items.BLAZE_POWDER) return submerged ? 0 : 10;
        if (item == Items.GLOW_BERRIES) return 10;
        if (item == Items.GLOWSTONE_DUST) return 9;
        if (item == Items.GLOW_INK_SAC || item == Items.SPECTRAL_ARROW) return 8;
        if (item == Items.MAGMA_CREAM || item == Items.ENCHANTED_GOLDEN_APPLE || item == Items.EXPERIENCE_BOTTLE) return 7;

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

    private static void markSectionsAround(ClientLevel level, double x, double y, double z, int r) {
        int minSx = ((int) Math.floor(x - r)) >> 4;
        int minSy = ((int) Math.floor(y - r)) >> 4;
        int minSz = ((int) Math.floor(z - r)) >> 4;
        int maxSx = ((int) Math.floor(x + r)) >> 4;
        int maxSy = ((int) Math.floor(y + r)) >> 4;
        int maxSz = ((int) Math.floor(z + r)) >> 4;
        level.setSectionRangeDirty(minSx, minSy, minSz, maxSx, maxSy, maxSz);
    }
}
