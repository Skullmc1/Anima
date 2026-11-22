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

    // Water vertices are tagged with Color.a = 254/255 (~0.99608) by Anima's FluidRendererMixin
    float isWater = (Color.a < 0.998 && Color.a > 0.990) ? 1.0 : 0.0;
    vec4 cleanColor = Color;
    float wave = 0.0;

    if (isWater > 0.5) {
        cleanColor.a = 1.0;
        // Multi-directional animated surface waves (only displace top water vertices, keeping block bottoms sealed)
        float t = GameTime * 1400.0;
        float w1 = sin(worldPos.x * 1.10 + worldPos.z * 0.72 + t * 2.1) * 0.030;
        float w2 = cos(worldPos.x * 0.68 - worldPos.z * 1.38 + t * 1.65) * 0.022;
        float w3 = sin((worldPos.x + worldPos.z) * 2.15 - t * 2.9) * 0.010;
        wave = w1 + w2 + w3;
        // Only wave vertices near the top surface of the water block (fract(y) > 0.15)
        float topMask = smoothstep(0.10, 0.35, fract(Position.y));
        pos.y += wave * topMask;
    }

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = cleanColor * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    vAnimaWater = isWater;
    vWaterWorldPos = worldPos;
    vWaterRelPos = pos;
    vWaveHeight = wave;
}
