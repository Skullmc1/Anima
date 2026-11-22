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
out float vAnimaWater;
out vec3 vWaterWorldPos;
out vec3 vWaterRelPos;
out float vWaveHeight;

void main() {
    vec3 worldPos = Position + vec3(ChunkPosition);
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;

    float animaWaterOn = (fract(FogEnvironmentalEnd) > 0.25) ? 1.0 : 0.0;
    float isWater = (animaWaterOn > 0.5 && Color.a < 0.998 && Color.a > 0.990) ? 1.0 : 0.0;
    vec4 cleanColor = Color;
    float wave = 0.0;

    if (Color.a < 0.998 && Color.a > 0.990) {
        cleanColor.a = 1.0;
    }

    if (isWater > 0.5) {
        const float H = 0.3926990817;
        float t = GameTime * 3600.0;
        float fy = fract(Position.y);
        float surfaceMask = smoothstep(0.65, 0.86, fy);
        float w1 = sin(worldPos.x * (3.0 * H) + worldPos.z * (2.0 * H) + t * 2.5) * 0.044;
        float w2 = cos(worldPos.x * (2.0 * H) - worldPos.z * (4.0 * H) + t * 1.9) * 0.028;
        float w3 = sin((worldPos.x + worldPos.z) * (5.0 * H) - t * 3.2) * 0.012;
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
    vertexColor = cleanColor * sample_lightmap(Sampler2, finalUV2);
    texCoord0 = UV0;
    vAnimaWater = isWater;
    vWaterWorldPos = worldPos;
    vWaterRelPos = pos;
    vWaveHeight = wave;
}
