#version 330

uniform sampler2D InSampler;
uniform sampler2D BloomSampler;
uniform sampler2D InDepthSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

out vec4 fragColor;

// Complementary Reimagined-inspired ACES filmic curve
vec3 acesTonemap(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec3 col = texture(InSampler, texCoord).rgb;
    vec3 bloom = texture(BloomSampler, texCoord).rgb;
    float depth = texture(InDepthSampler, texCoord).r;

    // 1. Add soft Complementary-style warm screen-space Bloom (sun, torches, water glints, sky!)
    col += bloom * vec3(0.46, 0.42, 0.35);

    // 2. Complementary warm-key / cool-shadow color grading
    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    vec3 shadowTint = vec3(0.90, 0.96, 1.09);
    vec3 highlightTint = vec3(1.08, 1.02, 0.92);
    col *= mix(shadowTint, highlightTint, smoothstep(0.14, 0.76, lum));

    // 3. Filmic S-curve tonemap + crisp vibrancy across the ENTIRE frame
    vec3 mapped = acesTonemap(col * 1.18);
    col = mix(col, mapped, 0.58);

    float finalLum = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = clamp(mix(vec3(finalLum), col, 1.15), 0.0, 1.0);

    // 4. Subtle atmospheric cinema vignette (only very gentle at screen corners)
    vec2 vigUv = texCoord * 2.0 - 1.0;
    float vig = 1.0 - dot(vigUv, vigUv) * 0.085;
    col *= clamp(vig, 0.85, 1.0);

    fragColor = vec4(col, 1.0);
}
