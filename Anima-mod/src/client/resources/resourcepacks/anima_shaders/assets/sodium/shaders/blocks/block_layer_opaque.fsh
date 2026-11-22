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
in vec3 v_BlockLocalPos;
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

// Fast ACES/Complementary-inspired filmic tonemap + subtle vibrancy (Lush mode)
vec3 animaFilmicGrade(vec3 c) {
    vec3 x = max(vec3(0.0), c * 1.12);
    vec3 mapped = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    vec3 graded = mix(c, mapped, 0.42);
    float lum = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(vec3(lum), graded, 1.12), 0.0, 1.0);
}

// Borderlands Cel-Shaded Comic Pipeline: crisp 3D block-edge ink outlines + stepped cel bands + clean saturation
vec3 animaBorderlandsGrade(vec3 c, vec3 relPos, vec3 localPos) {
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    // Clean, balanced Borderlands saturation (1.16x — punchy without neon blowout!)
    vec3 sat = clamp(mix(vec3(lum), c, 1.16), 0.0, 1.0);

    // 4.5 crisp cel-shading luminance bands
    float bands = 4.5;
    float scaled = lum * bands;
    float baseBand = floor(scaled);
    float frac = smoothstep(0.36, 0.64, fract(scaled));
    float celLum = clamp((baseBand + frac + 0.18) / (bands + 0.18), 0.06, 1.0);
    vec3 cel = clamp(sat * (celLum / max(0.04, lum)), 0.0, 1.0);

    // True 3D Block-Edge Ink Outlines (measures exact screen-pixel distance to block borders!)
    float dist = length(relPos);
    vec3 fw = fwidth(localPos);
    vec3 fp = fract(localPos);
    vec3 distToBorder = min(fp, 1.0 - fp);

    // Only check axes that actually vary across this face (ignore the constant face-normal axis!)
    float pxX = (fw.x > 0.0004) ? (distToBorder.x / fw.x) : 99.0;
    float pxY = (fw.y > 0.0004) ? (distToBorder.y / fw.y) : 99.0;
    float pxZ = (fw.z > 0.0004) ? (distToBorder.z / fw.z) : 99.0;
    float minPxEdge = min(pxX, min(pxY, pxZ));

    // Crisp ~1.35px dark comic ink line around block edges + subtle texture contrast ink
    float distFade = 1.0 - smoothstep(18.0, 48.0, dist);
    float blockOutline = (1.0 - smoothstep(0.55, 1.45, minPxEdge)) * 0.85 * distFade;
    float texInk = smoothstep(0.14, 0.34, fwidth(lum)) * 0.42 * distFade;
    float ink = clamp(max(blockOutline, texInk), 0.0, 0.88);

    vec3 inkColor = vec3(0.06, 0.05, 0.08);
    return mix(cel, inkColor, ink);
}

// Complementary Reimagined-inspired 3-scale scrolling animated water normal (big + medium + small waves)
vec3 complementaryWaterNormal(vec2 wp, float t) {
    const float H = 0.3926990817; // 2*PI/16 seamless harmonic
    // 1. Slow rolling big swells
    float b1 = cos(wp.x * (2.0 * H) + wp.y * (1.0 * H) - t * 0.9);
    float b2 = sin(wp.x * (1.0 * H) - wp.y * (3.0 * H) - t * 0.7);
    // 2. Medium drifting wind ripples
    float m1 = cos(wp.x * (6.0 * H) + wp.y * (4.0 * H) + t * 2.2);
    float m2 = sin(wp.x * (4.0 * H) - wp.y * (7.0 * H) + t * 1.8);
    // 3. Fast counter-scrolling small sparkles
    float s1 = cos(wp.x * (14.0 * H) - wp.y * (11.0 * H) - t * 3.6);
    float s2 = sin(wp.x * (11.0 * H) + wp.y * (15.0 * H) - t * 3.1);

    float nx = b1 * 0.055 + m1 * 0.075 + s1 * 0.045;
    float nz = b2 * 0.055 + m2 * 0.075 + s2 * 0.045;
    return normalize(vec3(nx, 1.0, nz));
}

void main() {
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#else
    // Complementary Reimagined-Style Animated Water (true 3-scale scrolling normal, Fresnel sky gradient, dancing sun glints!)
    if (v_AnimaWater > 0.5 && color.a < 0.98 && color.a > 0.12) {
        vec3 N = complementaryWaterNormal(v_WaterPhaseCoord, v_WaterTime);
        vec3 viewDir = normalize(v_WaterRelPos);
        vec3 R = reflect(viewDir, N);

        float ndotv = clamp(dot(-viewDir, N), 0.02, 1.0);
        float fresnel = 0.06 + 0.82 * pow(1.0 - ndotv, 3.6);

        // Complementary-style sky gradient reflection along reflected ray R.y
        vec3 horizonCol = u_FogColor.rgb * 1.12;
        vec3 zenithCol = u_FogColor.rgb * vec3(0.42, 0.64, 1.12);
        vec3 skyRefl = mix(horizonCol, zenithCol, clamp(R.y * 1.35, 0.0, 1.0));

        // Dancing animated specular glints on tilted wave facets (mimics Complementary's sparkling water!)
        vec3 sunDir = normalize(vec3(0.45, 0.52, 0.72));
        float specSharp = pow(max(0.0, dot(R, sunDir)), 42.0) * 0.95;
        float specBroad = pow(max(0.0, dot(R, normalize(vec3(-0.35, 0.65, 0.67)))), 18.0) * 0.25;
        float skyBright = clamp(dot(u_FogColor.rgb, vec3(0.333)) * 1.8, 0.08, 1.0);
        vec3 specCol = (horizonCol * 0.65 + vec3(0.35)) * (specSharp + specBroad) * skyBright;

        // Complementary crystal-clear deep turquoise-sapphire water body
        vec3 waterBody = pow(max(color.rgb, vec3(0.0)), vec3(1.18)) * vec3(0.50, 1.02, 1.16);

        color.rgb = mix(waterBody, skyRefl, fresnel * 0.84) + specCol;
        color.a = clamp(mix(0.46, 0.92, fresnel), 0.42, 0.94);
    }
#endif

    if (v_AnimaActive > 1.5) {
        color.rgb = animaBorderlandsGrade(color.rgb, v_WaterRelPos, v_BlockLocalPos);
    } else if (v_AnimaActive > 0.5) {
        color.rgb = animaFilmicGrade(color.rgb);
    }

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);
}
