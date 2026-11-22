package dev.anima.core;

/**
 * Shader-free water visual enhancement engine.
 * Combines Beer-Lambert depth absorption (turquoise shallows -> deep sapphire),
 * Schlick Fresnel sky tinting, and subtle procedural surface shimmer.
 */
public final class AnimaWaterEngine {

    private AnimaWaterEngine() {}

    /**
     * Returns an enhanced biome water tint RGB for Minecraft's native water color hook.
     */
    public static int enhanceBiomeWaterColor(int vanillaRgb, AnimaAtmosphere atm, AnimaConfig config) {
        if (!config.isActive() || !config.waterEnhancement) {
            return vanillaRgb;
        }
        float[] rgb = new float[3];
        ColorMath.unpackRgb(vanillaRgb, rgb);

        // Shift muddy vanilla blue toward clearer crystal turquoise-teal
        float[] targetTint = {0.16f, 0.62f, 0.74f};
        ColorMath.lerpRgb(rgb, targetTint, 0.42f * config.waterClarity, rgb);
        ColorMath.adjustSaturation(rgb, 1.12f);
        return ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * Shades a water surface hit given the refracted riverbed color underneath, water depth,
     * view ray, and world position. Used by Anima-renderer and mirrors Anima's water aesthetic.
     */
    public static void shadeWaterSurface(
            float[] underwaterRgb,
            float waterDepth,
            float worldX,
            float worldZ,
            float viewDirX,
            float viewDirY,
            float viewDirZ,
            float skyLightNorm,
            float timeSeconds,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outRgb) {

        if (!config.isActive() || !config.waterEnhancement) {
            // Vanilla Minecraft translucent flat blue water
            float[] vanillaBlue = {0.22f * atm.dayFactor + 0.04f, 0.40f * atm.dayFactor + 0.06f, 0.85f * atm.dayFactor + 0.10f};
            float alpha = 0.62f;
            outRgb[0] = ColorMath.lerp(underwaterRgb[0], vanillaBlue[0], alpha);
            outRgb[1] = ColorMath.lerp(underwaterRgb[1], vanillaBlue[1], alpha);
            outRgb[2] = ColorMath.lerp(underwaterRgb[2], vanillaBlue[2], alpha);
            return;
        }

        // Subtle procedural ripples (perturbed normal) for sparkling water surface
        float rippleX = (float) (Math.sin(worldX * 2.9 + timeSeconds * 1.8) * Math.cos(worldZ * 2.3 - timeSeconds * 1.4)) * 0.055f;
        float rippleZ = (float) (Math.cos(worldX * 2.4 - timeSeconds * 1.5) * Math.sin(worldZ * 3.1 + timeSeconds * 1.7)) * 0.055f;
        float nx = rippleX;
        float ny = 1.0f;
        float nz = rippleZ;
        float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= nLen; ny /= nLen; nz /= nLen;

        // Beer-Lambert depth absorption: shallow water is crystal-clear turquoise, deep water is rich sapphire
        float clarity = ColorMath.lerp(0.65f, 1.25f, config.waterClarity);
        float absorbR = (float) Math.exp(-waterDepth * 0.58f / clarity);
        float absorbG = (float) Math.exp(-waterDepth * 0.24f / clarity);
        float absorbB = (float) Math.exp(-waterDepth * 0.16f / clarity);

        float[] deepColor = {
                0.04f * atm.dayFactor + 0.01f,
                0.22f * atm.dayFactor + 0.03f,
                0.38f * atm.dayFactor + 0.07f
        };
        float[] shallowTint = {
                0.14f * atm.dayFactor + 0.02f,
                0.56f * atm.dayFactor + 0.06f,
                0.62f * atm.dayFactor + 0.10f
        };

        float depthFactor = 1.0f - (float) Math.exp(-waterDepth * 0.42f);
        float[] waterBody = new float[3];
        ColorMath.lerpRgb(shallowTint, deepColor, depthFactor, waterBody);

        // Subtle underwater caustic shimmer on shallow sunlit beds
        float caustic = 1.0f;
        if (waterDepth < 4.5f && atm.dayFactor > 0.15f) {
            float c1 = (float) Math.sin(worldX * 4.5 + worldZ * 3.2 + timeSeconds * 2.0);
            float c2 = (float) Math.cos(worldX * 3.7 - worldZ * 4.9 + timeSeconds * 1.6);
            float cPattern = ColorMath.smoothstep(0.45f, 0.98f, Math.abs(c1 * c2));
            caustic = 1.0f + cPattern * 0.32f * (1.0f - waterDepth / 4.5f) * skyLightNorm * atm.dayFactor;
        }

        float refractedR = ColorMath.lerp(waterBody[0], underwaterRgb[0] * absorbR * caustic, absorbR * 0.85f + 0.15f);
        float refractedG = ColorMath.lerp(waterBody[1], underwaterRgb[1] * absorbG * caustic, absorbG * 0.85f + 0.15f);
        float refractedB = ColorMath.lerp(waterBody[2], underwaterRgb[2] * absorbB * caustic, absorbB * 0.85f + 0.15f);

        // Schlick Fresnel sky reflection
        float cosTheta = Math.max(0.02f, -(viewDirX * nx + viewDirY * ny + viewDirZ * nz));
        float fresnel = ColorMath.fresnelSchlick(cosTheta, 0.04f) * (0.45f + 0.45f * skyLightNorm);

        // Reflected ray into sky dome
        float dotVN = viewDirX * nx + viewDirY * ny + viewDirZ * nz;
        float rx = viewDirX - 2f * dotVN * nx;
        float ry = Math.max(0.02f, viewDirY - 2f * dotVN * ny);
        float rz = viewDirZ - 2f * dotVN * nz;
        float[] skyRefl = new float[3];
        AnimaSkyEngine.sampleSky(rx, ry, rz, atm, config, skyRefl);

        outRgb[0] = ColorMath.lerp(refractedR, skyRefl[0], fresnel);
        outRgb[1] = ColorMath.lerp(refractedG, skyRefl[1], fresnel);
        outRgb[2] = ColorMath.lerp(refractedB, skyRefl[2], fresnel);

        // Crisp sun/moon specular glint on water ripples
        float sunDotRefl = rx * atm.sunDirX + ry * atm.sunDirY + rz * atm.sunDirZ;
        if (atm.dayFactor > 0.05f && sunDotRefl > 0.92f) {
            float spec = (float) Math.pow((sunDotRefl - 0.92f) / 0.08f, 3.0) * 0.95f * atm.dayFactor * skyLightNorm;
            outRgb[0] += atm.sunColor[0] * spec;
            outRgb[1] += atm.sunColor[1] * spec;
            outRgb[2] += atm.sunColor[2] * spec;
        }

        // Subtle shoreline foam highlight at very shallow edges
        float shoreFoam = (1.0f - ColorMath.smoothstep(0.05f, 0.42f, waterDepth)) * 0.18f * atm.dayFactor;
        outRgb[0] = ColorMath.clamp01(outRgb[0] + shoreFoam);
        outRgb[1] = ColorMath.clamp01(outRgb[1] + shoreFoam);
        outRgb[2] = ColorMath.clamp01(outRgb[2] + shoreFoam);
    }
}
