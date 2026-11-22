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

uniform sampler2D u_LightTex;

uvec3 _get_relative_chunk_coord(uint pos) {
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 drawTrans = _get_draw_translation(_draw_id);
    vec3 translation = u_RegionOffset + drawTrans;
    vec3 position = _vert_position + translation;

    float animaTime = float(u_CurrentTime & 1048575) * 0.001;
    const float H = 0.3926990817; // 2*PI / 16.0 -> seamless across all chunks & Sodium regions

    float modeFrac = fract(u_EnvironmentFog.y);
    float animaOn = (modeFrac > 0.15) ? 1.0 : 0.0;
    vec2 phaseCoord = _vert_position.xz + drawTrans.xz;
    float fy = fract(_vert_position.y);
    float wave = 0.0;

    // 1. Seamless Complementary-Style Animated Wavy Water (NOT inside #else so Sodium translucent pass ALWAYS runs it!)
    float isWaterCandidate = (animaOn > 0.5 && fy > 0.12 && fy < 0.96 && _vert_color.b > _vert_color.r * 1.12) ? 1.0 : 0.0;
    if (isWaterCandidate > 0.5) {
        float surfaceMask = smoothstep(0.65, 0.86, fy);
        float w1 = sin(phaseCoord.x * (3.0 * H) + phaseCoord.y * (2.0 * H) + animaTime * 2.4) * 0.048;
        float w2 = cos(phaseCoord.x * (2.0 * H) - phaseCoord.y * (4.0 * H) + animaTime * 1.8) * 0.030;
        float w3 = sin((phaseCoord.x + phaseCoord.y) * (5.0 * H) - animaTime * 3.1) * 0.014;
        wave = (w1 + w2 + w3) * surfaceMask;
        position.y += wave;
    }

    // 2. Zero-Cost Wind-Swaying Foliage & Sunlit Subsurface Glow (Leaves, Tall Grass, Flowers)
    vec3 sssTint = vec3(1.0);
#ifdef ALPHA_CUTOUT
    if (animaOn > 0.5 && isWaterCandidate < 0.5) {
        float skyL = _vert_tex_light_coord.y;
        if (skyL > 0.35 && _vert_color.g > _vert_color.b * 0.92 && _vert_color.g > _vert_color.r * 1.02) {
            float gust = sin(phaseCoord.x * (2.0 * H) + phaseCoord.y * (1.0 * H) + animaTime * 1.9)
                       + cos(phaseCoord.x * (3.0 * H) - phaseCoord.y * (2.0 * H) + animaTime * 1.3) * 0.5;
            float swayMask = smoothstep(0.05, 0.90, fy);
            float amp = 0.025 * (0.35 + 0.65 * swayMask);
            position.x += gust * amp;
            position.z += cos(phaseCoord.x * (1.0 * H) + phaseCoord.y * (3.0 * H) + animaTime * 1.6) * amp * 0.7;
        }
        if (skyL > 0.80 && _vert_color.g > _vert_color.r * 1.05) {
            sssTint = vec3(1.08, 1.11, 0.96);
        }
    }
#endif

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    fadeFactor = (chunkFade < 0) ? 1.0 : fade;
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // 3. Zero-Lag Per-Frame GPU Dynamic Handheld Lighting (encoded in fract(u_RenderFog.y))
    vec2 lightCoord = _vert_tex_light_coord;
    float heldLum = clamp(fract(u_RenderFog.y) * (16.0 / 0.96), 0.0, 15.0);
    if (heldLum > 0.05) {
        float dist = length(position);
        float dynBlock = max(0.0, heldLum - dist * 1.02);
        float dynCoord = clamp((dynBlock * 16.0 + 8.0) / 256.0, 0.03125, 0.96875);
        lightCoord.x = max(lightCoord.x, dynCoord);
    }

    vec4 lit = _vert_color * texture(u_LightTex, lightCoord);
    lit.rgb *= sssTint;
    v_Color = lit;
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
    v_AnimaWater = isWaterCandidate;
    v_WaterRelPos = position;
    v_WaterPhaseCoord = phaseCoord;
    v_WaveHeight = wave;
    v_WaterTime = animaTime;
}
