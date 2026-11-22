package dev.anima.core;

/**
 * Computes time-of-day solar geometry and atmospheric weights from Minecraft's
 * 24,000-tick day cycle (0 = dawn, 6000 = noon, 12000 = sunset, 18000 = midnight).
 */
public final class AnimaAtmosphere {

    public final float dayTimeTicks;
    public final float sunAngleRad;
    public final float sunDirX;
    public final float sunDirY;
    public final float sunDirZ;

    /** 1.0 at full daylight, 0.0 at deep night. */
    public final float dayFactor;
    /** 1.0 at golden hour (sunrise/sunset), 0.0 at noon or midnight. */
    public final float goldenHourFactor;
    /** 1.0 at deep night, 0.0 during day. */
    public final float nightFactor;
    /** 0.0 = clear sky, 1.0 = heavy rain. */
    public final float rainFactor;

    public final float[] sunColor = new float[3];
    public final float[] skyAmbientColor = new float[3];
    public final float[] shadowTintColor = new float[3];
    public final float[] horizonColor = new float[3];
    public final float[] zenithColor = new float[3];
    public final float[] fogColor = new float[3];
    public final float[] torchColor = new float[3];

    public AnimaAtmosphere(float dayTimeTicks, float rainFactor) {
        this.dayTimeTicks = ((dayTimeTicks % 24000f) + 24000f) % 24000f;
        this.rainFactor = ColorMath.clamp01(rainFactor);

        // Minecraft solar cycle: 0 = sunrise, 6000 = noon, 12000 = sunset, 18000 = midnight
        this.sunAngleRad = (this.dayTimeTicks / 24000f) * (float) (Math.PI * 2.0);
        float elevation = (float) Math.sin(sunAngleRad);
        float horizontal = (float) Math.cos(sunAngleRad);

        // Angled solar arc (+Z/+X tilt) so sunrise/sunset glows across the horizon and models all 3 block axes
        float rawX = horizontal * 0.72f + 0.28f;
        float rawY = elevation;
        float rawZ = 0.68f - horizontal * 0.24f;
        float len = (float) Math.sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ);
        this.sunDirX = rawX / len;
        this.sunDirY = rawY / len;
        this.sunDirZ = rawZ / len;

        this.dayFactor = ColorMath.smoothstep(-0.16f, 0.24f, elevation);
        this.nightFactor = 1f - ColorMath.smoothstep(-0.20f, 0.08f, elevation);
        float riseBell = (float) Math.exp(-Math.pow((elevation - 0.14f) / 0.21f, 2.0));
        this.goldenHourFactor = ColorMath.clamp01(riseBell * (1f - this.rainFactor * 0.7f));

        computePalettes();
    }

    private void computePalettes() {
        // 1. Direct sunlight / moonlight color
        float[] noonSun = {1.00f, 0.97f, 0.90f};
        float[] goldenSun = {1.00f, 0.62f, 0.26f};
        float[] moonLight = {0.48f, 0.64f, 0.94f};

        float[] daySun = new float[3];
        ColorMath.lerpRgb(noonSun, goldenSun, goldenHourFactor, daySun);
        ColorMath.lerpRgb(moonLight, daySun, dayFactor, sunColor);

        // 2. Sky ambient fill color
        float[] noonAmbient = {0.68f, 0.84f, 1.00f};
        float[] goldenAmbient = {0.74f, 0.56f, 0.68f};
        float[] nightAmbient = {0.18f, 0.27f, 0.48f};

        float[] dayAmbient = new float[3];
        ColorMath.lerpRgb(noonAmbient, goldenAmbient, goldenHourFactor, dayAmbient);
        ColorMath.lerpRgb(nightAmbient, dayAmbient, dayFactor, skyAmbientColor);

        // 3. Shadow fill tint (cool sky-bounce makes shadows look atmospheric instead of muddy gray)
        float[] noonShadow = {0.48f, 0.64f, 0.90f};
        float[] goldenShadow = {0.42f, 0.39f, 0.66f};
        float[] nightShadow = {0.13f, 0.20f, 0.38f};

        float[] dayShadow = new float[3];
        ColorMath.lerpRgb(noonShadow, goldenShadow, goldenHourFactor, dayShadow);
        ColorMath.lerpRgb(nightShadow, dayShadow, dayFactor, shadowTintColor);

        // 4. Sky zenith & horizon colors (Complementary Reimagined-inspired palettes)
        float[] noonZenith = {0.14f, 0.42f, 0.88f};
        float[] goldenZenith = {0.18f, 0.24f, 0.54f};
        float[] nightZenith = {0.04f, 0.09f, 0.24f};

        float[] dayZenith = new float[3];
        ColorMath.lerpRgb(noonZenith, goldenZenith, goldenHourFactor, dayZenith);
        ColorMath.lerpRgb(nightZenith, dayZenith, dayFactor, zenithColor);

        float[] noonHorizon = {0.66f, 0.85f, 0.99f};
        float[] goldenHorizon = {1.00f, 0.58f, 0.30f};
        float[] nightHorizon = {0.12f, 0.20f, 0.39f};

        float[] dayHorizon = new float[3];
        ColorMath.lerpRgb(noonHorizon, goldenHorizon, goldenHourFactor, dayHorizon);
        ColorMath.lerpRgb(nightHorizon, dayHorizon, dayFactor, horizonColor);

        // 5. Atmospheric perspective fog color
        float[] noonFog = {0.66f, 0.84f, 0.98f};
        float[] goldenFog = {0.95f, 0.60f, 0.38f};
        float[] nightFog = {0.10f, 0.17f, 0.34f};

        float[] dayFog = new float[3];
        ColorMath.lerpRgb(noonFog, goldenFog, goldenHourFactor, dayFog);
        ColorMath.lerpRgb(nightFog, dayFog, dayFactor, fogColor);

        // Rain desaturation & overcast shift
        if (rainFactor > 0.001f) {
            float[] rainGrey = {0.42f * dayFactor + 0.05f, 0.47f * dayFactor + 0.06f, 0.54f * dayFactor + 0.09f};
            ColorMath.lerpRgb(zenithColor, rainGrey, rainFactor * 0.75f, zenithColor);
            ColorMath.lerpRgb(horizonColor, rainGrey, rainFactor * 0.70f, horizonColor);
            ColorMath.lerpRgb(fogColor, rainGrey, rainFactor * 0.75f, fogColor);
            ColorMath.lerpRgb(sunColor, rainGrey, rainFactor * 0.65f, sunColor);
        }

        // 6. Warm incandescent torch / lantern block light (2650K rich amber-gold)
        torchColor[0] = 1.00f;
        torchColor[1] = 0.64f;
        torchColor[2] = 0.25f;
    }
}
