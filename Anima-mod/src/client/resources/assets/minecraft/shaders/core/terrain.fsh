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
        // Ultra-lightweight animated dual-layer ripples + Fresnel sky sheen + wave crest shimmer
        float t = GameTime * 1400.0;
        vec2 wp = vWaterWorldPos.xz;
        float r1 = sin(wp.x * 3.4 + wp.y * 2.2 + t * 2.8) * cos(wp.x * 2.1 - wp.y * 3.6 + t * 2.1);
        float r2 = sin(wp.x * 6.2 - wp.y * 5.4 - t * 3.5);
        float ripple = r1 * 0.65 + r2 * 0.35;

        // View-angle Schlick Fresnel reflection tint toward sky/horizon FogColor
        vec3 viewDir = normalize(vWaterRelPos);
        float cosTheta = clamp(abs(viewDir.y) + ripple * 0.06, 0.04, 1.0);
        float m = 1.0 - cosTheta;
        float fresnel = 0.06 + 0.68 * (m * m * m * m);

        // Crystal turquoise-teal water body polish + wave-crest highlight
        vec3 skyTint = FogColor.rgb * 1.08;
        float crestHighlight = smoothstep(0.015, 0.055, vWaveHeight + ripple * 0.025) * 0.22;
        float shimmer = smoothstep(0.55, 0.96, ripple) * 0.16;

        color.rgb = mix(color.rgb * vec3(0.88, 1.04, 1.08), skyTint, fresnel * 0.68);
        color.rgb += (skyTint * 0.65 + vec3(0.35)) * (crestHighlight + shimmer);
        // Slightly clearer water at steep viewing angles, more reflective/opaque at grazing angles
        color.a = clamp(mix(color.a * 0.84, 0.94, fresnel), 0.45, 0.96);
    }

    color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
