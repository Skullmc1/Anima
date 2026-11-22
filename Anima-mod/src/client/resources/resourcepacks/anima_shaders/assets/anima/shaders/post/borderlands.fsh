#version 330

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

// Exact scale-free linear depth proportional to true world distance Z (works at ANY render distance!)
// In standard perspective projection, (1.0 - d) ~ near / Z, so 1.0 / (1.0 - d) ~ Z / near.
float linearZ(float d) {
    return 1.0 / max(0.00015, 1.0 - d);
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// 8-direction symmetric offsets (1px crisp inner ring + 1.5px silhouette support)
const vec2 OFFSETS[4] = vec2[4](
    vec2( 1.0,  0.0),
    vec2( 0.0,  1.0),
    vec2( 1.0,  1.0),
    vec2(-1.0,  1.0)
);

void main() {
    vec2 texel = 1.0 / max(InSize, vec2(1.0));
    vec3 col = texture(InSampler, texCoord).rgb;
    float d0 = texture(InDepthSampler, texCoord).r;
    float z0 = linearZ(d0);
    float lum = luminance(col);

    // 1. Clean Borderlands Cel-Shading (balanced 1.14x saturation, 5 crisp comic tone bands, no poison green!)
    vec3 sat = clamp(mix(vec3(lum), col, 1.14), 0.0, 1.0);
    vec3 finalCol = sat;
    if (d0 < 0.9999) {
        float bands = 5.0;
        float scaled = lum * bands;
        float baseBand = floor(scaled);
        float frac = smoothstep(0.34, 0.66, fract(scaled));
        float celLum = clamp((baseBand + frac + 0.16) / (bands + 0.16), 0.05, 1.0);
        finalCol = clamp(sat * (celLum / max(0.035, lum)), 0.0, 1.0);
    }

    // 2. Scale-Invariant Laplacian Depth Silhouette Outlines (ZERO grid on flat ground/water!)
    // On any flat or sloped plane, z(+off) + z(-off) == 2*z0 -> curvature == 0.
    // On any cliff, step, tree, dead bush, sugar cane, grass, cactus, mob, or hand -> sharp non-zero spike!
    float maxCurv = 0.0;
    float maxStep = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 off = texel * OFFSETS[i];
        float za = linearZ(texture(InDepthSampler, texCoord + off).r);
        float zb = linearZ(texture(InDepthSampler, texCoord - off).r);

        // Relative depth curvature (catches both convex silhouettes AND concave corners!)
        float curv = abs(2.0 * z0 - (za + zb)) / max(z0, 1.0);
        // Also catch sharp foreground-vs-background silhouette jumps (e.g., dead bush / sugar cane in front of sky/ground)
        float stepA = abs(za - z0) / max(min(za, z0), 1.0);
        float stepB = abs(zb - z0) / max(min(zb, z0), 1.0);
        maxCurv = max(maxCurv, curv);
        maxStep = max(maxStep, max(stepA, stepB));
    }

    // Smooth distance fade only near the very far fog horizon
    float distFade = 1.0 - smoothstep(1800.0, 5200.0, z0);
    float depthInk = max(
        smoothstep(0.055, 0.145, maxCurv),
        smoothstep(0.14, 0.30, maxStep)
    ) * distFade;

    // 3. Crisp Comic Outlines on Clouds & High-Contrast Sky Silhouettes
    float cloudInk = 0.0;
    if (d0 >= 0.9997) {
        float lL = luminance(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb);
        float lR = luminance(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb);
        float lD = luminance(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb);
        float lU = luminance(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb);
        float grad = abs(lR - lL) + abs(lU - lD);
        cloudInk = smoothstep(0.065, 0.16, grad) * 0.72;
    }

    float ink = clamp(max(depthInk, cloudInk), 0.0, 0.92);
    vec3 inkColor = vec3(0.04, 0.035, 0.055);
    finalCol = mix(finalCol, inkColor, ink);

    fragColor = vec4(finalCol, 1.0);
}
