#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
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

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;

    if (vAnimaWater > 0.5) {
        // Bold, crisp animated dual-layer ripples + Fresnel sky reflections + wave-crest sparkles
        float t = GameTime * 3600.0;
        vec2 wp = vWaterWorldPos.xz;
        float r1 = sin(wp.x * 3.2 + wp.y * 2.1 + t * 3.1) * cos(wp.x * 2.0 - wp.y * 3.5 + t * 2.4);
        float r2 = sin(wp.x * 5.8 - wp.y * 5.1 - t * 3.8);
        float r3 = cos(wp.x * 9.0 + wp.y * 7.5 + t * 4.6) * 0.35;
        float ripple = r1 * 0.55 + r2 * 0.30 + r3 * 0.15;

        // Visible Fresnel sky/horizon reflection even at normal 45-degree gameplay angles
        vec3 viewDir = normalize(vWaterRelPos);
        float cosTheta = clamp(abs(viewDir.y) + ripple * 0.12, 0.05, 1.0);
        float fresnel = 0.16 + 0.74 * pow(1.0 - cosTheta, 2.2);

        // Crystal turquoise-sapphire water tint + sky reflection + sunlit wave crests & sparkles
        vec3 waterBase = color.rgb * vec3(0.76, 1.12, 1.16);
        vec3 skyRefl = FogColor.rgb * 1.15;
        float crest = smoothstep(0.01, 0.07, vWaveHeight + ripple * 0.03) * 0.32;
        float sparkle = smoothstep(0.50, 0.92, ripple) * 0.28;

        color.rgb = mix(waterBase, skyRefl, fresnel * 0.72);
        color.rgb += (skyRefl * 0.65 + vec3(0.35)) * (crest + sparkle);
        color.a = clamp(mix(color.a * 0.78, 0.95, fresnel), 0.42, 0.96);
    }

    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
