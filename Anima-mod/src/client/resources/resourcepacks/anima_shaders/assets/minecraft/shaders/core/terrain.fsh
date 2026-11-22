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

vec3 animaBorderlandsGrade(vec3 c, vec3 relPos) {
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 sat = clamp(mix(vec3(lum), c, 1.42), 0.0, 1.0);

    float bands = 4.5;
    float scaled = lum * bands;
    float baseBand = floor(scaled);
    float frac = smoothstep(0.38, 0.62, fract(scaled));
    float celLum = clamp((baseBand + frac + 0.15) / (bands + 0.15), 0.05, 1.0);
    vec3 cel = clamp(sat * (celLum / max(0.04, lum)), 0.0, 1.0);
    cel = mix(cel, cel * cel * 1.18, 0.25);

    float dist = length(relPos);
    vec3 dPosX = dFdx(relPos);
    vec3 dPosY = dFdy(relPos);
    vec3 faceN = normalize(cross(dPosX, dPosY) + vec3(1e-6));
    float normalEdge = length(fwidth(faceN));
    float depthEdge = fwidth(dist) / max(1.0, dist * 0.085);
    float lumEdge = fwidth(lum) * 2.2;

    float distFade = 1.0 - smoothstep(24.0, 68.0, dist);
    float outline = smoothstep(0.55, 1.15, normalEdge * 1.45 + depthEdge * 0.65 + lumEdge * 0.55) * distFade;

    vec3 inkColor = vec3(0.06, 0.05, 0.08);
    return mix(cel, inkColor, clamp(outline * 0.88, 0.0, 0.88));
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;

    if (vAnimaWater > 0.5) {
        const float H = 0.3926990817;
        float t = GameTime * 3600.0;
        vec2 wp = vWaterWorldPos.xz;
        float r1 = sin(wp.x * (6.0 * H) + wp.y * (4.0 * H) + t * 2.6) * cos(wp.x * (4.0 * H) - wp.y * (7.0 * H) + t * 2.0);
        float r2 = sin(wp.x * (12.0 * H) - wp.y * (10.0 * H) - t * 3.2);
        float ripple = r1 * 0.65 + r2 * 0.35;

        vec3 viewDir = normalize(vWaterRelPos);
        float cosTheta = clamp(abs(viewDir.y) + ripple * 0.07, 0.04, 1.0);
        float fresnel = 0.08 + 0.78 * pow(1.0 - cosTheta, 3.0);

        vec3 waterBody = color.rgb * vec3(0.45, 0.96, 1.12);
        vec3 skyRefl = FogColor.rgb * 1.06;

        float spec = pow(max(0.0, ripple * 0.5 + 0.5), 7.0) * 0.32;
        float crest = smoothstep(0.025, 0.068, vWaveHeight) * 0.14;

        color.rgb = mix(waterBody, skyRefl, fresnel * 0.78);
        color.rgb += (skyRefl * 0.75 + vec3(0.25)) * (spec + crest);
        color.a = clamp(mix(0.50, 0.90, fresnel), 0.45, 0.92);
    }

    if (vAnimaActive > 1.5) {
        color.rgb = animaBorderlandsGrade(color.rgb, vWaterRelPos);
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
