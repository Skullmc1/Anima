package dev.anima.core;

/**
 * Shader-free water visual enhancement engine.
 * Combines Beer-Lambert depth absorption (turquoise shallows -> deep sapphire),
 * Schlick Fresnel sky tinting, sparkling sun/moon specular glints, and warm torch shimmer.
 */
public final class AnimaWaterEngine {

    private AnimaWaterEngine() {}

    public static int enhanceBiomeWaterColor(int vanillaRgb, AnimaAtmosphere atm, AnimaConfig config) {
        if (!config.isActive() || !config.waterEnhancement) {
            return vanillaRgb;
        }
        float[] rgb = new float[3];
        ColorMath.unpackRgb(vanillaRgb, rgb);

        float[] targetTint = {0.14f, 0.62f, 0.74f};
        ColorMath.lerpRgb(rgb, targetTint, 0.45f * config.waterClarity, rgb);
        ColorMath.adjustSaturation(rgb, 1.14f);
        return ColorMath.packRgb(rgb[0], rgb[1], rgb[2]);
    }

    public static void shadeWaterSurface(
            float[] underwaterRgb,
            float waterDepth,
            float worldX,
            float worldZ,
            float viewDirX,
            float viewDirY,
            float viewDirZ,
            float skyLightNorm,
            float blockLightNorm,
            float timeSeconds,
            AnimaAtmosphere atm,
            AnimaConfig config,
            float[] outRgb) {

        if (!config.isActive() || !config.waterEnhancement) {
            float[] vanillaBlue = {
                    0.22f * atm.dayFactor + 0.04f,
                    0.40f * atm.dayFactor + 0.06f,
                    0.85f * atm.dayFactor + 0.10f
            };
            float alpha = 0.62f;
            outRgb[0] = ColorMath.lerp(underwaterRgb[0], vanillaBlue[0], alpha);
            outRgb[1] = ColorMath.lerp(underwaterRgb[1], vanillaBlue[1], alpha);
            outRgb[2] = ColorMath.lerp(underwaterRgb[2], vanillaBlue[2], alpha);
            return;
        }

        // Dual-octave procedural ripples for crisp, lively water surface
        float r1x = (float) (Math.sin(worldX * 2.6 + timeSeconds * 1.7) * Math.cos(worldZ * 2.1 - timeSeconds * 1.3)) * 0.045f;
        float r2x = (float) (Math.sin(worldX * 6.3 - worldZ * 4.7 + timeSeconds * 2.4)) * 0.022f;
        float r1z = (float) (Math.cos(worldX * 2.3 - timeSeconds * 1.5) * Math.sin(worldZ * 2.9 + timeSeconds * 1.6)) * 0.045f;
        float r2z = (float) (Math.cos(worldX * 5.1 + worldZ * 5.8 - timeSeconds * 2.1)) * 0.022f;

        float nx = r1x + r2x;
        float ny = 1.0f;
        float nz = r1z + r2z;
        float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= nLen; ny /= nLen; nz /= nLen;

        // Beer-Lambert depth absorption: shallow turquoise -> deep sapphire
        float clarity = ColorMath.lerp(0.65f, 1.28f, config.waterClarity);
        float absorbR = (float) Math.exp(-waterDepth * 0.56f / clarity);
        float absorbG = (float) Math.exp(-waterDepth * 0.23f / clarity);
        float absorbB = (float) Math.exp(-waterDepth * 0.15f / clarity);

        float[] deepColor = {
                0.04f * atm.dayFactor + 0.02f,
                0.22f * atm.dayFactor + 0.05f,
                0.40f * atm.dayFactor + 0.10f
        };
        float[] shallowTint = {
                0.14f * atm.dayFactor + 0.03f,
                0.58f * atm.dayFactor + 0.08f,
                0.64f * atm.dayFactor + 0.13f
        };

        float depthFactor = 1.0f - (float) Math.exp(-waterDepth * 0.42f);
        float[] waterBody = new float[3];
        ColorMath.lerpRgb(shallowTint, deepColor, depthFactor, waterBody);

        // Shallow sunlit caustics on riverbed
        float caustic = 1.0f;
        if (waterDepth < 4.8f && atm.dayFactor > 0.15f) {
            float c1 = (float) Math.sin(worldX * 4.5 + worldZ * 3.2 + timeSeconds * 2.0);
            float c2 = (float) Math.cos(worldX * 3.7 - worldZ * 4.9 + timeSeconds * 1.6);
            float cPattern = ColorMath.smoothstep(0.42f, 0.98f, Math.abs(c1 * c2));
            caustic = 1.0f + cPattern * 0.36f * (1.0f - waterDepth / 4.8f) * skyLightNorm * atm.dayFactor;
        }

        float refractedR = ColorMath.lerp(waterBody[0], underwaterRgb[0] * absorbR * caustic, absorbR * 0.85f + 0.15f);
        float refractedG = ColorMath.lerp(waterBody[1], underwaterRgb[1] * absorbG * caustic, absorbG * 0.85f + 0.15f);
        float refractedB = ColorMath.lerp(waterBody[2], underwaterRgb[2] * absorbB * caustic, absorbB * 0.85f + 0.15f);

        // Schlick Fresnel sky reflection
        float cosTheta = Math.max(0.02f, -(viewDirX * nx + viewDirY * ny + viewDirZ * nz));
        float fresnel = ColorMath.fresnelSchlick(cosTheta, 0.045f) * (0.48f + 0.46f * skyLightNorm);

        float dotVN = viewDirX * nx + viewDirY * ny + viewDirZ * nz;
        float rx = viewDirX - 2f * dotVN * nx;
        float ry = Math.max(0.02f, viewDirY - 2f * dotVN * ny);
        float rz = viewDirZ - 2f * dotVN * nz;
        float[] skyRefl = new float[3];
        AnimaSkyEngine.sampleSky(rx, ry, rz, atm, config, skyRefl);

        outRgb[0] = ColorMath.lerp(refractedR, skyRefl[0], fresnel);
        outRgb[1] = ColorMath.lerp(refractedG, skyRefl[1], fresnel);
        outRgb[2] = ColorMath.lerp(refractedB, skyRefl[2], fresnel);

        // Sparkling sun path specular glint across water ripples!
        float sunDotRefl = rx * atm.sunDirX + ry * atm.sunDirY + rz * atm.sunDirZ;
        if (atm.dayFactor > 0.05f && sunDotRefl > 0.86f) {
            float broad = (float) Math.pow((sunDotRefl - 0.86f) / 0.14f, 4.0) * (0.45f + 0.55f * atm.goldenHourFactor);
            float sharp = (float) Math.pow(Math.max(0f, (sunDotRefl - 0.95f) / 0.05f), 2.5) * 1.10f;
            float spec = (broad + sharp) * atm.dayFactor * skyLightNorm;
            outRgb[0] += atm.sunColor[0] * spec;
            outRgb[1] += atm.sunColor[1] * spec;
            outRgb[2] += atm.sunColor[2] * spec;
        }

        // Moonlit silver shimmer on water at night
        if (atm.nightFactor > 0.1f) {
            float moonDotRefl = -(rx * atm.sunDirX + ry * atm.sunDirY + rz * atm.sunDirZ);
            if (moonDotRefl > 0.88f) {
                float mSpec = (float) Math.pow((moonDotRefl - 0.88f) / 0.12f, 3.5) * 0.45f * atm.nightFactor;
                outRgb[0] += 0.55f * mSpec;
                outRgb[1] += 0.72f * mSpec;
                outRgb[2] += 1.00f * mSpec;
            }
        }

        // Warm glowing torchlight shimmer reflected on nearby water ripples!
        if (blockLightNorm > 0.05f) {
            float rippleSparkle = 0.75f + 0.45f * Math.abs((r1x + r2x) * 14f);
            float torchGlow = blockLightNorm * blockLightNorm * (0.42f + 0.55f * atm.nightFactor + 0.25f * atm.goldenHourFactor) * rippleSparkle;
            outRgb[0] += atm.torchColor[0] * torchGlow;
            outRgb[1] += atm.torchColor[1] * torchGlow;
            outRgb[2] += atm.torchColor[2] * torchGlow;
        }

        // Subtle shoreline foam accent
        float shoreFoam = (1.0f - ColorMath.smoothstep(0.05f, 0.40f, waterDepth)) * 0.16f * atm.dayFactor;
        outRgb[0] = ColorMath.clamp01(outRgb[0] + shoreFoam);
        outRgb[1] = ColorMath.clamp01(outRgb[1] + shoreFoam);
        outRgb[2] = ColorMath.clamp01(outRgb[2] + shoreFoam);
    }
}
