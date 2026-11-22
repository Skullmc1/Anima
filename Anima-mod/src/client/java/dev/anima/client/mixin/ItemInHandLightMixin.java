package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.DynamicLightTracker;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Ensures held light sources (torches, lanterns, glowstone, etc.) and items held
 * near dynamic light are warmly illuminated in first-person instead of appearing dark.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandLightMixin {

    @ModifyVariable(method = "renderItem", at = @At("HEAD"), argsOnly = true, index = 6)
    private int anima$lightHeldItem(int packedLight, LivingEntity entity, ItemStack stack) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.dynamicHandheldLight) {
            return packedLight;
        }

        int currentSmoothBlock = LightCoordsUtil.smoothBlock(packedLight);
        int bestSmoothBlock = currentSmoothBlock;

        // If this held item itself is a light source, light it at its own luminance!
        int ownLum = DynamicLightTracker.getItemLuminance(stack, entity != null && entity.isUnderWater());
        if (ownLum > 0) {
            bestSmoothBlock = Math.max(bestSmoothBlock, Math.min(240, ownLum * 16));
        }

        // Also light any held item when illuminated by offhand or nearby dropped dynamic light
        if (entity != null && AnimaDynamicLightEngine.isActive()) {
            int dyn = AnimaDynamicLightEngine.computeSmoothDynamicBlockLight(
                    entity.getX(),
                    entity.getEyeY() - 0.3,
                    entity.getZ()
            );
            bestSmoothBlock = Math.max(bestSmoothBlock, dyn);
        }

        if (bestSmoothBlock > currentSmoothBlock) {
            return LightCoordsUtil.smoothPack(bestSmoothBlock, LightCoordsUtil.smoothSky(packedLight));
        }
        return packedLight;
    }
}
