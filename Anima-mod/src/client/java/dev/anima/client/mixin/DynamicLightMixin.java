package dev.anima.client.mixin;

import dev.anima.core.AnimaDynamicLightEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects Anima's shader-free dynamic handheld light (main hand & offhand)
 * directly into Minecraft 26.2's BlockLightEngine lookup path.
 */
@Mixin(LightEngine.class)
public class DynamicLightMixin {

    @Inject(method = "getLightValue", at = @At("RETURN"), cancellable = true)
    private void anima$applyDynamicHandheldLight(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!AnimaDynamicLightEngine.isActive()) {
            return;
        }
        if ((Object) this instanceof BlockLightEngine) {
            int vanilla = cir.getReturnValue();
            if (vanilla < 15) {
                int dynamic = AnimaDynamicLightEngine.computeDynamicBlockLight(
                        pos.getX() + 0.5,
                        pos.getY() + 0.5,
                        pos.getZ() + 0.5
                );
                if (dynamic > vanilla) {
                    cir.setReturnValue(dynamic);
                }
            }
        }
    }
}
