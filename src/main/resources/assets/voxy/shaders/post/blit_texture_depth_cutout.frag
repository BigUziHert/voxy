#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec2 fogParams;//.x=fogStart,.y=fogEnd
layout(location = 5) uniform vec4 fogColor;
layout(location = 6) uniform int fogShape;
layout(location = 7) uniform float fogIntensity;
layout(location = 8) uniform float fogDensity;
#endif
#endif

#import <sodium:include/fog.glsl>

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip*2.0f-1.0f,1.0f);
    return view.xyz/view.w;
}
float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    depth = projDepth(point);
    depth = min(1.0f-(2.0f/((1<<24)-1)), depth);
    depth = depth * 0.5f + 0.5f;
    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;
    gl_FragDepth = depth;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #ifdef USE_ENV_FOG
    if (fogIntensity > 0.0){
        float dist = getFragDistance(fogShape, point.xyz);
        float fogLerp = smoothstep(fogParams.x, fogParams.y, dist);
        if (fogDensity > 0.0) fogLerp = (exp(fogDensity * fogLerp) - 1.0) / (exp(fogDensity) - 1.0);
        colour.rgb = mix(colour.rgb, fogColor.rgb, clamp(fogLerp * fogIntensity, 0.0, 1.0));
    }
    #endif
    #else
    colour = vec4(0);
    #endif

}
