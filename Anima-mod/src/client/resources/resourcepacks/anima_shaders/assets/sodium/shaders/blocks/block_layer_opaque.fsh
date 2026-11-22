#version 330 core

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_material.glsl>

in vec4 v_Color;
in vec2 v_TexCoord;
in vec2 v_FragDistance;
in float fadeFactor;

in float v_AnimaActive;
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

// Fast ACES/Complementary-inspired filmic tonemap + subtle vibrancy (zero extra render passes!)
vec3 animaFilmicGrade(vec3 c) {
    vec3 x = max(vec3(0.0), c * 1.12);
    vec3 mapped = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    vec3 graded = mix(c, mapped, 0.42);
    float lum = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(vec3(lum), graded, 1.12), 0.0, 1.0);
}

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#else
    // Crystal-Clear Complementary-Style Water (seamless 16-block harmonic ripples, glassy reflections)
    if (v_AnimaWater > 0.5 && color.a < 0.98 && color.a > 0.12) {
        const float H = 0.3926990817;
        float t = v_WaterTime;
        vec2 wp = v_WaterPhaseCoord;

        // Analytical dual-octave water wave slope for crisp glassy normal highlights (no milky noise!)
        float r1 = sin(wp.x * (6.0 * H) + wp.y * (4.0 * H) + t * 2.6) * cos(wp.x * (4.0 * H) - wp.y * (7.0 * H) + t * 2.0);
        float r2 = sin(wp.x * (12.0 * H) - wp.y * (10.0 * H) - t * 3.2);
        float ripple = r1 * 0.65 + r2 * 0.35;

        vec3 viewDir = normalize(v_WaterRelPos);
        float cosTheta = clamp(abs(viewDir.y) + ripple * 0.07, 0.04, 1.0);
        float fresnel = 0.08 + 0.78 * pow(1.0 - cosTheta, 3.0);

        // Rich crystal turquoise-sapphire water body (shows riverbed clearly at steep angles!)
        vec3 waterBody = color.rgb * vec3(0.45, 0.96, 1.12);
        vec3 skyRefl = u_FogColor.rgb * 1.06;

        // Crisp delicate specular glint only on sharp wave peaks
        float spec = pow(max(0.0, ripple * 0.5 + 0.5), 7.0) * 0.32;
        float crest = smoothstep(0.025, 0.068, v_WaveHeight) * 0.14;

        color.rgb = mix(waterBody, skyRefl, fresnel * 0.78);
        color.rgb += (skyRefl * 0.75 + vec3(0.25)) * (spec + crest);
        // Crystal-clear ~0.52 alpha looking down, smoothly rising to 0.90 reflective sheen at horizon!
        color.a = clamp(mix(0.50, 0.90, fresnel), 0.45, 0.92);
    }
#endif

    if (v_AnimaActive > 0.5) {
        color.rgb = animaFilmicGrade(color.rgb);
    }

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);
}
