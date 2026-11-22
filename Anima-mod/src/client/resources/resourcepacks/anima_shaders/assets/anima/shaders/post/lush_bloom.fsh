#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BloomDir {
    vec2 Dir;
    float Extract;
};

out vec4 fragColor;

vec3 extractBright(vec3 c) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float mask = smoothstep(0.56, 0.96, l);
    return c * mask;
}

void main() {
    vec2 stepUv = (Dir / max(InSize, vec2(1.0))) * 2.2;
    const float weights[5] = float[5](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);

    vec3 center = texture(InSampler, texCoord).rgb;
    if (Extract > 0.5) center = extractBright(center);
    vec3 sum = center * weights[0];

    for (int i = 1; i < 5; i++) {
        vec2 off = stepUv * float(i);
        vec3 s1 = texture(InSampler, texCoord + off).rgb;
        vec3 s2 = texture(InSampler, texCoord - off).rgb;
        if (Extract > 0.5) {
            s1 = extractBright(s1);
            s2 = extractBright(s2);
        }
        sum += (s1 + s2) * weights[i];
    }

    fragColor = vec4(sum, 1.0);
}
