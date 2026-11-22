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

float hash21(vec2 p) {
    p = fract(p * vec2(234.34, 435.345));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

const vec2 OFFSETS[4] = vec2[4](
    vec2( 1.0,  0.0),
    vec2( 0.0,  1.0),
    vec2( 1.0,  1.0),
    vec2(-1.0,  1.0)
);

void main() {
    vec2 res = max(InSize, vec2(1.0));
    vec2 px = texCoord * res;
    vec2 texel = 1.0 / res;
    float radius = clamp(res.y / 720.0, 1.0, 2.0);

    // Subtle hand-drawn paper grain & slight organic pencil jitter on UV
    float paperFine = valueNoise(px * 0.45);
    float paperCoarse = valueNoise(px * 0.12 + vec2(17.3, 9.1));
    float paperGrain = paperFine * 0.65 + paperCoarse * 0.35;

    vec2 wobble = vec2(paperCoarse - 0.5, valueNoise(px * 0.12 + vec2(3.7, 29.4)) - 0.5) * texel * 0.9;
    vec3 col = texture(InSampler, texCoord + wobble).rgb;
    // Minecraft 26.2 Reverse-Z depth: 0.0 = sky, 1.0 = near
    float d0 = texture(InDepthSampler, texCoord).r;
    bool isSky = (d0 <= 0.000001);
    float lum = luminance(col);

    // 1. Vibrant Colored-Pencil Pigment + Warm Cream Sketchbook Paper Blend
    vec3 pigment = clamp(mix(vec3(lum), col, 1.24), 0.0, 1.0);
    vec3 creamPaper = vec3(0.965, 0.945, 0.895) * (0.94 + 0.10 * paperGrain);
    float toothMask = smoothstep(0.55, 0.98, paperGrain) * 0.16;
    vec3 sketched = mix(pigment, creamPaper, toothMask + 0.08);

    // 2. Diagonal Pencil Cross-Hatching in Mid-Tones & Shadows (geometry only)
    if (!isSky) {
        float diag1 = sin((px.x + px.y) * 0.95 + (paperCoarse - 0.5) * 2.2);
        float diag2 = sin((px.x - px.y) * 0.95 + (paperFine - 0.5) * 2.0);
        float stroke1 = smoothstep(0.55, 0.92, diag1) * (1.0 - smoothstep(0.28, 0.68, lum));
        float stroke2 = smoothstep(0.62, 0.94, diag2) * (1.0 - smoothstep(0.10, 0.38, lum));
        float hatch = clamp(stroke1 * 0.36 + stroke2 * 0.32, 0.0, 0.52);

        vec3 hatchCol = mix(vec3(0.16, 0.14, 0.18), pigment * 0.42, 0.55);
        sketched = mix(sketched, hatchCol, hatch);
    }

    // 3. Hand-Drawn Graphite Silhouette Outlines (Reverse-Z exact linear-plane curvature)
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

    float distFade = smoothstep(0.00014, 0.00045, maxD);
    float lineWeight = (0.72 + 0.28 * paperFine);
    float depthPencil = max(
        smoothstep(0.058, 0.15, maxCurv),
        smoothstep(0.16, 0.34, maxStep)
    ) * distFade * lineWeight;

    float cloudPencil = 0.0;
    if (isSky) {
        vec2 cStep = texel * radius;
        float lL = luminance(texture(InSampler, texCoord - vec2(cStep.x, 0.0)).rgb);
        float lR = luminance(texture(InSampler, texCoord + vec2(cStep.x, 0.0)).rgb);
        float lD = luminance(texture(InSampler, texCoord - vec2(0.0, cStep.y)).rgb);
        float lU = luminance(texture(InSampler, texCoord + vec2(0.0, cStep.y)).rgb);
        cloudPencil = smoothstep(0.07, 0.17, abs(lR - lL) + abs(lU - lD)) * 0.48 * lineWeight;
    }

    float outline = clamp(max(depthPencil, cloudPencil), 0.0, 0.80);
    vec3 graphite = vec3(0.15, 0.13, 0.17);
    vec3 finalCol = mix(sketched, graphite, outline);

    fragColor = vec4(finalCol, 1.0);
}
