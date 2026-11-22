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

out float v_AnimaActive;
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
    const float H = 0.3926990817; // 2*PI / 16.0 -> seamless across all chunks & regions

    float animaOn = (fract(u_EnvironmentFog.y) > 0.25) ? 1.0 : 0.0;
    vec2 phaseCoord = _vert_position.xz + drawTrans.xz;
    float fy = fract(_vert_position.y);
    float wave = 0.0;
    float isWaterCandidate = 0.0;

    // 1. Zero-Cost Wind-Swaying Foliage & Sunlit Subsurface Glow (Leaves, Tall Grass, Flowers)
    vec3 sssTint = vec3(1.0);
#ifdef ALPHA_CUTOUT
    if (animaOn > 0.5) {
        float skyL = _vert_tex_light_coord.y;
        // Gentle organic wind sway on outdoor cutout foliage (leaves, grass, flowers)
        if (skyL > 0.35 && _vert_color.g > _vert_color.b * 0.92) {
            float gust = sin(phaseCoord.x * (2.0 * H) + phaseCoord.y * (1.0 * H) + animaTime * 1.9)
                       + cos(phaseCoord.x * (3.0 * H) - phaseCoord.y * (2.0 * H) + animaTime * 1.3) * 0.5;
            float swayMask = smoothstep(0.05, 0.90, fy);
            // Leaves (high green/dark) sway gently all over; ground plants anchor at bottom (fy=0)
            float amp = 0.026 * (0.35 + 0.65 * swayMask);
            position.x += gust * amp;
            position.z += cos(phaseCoord.x * (1.0 * H) + phaseCoord.y * (3.0 * H) + animaTime * 1.6) * amp * 0.7;
        }
        // Subtle Complementary-style sunlit foliage subsurface glow
        if (skyL > 0.80 && _vert_color.g > _vert_color.r * 1.05) {
            sssTint = vec3(1.09, 1.12, 0.96);
        }
    }
#else
    // 2. Seamless Animated Wavy Water (only on translucent water pass!)
    isWaterCandidate = (animaOn > 0.5 && fy > 0.12 && fy < 0.96 && _vert_color.b > _vert_color.r * 1.12) ? 1.0 : 0.0;
    if (isWaterCandidate > 0.5) {
        float surfaceMask = smoothstep(0.65, 0.86, fy);
        float w1 = sin(phaseCoord.x * (3.0 * H) + phaseCoord.y * (2.0 * H) + animaTime * 2.4) * 0.042;
        float w2 = cos(phaseCoord.x * (2.0 * H) - phaseCoord.y * (4.0 * H) + animaTime * 1.8) * 0.026;
        float w3 = sin((phaseCoord.x + phaseCoord.y) * (5.0 * H) - animaTime * 3.1) * 0.011;
        wave = (w1 + w2 + w3) * surfaceMask;
        position.y += wave;
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
    v_AnimaActive = animaOn;
    v_AnimaWater = isWaterCandidate;
    v_WaterRelPos = position;
    v_WaterPhaseCoord = phaseCoord;
    v_WaveHeight = wave;
    v_WaterTime = animaTime;
}
