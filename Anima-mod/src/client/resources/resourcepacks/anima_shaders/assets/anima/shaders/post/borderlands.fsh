#version 330

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// 4 symmetric axes (8 taps) for crisp omnidirectional outlines
const vec2 OFFSETS[4] = vec2[4](
    vec2( 1.0,  0.0),
    vec2( 0.0,  1.0),
    vec2( 1.0,  1.0),
    vec2(-1.0,  1.0)
);

void main() {
    vec2 res = max(InSize, vec2(1.0));
    vec2 texel = 1.0 / res;
    // Slight thickness scaling so outlines stay crisp on both 720p and 1440p/4K displays
    float radius = clamp(res.y / 720.0, 1.0, 2.0);

    vec3 col = texture(InSampler, texCoord).rgb;
    // Minecraft 26.2 uses Reverse-Z depth: 0.0 = sky/far, 1.0 = near, d = near / Z
    float d0 = texture(InDepthSampler, texCoord).r;
    bool isSky = (d0 <= 0.000001);
    float lum = luminance(col);

    // 1. Clean Borderlands Cel-Shading (subtle 1.04x saturation + smooth 32% 7-band comic tone so textures stay crisp & never blotchy!)
    vec3 sat = clamp(mix(vec3(lum), col, 1.04), 0.0, 1.0);
    vec3 finalCol = sat;
    if (!isSky) {
        float bands = 7.0;
        float scaled = lum * bands;
        float baseBand = floor(scaled);
        float frac = smoothstep(0.18, 0.82, fract(scaled));
        float celLum = clamp((baseBand + frac + 0.12) / (bands + 0.12), 0.04, 1.0);
        vec3 celCol = clamp(sat * (celLum / max(0.035, lum)), 0.0, 1.0);
        finalCol = mix(sat, celCol, 0.32);
    }

    // 2. Reverse-Z Linear-Plane Curvature & Silhouette Outlines
    // Because d = near/Z is EXACTLY linear in screen space on any flat plane,
    // 2*d0 - (da + db) == 0 on flat ground, walls, and water (ZERO per-block grid!)
    // while spiking sharply on cliffs, steps, trees, grass, dead bushes, sugar cane, and mobs!
    float maxCurv = 0.0;
    float maxStep = 0.0;
    float maxD = d0;
    for (int i = 0; i < 4; i++) {
        vec2 off = texel * OFFSETS[i] * radius;
        float da = texture(InDepthSampler, texCoord + off).r;
        float db = texture(InDepthSampler, texCoord - off).r;
        float refD = max(d0, max(da, db));
        maxD = max(maxD, refD);
        if (refD > 0.00005) {
            float curv = abs(2.0 * d0 - (da + db)) / refD;
            float stepA = abs(da - d0) / max(max(da, d0), 0.00005);
            float stepB = abs(db - d0) / max(max(db, d0), 0.00005);
            maxCurv = max(maxCurv, curv);
            maxStep = max(maxStep, max(stepA, stepB));
        }
    }

    // Smooth horizon distance fade (d ~ 0.00033 is ~150 blocks, d ~ 0.00014 is ~350 blocks)
    float distFade = smoothstep(0.00012, 0.00042, maxD);
    float depthInk = max(
        smoothstep(0.055, 0.15, maxCurv),
        smoothstep(0.16, 0.34, maxStep)
    ) * distFade;

    // 3. Crisp Comic Outlines on Clouds & High-Contrast Sky Silhouettes
    float cloudInk = 0.0;
    if (isSky) {
        vec2 cStep = texel * radius;
        float lL = luminance(texture(InSampler, texCoord - vec2(cStep.x, 0.0)).rgb);
        float lR = luminance(texture(InSampler, texCoord + vec2(cStep.x, 0.0)).rgb);
        float lD = luminance(texture(InSampler, texCoord - vec2(0.0, cStep.y)).rgb);
        float lU = luminance(texture(InSampler, texCoord + vec2(0.0, cStep.y)).rgb);
        float grad = abs(lR - lL) + abs(lU - lD);
        cloudInk = smoothstep(0.065, 0.16, grad) * 0.72;
    }

    float ink = clamp(max(depthInk, cloudInk), 0.0, 0.92);
    vec3 inkColor = vec3(0.04, 0.035, 0.055);
    finalCol = mix(finalCol, inkColor, ink);

    fragColor = vec4(finalCol, 1.0);
}
