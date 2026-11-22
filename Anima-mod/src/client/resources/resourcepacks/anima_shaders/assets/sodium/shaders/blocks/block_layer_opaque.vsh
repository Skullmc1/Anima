#version 330 core

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_vertex.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

#ifdef USE_FOG
out vec2 v_FragDistance;
out float fadeFactor;
#endif

out float v_AnimaWater;
out vec3 v_WaterRelPos;
out vec2 v_WaterPhaseCoord;
out float v_WaveHeight;
out float v_WaterTime;

uniform isamplerBuffer u_SectionTimeInfo;

#ifdef VULKAN
layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

uniform sampler2D u_LightTex; // The light map texture sampler

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into camera-relative world space
    vec3 drawTrans = _get_draw_translation(_draw_id);
    vec3 translation = u_RegionOffset + drawTrans;
    vec3 position = _vert_position + translation;

    // Real-time seconds from Sodium's u_CurrentTime (milliseconds)
    float animaTime = float(u_CurrentTime & 1048575) * 0.001;

    // 1. Animated Wavy Water in Sodium (only when Anima is ON: fract(u_EnvironmentFog.y) > 0.25)
    float animaWaterOn = (fract(u_EnvironmentFog.y) > 0.25) ? 1.0 : 0.0;
    float fy = fract(_vert_position.y);
    // Water top surfaces have fractional height (still water = 8/9 = 0.8888) and cool blue/teal biome tint
    float isWaterCandidate = (animaWaterOn > 0.5 && fy > 0.06 && fy < 0.97 && _vert_color.b > _vert_color.r * 1.12) ? 1.0 : 0.0;
    float wave = 0.0;

    // Region-local continuous coordinate (seamless across all chunks in region, independent of camera motion!)
    vec2 waterCoord = (_vert_position.xz + drawTrans.xz);

    if (isWaterCandidate > 0.5) {
        float w1 = sin(waterCoord.x * 1.15 + waterCoord.y * 0.78 + animaTime * 2.6) * 0.048;
        float w2 = cos(waterCoord.x * 0.72 - waterCoord.y * 1.42 + animaTime * 2.0) * 0.032;
        float w3 = sin((waterCoord.x + waterCoord.y) * 2.25 - animaTime * 3.4) * 0.015;
        wave = w1 + w2 + w3;
        position.y += wave;
    }

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    fadeFactor = (chunkFade < 0) ? 1.0 : fade;
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // 2. Zero-Lag Per-Frame GPU Dynamic Handheld Lighting in Sodium (ZERO chunk rebuilds, full FPS!)
    // Smooth wall-clock faded luminance (0..15) is encoded in fract(u_RenderFog.y).
    vec2 lightCoord = _vert_tex_light_coord;
    float heldLum = clamp(fract(u_RenderFog.y) * (16.0 / 0.96), 0.0, 15.0);
    if (heldLum > 0.05) {
        float dist = length(position);
        float dynBlock = max(0.0, heldLum - dist * 1.02);
        float dynCoord = clamp((dynBlock * 16.0 + 8.0) / 256.0, 0.03125, 0.96875);
        lightCoord.x = max(lightCoord.x, dynCoord);
    }

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    v_Color = _vert_color * texture(u_LightTex, lightCoord);
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
    v_AnimaWater = isWaterCandidate;
    v_WaterRelPos = position;
    v_WaterPhaseCoord = waterCoord;
    v_WaveHeight = wave;
    v_WaterTime = animaTime;
}
