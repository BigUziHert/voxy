//World curvature, bends lod geometry down and inwards around the camera so that distant terrain
// drops away over a horizon like it would on a sphere.
//Requires the SceneUniform block, so <voxy:lod/gl46/bindings.glsl> must be imported first.

//Everything within worldCurveData.y of the camera is left flat, that region is where vanilla is
// still drawing its own (uncurved) chunks, so bending there would tear the seam apart.
vec3 applyWorldCurvature(vec3 point) {
    float radius = worldCurveData.x;
    vec2 delta = point.xz - cameraSubPos.xz;
    float distanceFromCamera = length(delta);
    float arcDistance = distanceFromCamera - worldCurveData.y;

    //Note: worldCurveData.y is never negative, so distanceFromCamera == 0 always lands here and
    // the divide at the bottom is only ever reached with distanceFromCamera > worldCurveData.y
    if (radius <= 0.0f || arcDistance <= 0.0f) {
        return point;
    }

    //Blocks higher up sit on a larger sphere so they fall away more slowly
    float localRadius = max(radius + point.y, 1.0f);
    float angle = arcDistance / localRadius;

    //cos(angle)-1 is catastrophic cancellation in fp32 at the tiny angles a planet sized radius
    // produces (cos is within an ulp or two of 1, which quantises the drop to whole blocks), the
    // haversine identity cos(a)-1 == -2*sin(a/2)^2 keeps full relative precision instead
    float halfAngleSin = sin(angle * 0.5f);
    point.y -= 2.0f * halfAngleSin * halfAngleSin * localRadius;

    //Walking along the surface of the sphere also pulls the point radially inwards
    point.xz = cameraSubPos.xz + delta * ((worldCurveData.y + (sin(angle) * localRadius)) / distanceFromCamera);
    return point;
}
