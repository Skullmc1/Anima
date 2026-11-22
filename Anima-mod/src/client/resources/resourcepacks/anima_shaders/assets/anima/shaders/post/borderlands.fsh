#version 330

uniform sampler2D InSampler;
uniform sampler2D InDepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

// Linearize Minecraft's depth buffer (near = 0.05, far = 256.0)
float linearDepth(float d) {
    float near = 0.05;
    float far = 256.0;
    return (2.0 * near) / (far + near - d * (far - near));
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Miasma-inspired 12-tap Laplacian Depth Silhouette Outlines:
// Zero grid lines on flat ground or water; crisp ink silhouettes on cliffs, trees, dead bushes,
// sugar cane, grass, mobs, hands, AND clouds all the way to the horizon!
const vec2 OFFSETS[12] = vec2[12](
    vec2( 1.0,  0.0),
    vec2(-1.0,  1.0),
    vec2( 0.0,  1.0),
    vec2( 1.0,  1.0),
    vec2(-2.0,  2.0),
    vec2(-1.0,  2.0),
    vec2( 0.0,  2.0),
    vec2( 1.0,  2.0),
    vec2( 2.0,  2.0),
    vec2(-2.0,  1.0),
    vec2( 2.0,  1.0),
    vec2( 2.0,  0.0)
);

void main() {
    vec2 texel = 1.0 / max(InSize, vec2(1.0));
    vec3 col = texture(InSampler, texCoord).rgb;
    float z0 = texture(InDepthSampler, texCoord).r;
    float linZ0 = linearDepth(z0);

    // 1. Balanced Borderlands Cel-Shading (clean 1.14x saturation, no poison green, no blowout!)
    float lum = luminance(col);
    vec3 sat = clamp(mix(vec3(lum), col, 1.14), 0.0, 1.0);

    // Only cel-band geometry & clouds, keep smooth sky gradients clean
    vec3 finalCol = sat;
    if (z0 < 0.99995) {
        float bands = 5.0;
        float scaled = lum * bands;
        float baseBand = floor(scaled);
        float frac = smoothstep(0.35, 0.65, fract(scaled));
        float celLum = clamp((baseBand + frac + 0.16) / (bands + 0.16), 0.05, 1.0);
        finalCol = clamp(sat * (celLum / max(0.035, lum)), 0.0, 1.0);
    }

    // 2. Miasma-style Laplacian Depth Silhouette Outlines (works on terrain, dead bushes, sugar cane, grass, mobs, hands!)
    float outlineFactor = 1.0;
    float zScaled = linZ0 * 512.0;

    for (int i = 0; i < 12; i++) {
        vec2 off = texel * OFFSETS[i];
        float za = linearDepth(texture(InDepthSampler, texCoord + off).r);
        float zb = linearDepth(texture(InDepthSampler, texCoord - off).r);
        float diff = zScaled - (za + zb) * 256.0;
        outlineFactor *= clamp(1.0 - max(0.0, diff - 0.18) * 1.15, 0.0, 1.0);
    }

    float depthInk = (1.0 - outlineFactor) * (1.0 - smoothstep(0.55, 0.98, linZ0));

    // 3. Crisp Comic Outlines on Clouds (sky-depth pixels where bright cloud meets blue/dusk sky!)
    float cloudInk = 0.0;
    if (z0 >= 0.9998) {
        float lL = luminance(texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb);
        float lR = luminance(texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb);
        float lD = luminance(texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb);
        float lU = luminance(texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb);
        float grad = abs(lR - lL) + abs(lU - lD);
        // Only outline crisp cloud edges (ignore smooth sun/horizon gradients)
        cloudInk = smoothstep(0.075, 0.19, grad) * 0.62;
    }

    float ink = clamp(max(depthInk, cloudInk), 0.0, 0.90);
    vec3 inkColor = vec3(0.04, 0.035, 0.055);
    finalCol = mix(finalCol, inkColor, ink);

    fragColor = vec4(finalCol, 1.0);
}
