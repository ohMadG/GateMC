#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:globals.glsl>

vec3 paintStops(int paint, int i) {
    if (paint == 1) {
        vec3 s[4] = vec3[4](vec3(0.490, 0.267, 0.816), vec3(0.788, 0.714, 0.949), vec3(0.949, 0.702, 0.239), vec3(0.788, 0.714, 0.949));
        return s[i % 4];
    }
    if (paint == 2) {
        vec3 s[4] = vec3[4](vec3(0.243, 0.878, 1.000), vec3(0.490, 0.267, 0.816), vec3(1.000, 0.302, 0.824), vec3(0.490, 0.267, 0.816));
        return s[i % 4];
    }
    if (paint == 3) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.165, 0.165), vec3(1.000, 0.416, 0.082), vec3(1.000, 0.667, 0.000), vec3(1.000, 0.416, 0.082));
        return s[i % 4];
    }
    if (paint == 5) {
        vec3 s[4] = vec3[4](vec3(0.118, 0.427, 1.000), vec3(0.243, 0.878, 1.000), vec3(0.106, 0.247, 0.659), vec3(0.243, 0.878, 1.000));
        return s[i % 4];
    }
    if (paint == 6) {
        vec3 s[4] = vec3[4](vec3(0.482, 1.000, 0.227), vec3(0.843, 1.000, 0.227), vec3(0.180, 0.620, 0.271), vec3(0.843, 1.000, 0.227));
        return s[i % 4];
    }
    if (paint == 7) {
        vec3 s[4] = vec3[4](vec3(1.000, 1.000, 1.000), vec3(0.663, 0.910, 1.000), vec3(0.353, 0.722, 1.000), vec3(0.663, 0.910, 1.000));
        return s[i % 4];
    }
    if (paint == 8) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.494, 0.000), vec3(1.000, 0.184, 0.333), vec3(0.722, 0.196, 0.502), vec3(1.000, 0.184, 0.333));
        return s[i % 4];
    }
    if (paint == 9) {
        vec3 s[4] = vec3[4](vec3(0.165, 0.059, 0.361), vec3(0.490, 0.267, 0.816), vec3(1.000, 0.302, 0.824), vec3(0.243, 0.878, 1.000));
        return s[i % 4];
    }
    if (paint == 10) {
        vec3 s[4] = vec3[4](vec3(0.949, 0.702, 0.239), vec3(1.000, 0.945, 0.722), vec3(0.769, 0.498, 0.071), vec3(1.000, 0.945, 0.722));
        return s[i % 4];
    }
    if (paint == 11) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.106, 0.161), vec3(1.000, 1.000, 1.000), vec3(0.784, 0.000, 0.078), vec3(1.000, 1.000, 1.000));
        return s[i % 4];
    }
    if (paint == 12) {
        vec3 s[4] = vec3[4](vec3(0.420, 0.000, 0.039), vec3(1.000, 0.180, 0.110), vec3(0.161, 0.110, 0.122), vec3(1.000, 0.420, 0.110));
        return s[i % 4];
    }
    if (paint == 13) {
        vec3 s[4] = vec3[4](vec3(0.180, 0.800, 0.443), vec3(1.000, 0.843, 0.290), vec3(0.059, 0.478, 0.220), vec3(0.851, 1.000, 0.902));
        return s[i % 4];
    }
    if (paint == 14) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.710, 0.800), vec3(1.000, 1.000, 1.000), vec3(1.000, 0.450, 0.650), vec3(1.000, 0.860, 0.920));
        return s[i % 4];
    }
    if (paint == 15) {
        vec3 s[4] = vec3[4](vec3(0.000, 0.800, 0.750), vec3(0.550, 1.000, 0.900), vec3(0.000, 0.550, 0.700), vec3(0.550, 1.000, 0.900));
        return s[i % 4];
    }
    if (paint == 16) {
        vec3 s[4] = vec3[4](vec3(0.220, 1.000, 0.080), vec3(0.000, 1.000, 0.940), vec3(1.000, 0.000, 0.800), vec3(0.000, 1.000, 0.940));
        return s[i % 4];
    }
    if (paint == 17) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.690, 0.450), vec3(1.000, 0.860, 0.700), vec3(1.000, 0.500, 0.550), vec3(1.000, 0.860, 0.700));
        return s[i % 4];
    }
    if (paint == 18) {
        vec3 s[4] = vec3[4](vec3(0.550, 0.600, 0.700), vec3(0.850, 0.900, 1.000), vec3(0.400, 0.450, 0.600), vec3(1.000, 0.950, 0.550));
        return s[i % 4];
    }
    if (paint == 19) {
        vec3 s[4] = vec3[4](vec3(0.000, 0.700, 0.350), vec3(0.600, 1.000, 0.750), vec3(0.000, 0.500, 0.280), vec3(0.600, 1.000, 0.750));
        return s[i % 4];
    }
    if (paint == 20) {
        vec3 s[4] = vec3[4](vec3(0.750, 0.050, 0.100), vec3(1.000, 0.300, 0.300), vec3(0.550, 0.000, 0.080), vec3(1.000, 0.500, 0.350));
        return s[i % 4];
    }
    if (paint == 21) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.600, 0.850), vec3(0.600, 0.850, 1.000), vec3(0.850, 0.700, 1.000), vec3(0.600, 0.850, 1.000));
        return s[i % 4];
    }
    if (paint == 22) {
        vec3 s[4] = vec3[4](vec3(0.550, 0.250, 0.850), vec3(0.100, 0.800, 0.750), vec3(0.700, 0.400, 1.000), vec3(0.100, 0.800, 0.750));
        return s[i % 4];
    }
    if (paint == 23) {
        vec3 s[4] = vec3[4](vec3(1.000, 0.850, 0.200), vec3(1.000, 1.000, 0.700), vec3(1.000, 0.550, 0.000), vec3(1.000, 1.000, 0.700));
        return s[i % 4];
    }
    if (paint == 24) {
        vec3 s[4] = vec3[4](vec3(0.150, 0.350, 0.750), vec3(0.350, 0.750, 1.000), vec3(0.100, 0.250, 0.600), vec3(0.550, 0.900, 1.000));
        return s[i % 4];
    }
    if (paint == 25) {
        vec3 s[4] = vec3[4](vec3(0.800, 0.950, 1.000), vec3(1.000, 0.800, 0.950), vec3(0.850, 1.000, 0.850), vec3(1.000, 0.950, 0.800));
        return s[i % 4];
    }
    vec3 s[6] = vec3[6](vec3(1.0, 0.333, 0.333), vec3(1.0, 0.667, 0.0), vec3(1.0, 1.0, 0.333), vec3(0.333, 1.0, 0.333), vec3(0.333, 1.0, 1.0), vec3(1.0, 0.333, 1.0));
    return s[i % 6];
}

vec3 paintColor(int paint, float t) {
    int n = paint == 4 ? 6 : 4;
    float x = fract(t) * float(n);
    int i = int(floor(x));
    return mix(paintStops(paint, i), paintStops(paint, i + 1), smoothstep(0.0, 1.0, fract(x)));
}

vec4 paintText(vec4 color, vec4 clipPos) {
    ivec3 c = ivec3(round(color.rgb * 255.0));
    int paint = 0;
    float shade = 1.0;
    if (c.r == 254 && c.b == 253 && c.g % 4 == 0) {
        paint = c.g / 4;
    } else if (c.r == 63 && c.b == 63) {
        paint = c.g;
        shade = 0.25;
    }
    if (paint < 1 || paint > 25) {
        return color;
    }
    float x = clipPos.x / max(clipPos.w, 0.0001);
    float t = GameTime * 300.0 - x * 0.75;
    return vec4(paintColor(paint, t) * shade, color.a);
}

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = paintText(Color, gl_Position) * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = paintText(Color, gl_Position);
#endif
    texCoord0 = UV0;
}
