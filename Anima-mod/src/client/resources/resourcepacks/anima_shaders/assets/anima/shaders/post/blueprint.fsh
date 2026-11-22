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
    p = fract(p * vec2(127.1, 311.7));
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
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

    vec3 col = texture(InSampler, texCoord).rgb;
    // Minecraft 26.2 Reverse-Z depth: 0.0 = sky, 1.0 = near
    float d0 = texture(InDepthSampler, texCoord).r;
    bool isSky = (d0 <= 0.000001);
    float lum = luminance(col);

    // 1. Classic Architectural Cyanotype Blueprint Paper (textureless Prussian blue with subtle drafting vignette)
    vec2 vigUv = texCoord * 2.0 - 1.0;
    float vig = 1.0 - dot(vigUv, vigUv) * 0.16;
    float grain = (hash21(floor(px * 0.5)) - 0.5) * 0.025;

    vec3 deepBlue = vec3(0.055, 0.195, 0.445);
    vec3 midBlue  = vec3(0.095, 0.305, 0.625);
    vec3 skyBlue  = vec3(0.065, 0.235, 0.510);

    vec3 paper = isSky
        ? mix(skyBlue, midBlue * 0.92, clamp(1.0 - texCoord.y, 0.0, 1.0))
        : mix(deepBlue, midBlue, smoothstep(0.08, 0.90, lum));
    paper = paper * clamp(vig, 0.72, 1.0) + vec3(grain);

    // 2. Crisp Technical Drafting Grid (minor 24px grid + major 120px engineering lines)
    vec2 minorG = abs(fract(px / 24.0 + 0.5) - 0.5) * 24.0;
    vec2 majorG = abs(fract(px / 120.0 + 0.5) - 0.5) * 120.0;
    float minorLine = (1.0 - smoothstep(0.4, 1.1, min(minorG.x, minorG.y))) * 0.11;
    float majorLine = (1.0 - smoothstep(0.5, 1.4, min(majorG.x, majorG.y))) * 0.22;
    vec3 gridCol = vec3(0.45, 0.76, 1.0);
    paper = mix(paper, gridCol, clamp(max(minorLine, majorLine), 0.0, 0.28));

    // 3. Architectural White Diagonal Hatching in Shaded Geometry
    if (!isSky) {
        float h1 = smoothstep(0.68, 0.94, sin((px.x + px.y) * 0.85)) * (1.0 - smoothstep(0.22, 0.58, lum));
        float h2 = smoothstep(0.72, 0.96, sin((px.x - px.y) * 0.85)) * (1.0 - smoothstep(0.08, 0.28, lum));
        float hatch = clamp(h1 * 0.24 + h2 * 0.20, 0.0, 0.38);
        paper = mix(paper, vec3(0.78, 0.92, 1.0), hatch);
    }

    // 4. Crisp Bright-White Technical Linework (Reverse-Z exact linear-plane curvature)
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

    float distFade = smoothstep(0.00012, 0.00042, maxD);
    float depthLine = max(
        smoothstep(0.050, 0.135, maxCurv),
        smoothstep(0.15, 0.32, maxStep)
    ) * distFade;

    float cloudLine = 0.0;
    if (isSky) {
        vec2 cStep = texel * radius;
        float lL = luminance(texture(InSampler, texCoord - vec2(cStep.x, 0.0)).rgb);
        float lR = luminance(texture(InSampler, texCoord + vec2(cStep.x, 0.0)).rgb);
        float lD = luminance(texture(InSampler, texCoord - vec2(0.0, cStep.y)).rgb);
        float lU = luminance(texture(InSampler, texCoord + vec2(0.0, cStep.y)).rgb);
        cloudLine = smoothstep(0.065, 0.16, abs(lR - lL) + abs(lU - lD)) * 0.65;
    }

    float whiteInk = clamp(max(depthLine, cloudLine), 0.0, 0.95);
    vec3 chalkWhite = vec3(0.92, 0.97, 1.00);
    vec3 finalCol = mix(paper, chalkWhite, whiteInk);

    fragColor = vec4(finalCol, 1.0);
}
