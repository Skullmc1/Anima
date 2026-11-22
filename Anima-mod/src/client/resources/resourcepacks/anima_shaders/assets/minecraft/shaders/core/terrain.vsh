#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out float vAnimaActive;
out float vAnimaWater;
out vec3 vWaterWorldPos;
out vec3 vWaterRelPos;
out float vWaveHeight;

void main() {
    vec3 worldPos = Position + vec3(ChunkPosition);
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;

    const float H = 0.3926990817;
    float animaOn = (fract(FogEnvironmentalEnd) > 0.25) ? 1.0 : 0.0;
    float isWater = (animaOn > 0.5 && Color.a < 0.998 && Color.a > 0.990) ? 1.0 : 0.0;
    vec4 cleanColor = Color;
    float wave = 0.0;

    if (Color.a < 0.998 && Color.a > 0.990) {
        cleanColor.a = 1.0;
    }

    vec3 sssTint = vec3(1.0);
    float t = GameTime * 3600.0;

#ifdef ALPHA_CUTOUT
    if (animaOn > 0.5) {
        float skyL = float(UV2.y) / 240.0;
        if (skyL > 0.35 && cleanColor.g > cleanColor.b * 0.92) {
            float gust = sin(worldPos.x * (2.0 * H) + worldPos.z * (1.0 * H) + t * 1.9)
                       + cos(worldPos.x * (3.0 * H) - worldPos.z * (2.0 * H) + t * 1.3) * 0.5;
            float swayMask = smoothstep(0.05, 0.90, fract(Position.y));
            float amp = 0.026 * (0.35 + 0.65 * swayMask);
            pos.x += gust * amp;
            pos.z += cos(worldPos.x * (1.0 * H) + worldPos.z * (3.0 * H) + t * 1.6) * amp * 0.7;
        }
        if (skyL > 0.80 && cleanColor.g > cleanColor.r * 1.05) {
            sssTint = vec3(1.09, 1.12, 0.96);
        }
    }
#endif

    if (isWater > 0.5) {
        float fy = fract(Position.y);
        float surfaceMask = smoothstep(0.65, 0.86, fy);
        float w1 = sin(worldPos.x * (3.0 * H) + worldPos.z * (2.0 * H) + t * 2.4) * 0.042;
        float w2 = cos(worldPos.x * (2.0 * H) - worldPos.z * (4.0 * H) + t * 1.8) * 0.026;
        float w3 = sin((worldPos.x + worldPos.z) * (5.0 * H) - t * 3.1) * 0.011;
        wave = (w1 + w2 + w3) * surfaceMask;
        pos.y += wave;
    }

    // Zero-lag per-frame GPU dynamic handheld lighting (encoded in fract(FogRenderDistanceEnd))
    float heldLum = clamp(fract(FogRenderDistanceEnd) * (16.0 / 0.96), 0.0, 15.0);
    ivec2 finalUV2 = UV2;
    if (heldLum > 0.05) {
        float dist = length(pos);
        float dynBlock = max(0.0, heldLum - dist * 1.02);
        int dynUV = int(clamp(dynBlock * 16.0, 0.0, 240.0));
        finalUV2.x = max(finalUV2.x, dynUV);
    }

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vec4 lit = cleanColor * sample_lightmap(Sampler2, finalUV2);
    lit.rgb *= sssTint;
    vertexColor = lit;
    texCoord0 = UV0;
    vAnimaActive = animaOn;
    vAnimaWater = isWater;
    vWaterWorldPos = worldPos;
    vWaterRelPos = pos;
    vWaveHeight = wave;
}
