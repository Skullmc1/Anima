#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in float vAnimaActive;
in float vAnimaWater;
in vec3 vWaterWorldPos;
in vec3 vWaterRelPos;
in vec3 vBlockLocalPos;
in float vWaveHeight;

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

    float mipLevelLow = floor(mipLevelExact);
    float mipLevelHigh = mipLevelLow + 1.0;
    float mipBlend = fract(mipLevelExact);

    const vec2 offsets[4] = vec2[](
    vec2(0.125, 0.375),
    vec2(-0.125, -0.375),
    vec2(0.375, -0.125),
    vec2(-0.375, 0.125)
    );

    vec4 rgssColorLow = vec4(0.0);
    vec4 rgssColorHigh = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + offsets[i] * pixelSize;
        rgssColorLow += textureLod(source, sampleUV, mipLevelLow);
        rgssColorHigh += textureLod(source, sampleUV, mipLevelHigh);
    }
    rgssColorLow *= 0.25;
    rgssColorHigh *= 0.25;

    vec4 rgssColor = mix(rgssColorLow, rgssColorHigh, mipBlend);
    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);

    return mix(nearestColor, rgssColor, blendFactor);
}

vec3 animaFilmicGrade(vec3 c) {
    vec3 x = max(vec3(0.0), c * 1.12);
    vec3 mapped = clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
    vec3 graded = mix(c, mapped, 0.42);
    float lum = dot(graded, vec3(0.2126, 0.7152, 0.0722));
    return clamp(mix(vec3(lum), graded, 1.12), 0.0, 1.0);
}

vec3 animaBorderlandsGrade(vec3 c, vec3 relPos, vec3 localPos) {
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 sat = clamp(mix(vec3(lum), c, 1.16), 0.0, 1.0);

    float bands = 4.5;
    float scaled = lum * bands;
    float baseBand = floor(scaled);
    float frac = smoothstep(0.36, 0.64, fract(scaled));
    float celLum = clamp((baseBand + frac + 0.18) / (bands + 0.18), 0.06, 1.0);
    vec3 cel = clamp(sat * (celLum / max(0.04, lum)), 0.0, 1.0);

    float dist = length(relPos);
    vec3 fw = fwidth(localPos);
    vec3 fp = fract(localPos);
    vec3 distToBorder = min(fp, 1.0 - fp);

    float pxX = (fw.x > 0.0004) ? (distToBorder.x / fw.x) : 99.0;
    float pxY = (fw.y > 0.0004) ? (distToBorder.y / fw.y) : 99.0;
    float pxZ = (fw.z > 0.0004) ? (distToBorder.z / fw.z) : 99.0;
    float minPxEdge = min(pxX, min(pxY, pxZ));

    float distFade = 1.0 - smoothstep(18.0, 48.0, dist);
    float blockOutline = (1.0 - smoothstep(0.55, 1.45, minPxEdge)) * 0.85 * distFade;
    float texInk = smoothstep(0.14, 0.34, fwidth(lum)) * 0.42 * distFade;
    float ink = clamp(max(blockOutline, texInk), 0.0, 0.88);

    vec3 inkColor = vec3(0.06, 0.05, 0.08);
    return mix(cel, inkColor, ink);
}

vec3 complementaryWaterNormal(vec2 wp, float t) {
    const float H = 0.3926990817;
    float b1 = cos(wp.x * (2.0 * H) + wp.y * (1.0 * H) - t * 0.9);
    float b2 = sin(wp.x * (1.0 * H) - wp.y * (3.0 * H) - t * 0.7);
    float m1 = cos(wp.x * (6.0 * H) + wp.y * (4.0 * H) + t * 2.2);
    float m2 = sin(wp.x * (4.0 * H) - wp.y * (7.0 * H) + t * 1.8);
    float s1 = cos(wp.x * (14.0 * H) - wp.y * (11.0 * H) - t * 3.6);
    float s2 = sin(wp.x * (11.0 * H) + wp.y * (15.0 * H) - t * 3.1);

    float nx = b1 * 0.055 + m1 * 0.075 + s1 * 0.045;
    float nz = b2 * 0.055 + m2 * 0.075 + s2 * 0.045;
    return normalize(vec3(nx, 1.0, nz));
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;

    if (vAnimaWater > 0.5) {
        vec3 N = complementaryWaterNormal(vWaterWorldPos.xz, GameTime * 3600.0);
        vec3 viewDir = normalize(vWaterRelPos);
        vec3 R = reflect(viewDir, N);

        float ndotv = clamp(dot(-viewDir, N), 0.02, 1.0);
        float fresnel = 0.06 + 0.82 * pow(1.0 - ndotv, 3.6);

        vec3 horizonCol = FogColor.rgb * 1.12;
        vec3 zenithCol = FogColor.rgb * vec3(0.42, 0.64, 1.12);
        vec3 skyRefl = mix(horizonCol, zenithCol, clamp(R.y * 1.35, 0.0, 1.0));

        vec3 sunDir = normalize(vec3(0.45, 0.52, 0.72));
        float specSharp = pow(max(0.0, dot(R, sunDir)), 42.0) * 0.95;
        float specBroad = pow(max(0.0, dot(R, normalize(vec3(-0.35, 0.65, 0.67)))), 18.0) * 0.25;
        float skyBright = clamp(dot(FogColor.rgb, vec3(0.333)) * 1.8, 0.08, 1.0);
        vec3 specCol = (horizonCol * 0.65 + vec3(0.35)) * (specSharp + specBroad) * skyBright;

        vec3 waterBody = pow(max(color.rgb, vec3(0.0)), vec3(1.18)) * vec3(0.50, 1.02, 1.16);

        color.rgb = mix(waterBody, skyRefl, fresnel * 0.84) + specCol;
        color.a = clamp(mix(0.46, 0.92, fresnel), 0.42, 0.94);
    }

    if (vAnimaActive > 1.5) {
        color.rgb = animaBorderlandsGrade(color.rgb, vWaterRelPos, vBlockLocalPos);
    } else if (vAnimaActive > 0.5) {
        color.rgb = animaFilmicGrade(color.rgb);
    }

    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
