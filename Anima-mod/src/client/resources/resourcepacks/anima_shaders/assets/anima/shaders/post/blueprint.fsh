#version 330

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

float linearZ(float d) {
    return 1.0 / max(0.00015, 1.0 - d);
}

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
    vec2 px = texCoord * max(InSize, vec2(1.0));
    vec2 texel = 1.0 / max(InSize, vec2(1.0));

    vec3 col = texture(InSampler, texCoord).rgb;
    float d0 = texture(InDepthSampler, texCoord).r;
    float z0 = linearZ(d0);
    float lum = luminance(col);

    // 1. Classic Architectural Cyanotype Blueprint Paper (textureless Prussian blue with subtle drafting vignette)
    vec2 vigUv = texCoord * 2.0 - 1.0;
    float vig = 1.0 - dot(vigUv, vigUv) * 0.16;
    float grain = (hash21(floor(px * 0.5)) - 0.5) * 0.025;

    vec3 deepBlue = vec3(0.055, 0.195, 0.445);
    vec3 midBlue  = vec3(0.095, 0.305, 0.625);
    vec3 skyBlue  = vec3(0.065, 0.235, 0.510);

    vec3 paper = (d0 >= 0.9997)
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
    if (d0 < 0.9997) {
        float h1 = smoothstep(0.68, 0.94, sin((px.x + px.y) * 0.85)) * (1.0 - smoothstep(0.22, 0.58, lum));
        float h2 = smoothstep(0.72, 0.96, sin((px.x - px.y) * 0.85)) * (1.0 - smoothstep(0.08, 0.28, lum));
        float hatch = clamp(h1 * 0.24 + h2 * 0.20, 0.0, 0.38);
        paper = mix(paper, vec3(0.78, 0.92, 1.0), hatch);
    }

    // 4. Crisp Bright-White Technical Linework (Scale-Invariant Depth Silhouettes + Cloud Edges)
    float maxCurv = 0.0;
    float maxStep = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 off = texel * OFFSETS[i];
        float za = linearZ(texture(InDepthSampler, texCoord + off).r);
        float zb = linearZ(texture(InDepthSampler, texCoord - off).r);
        float curv = abs(2.0 * z0 - (za + zb)) / max(z0, 1.0);
        float stepA = abs(za - z0) / max(min(za, z0), 1.0);
        float stepB = abs(zb - z0) / max(min(zb, z0), 1.0);
        maxCurv = max(maxCurv, curv);
        maxStep = max(maxStep, max(stepA, stepB));
    }

    float distFade = 1.0 - smoothstep(1800.0, 5200.0, z0);
    float depthLine = max(
        smoothstep(0.050, 0.135, maxCurv),
        smoothstep(0.13, 0.28, maxStep)
    ) * distFade;

    float cloudLine = 0.0;
    if (d0 >= 0.9997) {
        float lL = luminance(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb);
        float lR = luminance(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb);
        float lD = luminance(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb);
        float lU = luminance(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb);
        cloudLine = smoothstep(0.065, 0.16, abs(lR - lL) + abs(lU - lD)) * 0.65;
    }

    float whiteInk = clamp(max(depthLine, cloudLine), 0.0, 0.95);
    vec3 chalkWhite = vec3(0.92, 0.97, 1.00);
    vec3 finalCol = mix(paper, chalkWhite, whiteInk);

    fragColor = vec4(finalCol, 1.0);
}
