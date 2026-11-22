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

        // Minecraft solar cycle: 0 = sunrise (east, +X), 6000 = noon (+Y), 12000 = sunset (-X), 18000 = midnight (-Y)
        this.sunAngleRad = (this.dayTimeTicks / 24000f) * (float) (Math.PI * 2.0);
        float elevation = (float) Math.sin(sunAngleRad);
        float horizontal = (float) Math.cos(sunAngleRad);

        // Slight Z tilt for more natural 3D block-face modeling across all axes
        float rawX = horizontal * 0.92f;
        float rawY = elevation;
        float rawZ = -0.34f;
        float len = (float) Math.sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ);
        this.sunDirX = rawX / len;
        this.sunDirY = rawY / len;
        this.sunDirZ = rawZ / len;

        this.dayFactor = ColorMath.smoothstep(-0.18f, 0.25f, elevation);
        this.nightFactor = 1f - ColorMath.smoothstep(-0.22f, 0.08f, elevation);
        // Peaks when sun elevation is around 0.05..0.28 (classic golden hour)
        float riseBell = (float) Math.exp(-Math.pow((elevation - 0.14f) / 0.22f, 2.0));
        this.goldenHourFactor = ColorMath.clamp01(riseBell * (1f - this.rainFactor * 0.7f));

        computePalettes();
    }

    private void computePalettes() {
        // 1. Direct sunlight / moonlight color
        float[] noonSun = {1.00f, 0.96f, 0.88f};
        float[] goldenSun = {1.00f, 0.64f, 0.30f};
        float[] moonLight = {0.42f, 0.56f, 0.84f};

        float[] daySun = new float[3];
        ColorMath.lerpRgb(noonSun, goldenSun, goldenHourFactor, daySun);
        ColorMath.lerpRgb(moonLight, daySun, dayFactor, sunColor);

        // 2. Sky ambient fill color
        float[] noonAmbient = {0.72f, 0.85f, 1.00f};
        float[] goldenAmbient = {0.76f, 0.60f, 0.68f};
        float[] nightAmbient = {0.14f, 0.20f, 0.36f};

        float[] dayAmbient = new float[3];
        ColorMath.lerpRgb(noonAmbient, goldenAmbient, goldenHourFactor, dayAmbient);
        ColorMath.lerpRgb(nightAmbient, dayAmbient, dayFactor, skyAmbientColor);

        // 3. Shadow fill tint (cool sky-bounce makes shadows look atmospheric instead of muddy gray)
        float[] noonShadow = {0.58f, 0.70f, 0.90f};
        float[] goldenShadow = {0.48f, 0.44f, 0.68f};
        float[] nightShadow = {0.10f, 0.15f, 0.28f};

        float[] dayShadow = new float[3];
        ColorMath.lerpRgb(noonShadow, goldenShadow, goldenHourFactor, dayShadow);
        ColorMath.lerpRgb(nightShadow, dayShadow, dayFactor, shadowTintColor);

        // 4. Sky zenith & horizon colors
        float[] noonZenith = {0.18f, 0.44f, 0.86f};
        float[] goldenZenith = {0.19f, 0.25f, 0.54f};
        float[] nightZenith = {0.02f, 0.04f, 0.10f};

        float[] dayZenith = new float[3];
        ColorMath.lerpRgb(noonZenith, goldenZenith, goldenHourFactor, dayZenith);
        ColorMath.lerpRgb(nightZenith, dayZenith, dayFactor, zenithColor);

        float[] noonHorizon = {0.68f, 0.84f, 0.98f};
        float[] goldenHorizon = {0.98f, 0.58f, 0.34f};
        float[] nightHorizon = {0.07f, 0.11f, 0.21f};

        float[] dayHorizon = new float[3];
        ColorMath.lerpRgb(noonHorizon, goldenHorizon, goldenHourFactor, dayHorizon);
        ColorMath.lerpRgb(nightHorizon, dayHorizon, dayFactor, horizonColor);

        // 5. Atmospheric perspective fog color
        float[] noonFog = {0.66f, 0.81f, 0.95f};
        float[] goldenFog = {0.92f, 0.60f, 0.40f};
        float[] nightFog = {0.06f, 0.09f, 0.18f};

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

        // 6. Warm incandescent torch / lantern block light (2700K amber-gold)
        torchColor[0] = 1.00f;
        torchColor[1] = 0.66f;
        torchColor[2] = 0.29f;
    }
}
