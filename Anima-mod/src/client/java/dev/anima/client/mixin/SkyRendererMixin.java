package dev.anima.client.mixin;

import dev.anima.AnimaMod;
import dev.anima.client.AnimaClientMod;
import dev.anima.core.AnimaAtmosphere;
import dev.anima.core.AnimaConfig;
import dev.anima.core.ColorMath;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enhances Minecraft 26.2's sky dome zenith color, sunset glow band, and night starfield.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void anima$enhanceSkyState(ClientLevel level, float partialTick, Camera camera, SkyRenderState state, CallbackInfo ci) {
        AnimaConfig cfg = AnimaMod.config();
        if (!cfg.isActive() || !cfg.skyEnhancement) {
            return;
        }
        AnimaAtmosphere atm = AnimaClientMod.atmosphere();

        float[] vSky = new float[3];
        ColorMath.unpackRgb(state.skyColor, vSky);
        float r = ColorMath.lerp(vSky[0], atm.zenithColor[0], 0.84f);
        float g = ColorMath.lerp(vSky[1], atm.zenithColor[1], 0.84f);
        float b = ColorMath.lerp(vSky[2], atm.zenithColor[2], 0.84f);
        state.skyColor = ColorMath.packRgb(r, g, b);

        if (atm.goldenHourFactor > 0.04f) {
            state.sunriseAndSunsetColor = ColorMath.packArgb(
                    Math.min(1f, atm.goldenHourFactor * 0.96f),
                    atm.horizonColor[0],
                    atm.horizonColor[1],
                    atm.horizonColor[2]
            );
        }

        if (atm.nightFactor > 0.08f) {
            state.starBrightness = Math.min(1.0f, state.starBrightness * 1.45f + 0.18f * atm.nightFactor);
        }
    }
}
