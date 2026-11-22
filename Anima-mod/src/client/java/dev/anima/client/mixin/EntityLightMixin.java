package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.DynamicLightTracker;
import dev.anima.core.AnimaConfig;
import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Illuminates dropped items, mobs, and the player with Anima's smooth dynamic light
 * (and makes dropped luminous items like torches/lanterns glow themselves).
 */
@Mixin(EntityRenderer.class)
public class EntityLightMixin {

    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private void anima$lightEntitiesAndDroppedItems(Entity entity, float partialTick, CallbackInfoReturnable<Integer> cir) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.dynamicHandheldLight) {
            return;
        }

        int packed = cir.getReturnValue();
        int currentSmoothBlock = LightCoordsUtil.smoothBlock(packed);
        int bestSmoothBlock = currentSmoothBlock;

        if (AnimaDynamicLightEngine.isActive()) {
            int dyn = AnimaDynamicLightEngine.computeSmoothDynamicBlockLight(
                    entity.getX(),
                    entity.getY() + entity.getBbHeight() * 0.5,
                    entity.getZ()
            );
            if (dyn > bestSmoothBlock) {
                bestSmoothBlock = dyn;
            }
        }

        // Dropped light-emitting items (torches, lanterns, glowstone) illuminate themselves
        if (entity instanceof ItemEntity itemEntity) {
            int ownLum = DynamicLightTracker.getItemLuminance(itemEntity.getItem(), itemEntity.isUnderWater());
            if (ownLum > 0) {
                int ownSmooth = Math.min(240, ownLum * 16);
                if (ownSmooth > bestSmoothBlock) {
                    bestSmoothBlock = ownSmooth;
                }
            }
        }

        if (bestSmoothBlock > currentSmoothBlock) {
            cir.setReturnValue(LightCoordsUtil.smoothPack(bestSmoothBlock, LightCoordsUtil.smoothSky(packed)));
        }
    }
}
