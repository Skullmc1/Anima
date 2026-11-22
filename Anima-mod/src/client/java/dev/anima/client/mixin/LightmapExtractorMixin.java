package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.ColorMath;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects Anima's atmospheric lightmap colors (warm golden-hour sunlight, cool sky-bounce
 * ambient shadows, silver-blue moonlight, and warm flickering torchlight) directly into
 * Minecraft 26.2's LightmapRenderState — zero custom GPU shaders required.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapExtractorMixin {

    @Inject(method = "extract", at = @At("TAIL"))
    private void anima$applyAtmosphericLightmap(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.atmosphericLightmap) {
            return;
        }

        AnimaAtmosphere atm = AnimaClientMod.atmosphere();
        float t = AnimaClientMod.elapsedSeconds();

        // Warm golden-hour / crisp midday sky light tint
        float warmth = cfg.warmthStrength;
        float skyR = ColorMath.lerp(state.skyLightColor.x(), atm.sunColor[0], 0.55f * warmth);
        float skyG = ColorMath.lerp(state.skyLightColor.y(), atm.sunColor[1], 0.55f * warmth);
        float skyB = ColorMath.lerp(state.skyLightColor.z(), atm.sunColor[2], 0.55f * warmth);
        state.skyLightColor = new Vector3f(skyR, skyG, skyB);

        // Cool atmospheric shadow fill instead of muddy gray
        float ambR = ColorMath.lerp(state.ambientColor.x(), atm.shadowTintColor[0] * 0.28f, 0.65f * cfg.shadowDepth);
        float ambG = ColorMath.lerp(state.ambientColor.y(), atm.shadowTintColor[1] * 0.28f, 0.65f * cfg.shadowDepth);
        float ambB = ColorMath.lerp(state.ambientColor.z(), atm.shadowTintColor[2] * 0.32f, 0.65f * cfg.shadowDepth);
        state.ambientColor = new Vector3f(ambR, ambG, ambB);

        // Cozy 2700K incandescent torchlight with subtle organic flicker
        float flicker = 1.0f;
        if (cfg.torchFlicker > 0.001f) {
            flicker += (float) (Math.sin(t * 7.3) * 0.02 + Math.sin(t * 13.7) * 0.012) * cfg.torchFlicker;
        }
        state.blockLightTint = new Vector3f(
                atm.torchColor[0] * flicker,
                atm.torchColor[1] * flicker,
                atm.torchColor[2] * flicker
        );
        state.blockFactor = ColorMath.clamp(state.blockFactor * (1.08f + 0.14f * atm.nightFactor), 0f, 1.35f);
        state.needsUpdate = true;
    }
}
