package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.core.AnimaConfig;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tags water vertices emitted by Minecraft 26.2's FluidRenderer with alpha = 254 (0xFE)
 * when Anima's water enhancement is active so terrain.vsh / terrain.fsh apply smooth
 * animated waves, ripples, and Fresnel sky sheen ONLY to water (zero cost on solid blocks).
 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin {

    @Unique
    private static final ThreadLocal<Boolean> anima$isWater = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "tesselate", at = @At("HEAD"))
    private void anima$markFluidType(
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        anima$isWater.set(cfg.isActive() && cfg.waterEnhancement && fluidState.is(FluidTags.WATER));
    }

    @Inject(method = "tesselate", at = @At("RETURN"))
    private void anima$clearFluidType(
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci) {
        anima$isWater.set(Boolean.FALSE);
    }

    @ModifyVariable(method = "vertex", at = @At("HEAD"), argsOnly = true, index = 5)
    private int anima$tagWaterVertexColor(int color) {
        if (Boolean.TRUE.equals(anima$isWater.get())) {
            // Set alpha byte to 254 (0xFE) as a zero-overhead flag for terrain.vsh/fsh
            return (color & 0x00FFFFFF) | 0xFE000000;
        }
        return color;
    }
}
