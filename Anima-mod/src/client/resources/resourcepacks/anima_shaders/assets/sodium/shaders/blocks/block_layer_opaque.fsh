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

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#else
    // Translucent water enhancement in Sodium (only triggers on translucent water surfaces!)
    if (v_AnimaWater > 0.5 && color.a < 0.98 && color.a > 0.12) {
        float t = v_WaterTime;
        vec2 wp = v_WaterPhaseCoord;
        float r1 = sin(wp.x * 3.2 + wp.y * 2.1 + t * 3.1) * cos(wp.x * 2.0 - wp.y * 3.5 + t * 2.4);
        float r2 = sin(wp.x * 5.8 - wp.y * 5.1 - t * 3.8);
        float r3 = cos(wp.x * 9.0 + wp.y * 7.5 + t * 4.6) * 0.35;
        float ripple = r1 * 0.55 + r2 * 0.30 + r3 * 0.15;

        vec3 viewDir = normalize(v_WaterRelPos);
        float cosTheta = clamp(abs(viewDir.y) + ripple * 0.12, 0.05, 1.0);
        float fresnel = 0.16 + 0.74 * pow(1.0 - cosTheta, 2.2);

        vec3 waterBase = color.rgb * vec3(0.76, 1.12, 1.16);
        vec3 skyRefl = u_FogColor.rgb * 1.15;
        float crest = smoothstep(0.01, 0.07, v_WaveHeight + ripple * 0.03) * 0.32;
        float sparkle = smoothstep(0.50, 0.92, ripple) * 0.28;

        color.rgb = mix(waterBase, skyRefl, fresnel * 0.72);
        color.rgb += (skyRefl * 0.65 + vec3(0.35)) * (crest + sparkle);
        color.a = clamp(mix(color.a * 0.78, 0.95, fresnel), 0.42, 0.96);
    }
#endif

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);
}
