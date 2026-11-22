#version 330 core

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_material.glsl>

in vec4 v_Color;
in vec2 v_TexCoord;
in vec2 v_FragDistance;
in float fadeFactor;

in float v_AnimaWater;
in vec3 v_WaterRelPos;
in vec2 v_WaterPhaseCoord;
in float v_WaveHeight;
in float v_WaterTime;

uniform sampler2D u_BlockTex;

out vec4 fragColor;

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;

    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);

    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);

    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);

    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize * 1.0;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);

    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColor += textureLod(source, sampleUV, mipLevelExact);
    }
    rgssColor *= 0.25;

    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

// Complementary Reimagined-inspired 3-scale scrolling animated water normal
vec3 complementaryWaterNormal(vec2 wp, float t) {
    const float H = 0.3926990817;
    float b1 = cos(wp.x * (2.0 * H) + wp.y * (1.0 * H) - t * 1.1);
    float b2 = sin(wp.x * (1.0 * H) - wp.y * (3.0 * H) - t * 0.9);
    float m1 = cos(wp.x * (6.0 * H) + wp.y * (4.0 * H) + t * 2.5);
    float m2 = sin(wp.x * (4.0 * H) - wp.y * (7.0 * H) + t * 2.1);
    float s1 = cos(wp.x * (14.0 * H) - wp.y * (11.0 * H) - t * 4.0);
    float s2 = sin(wp.x * (11.0 * H) + wp.y * (15.0 * H) - t * 3.5);

    float nx = b1 * 0.065 + m1 * 0.085 + s1 * 0.050;
    float nz = b2 * 0.065 + m2 * 0.085 + s2 * 0.050;
    return normalize(vec3(nx, 1.0, nz));
}

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // Complementary Reimagined-Style Animated Water (outside #else so Sodium translucent pass ALWAYS runs it!)
    if (v_AnimaWater > 0.5 && color.a < 0.98 && color.a > 0.12) {
        vec3 N = complementaryWaterNormal(v_WaterPhaseCoord, v_WaterTime);
        vec3 viewDir = normalize(v_WaterRelPos);
        vec3 R = reflect(viewDir, N);

        float ndotv = clamp(dot(-viewDir, N), 0.02, 1.0);
        float fresnel = 0.08 + 0.82 * pow(1.0 - ndotv, 3.2);

        vec3 horizonCol = u_FogColor.rgb * 1.14;
        vec3 zenithCol = u_FogColor.rgb * vec3(0.42, 0.66, 1.14);
        vec3 skyRefl = mix(horizonCol, zenithCol, clamp(R.y * 1.35, 0.0, 1.0));

        vec3 sunDir = normalize(vec3(0.45, 0.52, 0.72));
        float specSharp = pow(max(0.0, dot(R, sunDir)), 36.0) * 1.10;
        float specBroad = pow(max(0.0, dot(R, normalize(vec3(-0.35, 0.65, 0.67)))), 16.0) * 0.30;
        float skyBright = clamp(dot(u_FogColor.rgb, vec3(0.333)) * 1.8, 0.08, 1.0);
        vec3 specCol = (horizonCol * 0.65 + vec3(0.35)) * (specSharp + specBroad) * skyBright;

        vec3 waterBody = pow(max(color.rgb, vec3(0.0)), vec3(1.16)) * vec3(0.48, 1.02, 1.16);

        color.rgb = mix(waterBody, skyRefl, fresnel * 0.84) + specCol;
        color.a = clamp(mix(0.46, 0.92, fresnel), 0.42, 0.94);
    }

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);
}
