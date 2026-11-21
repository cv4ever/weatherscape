precision mediump float;
// shape.z selects the fill: 0 flat colour, 1 soft radial glow, 2..3 atlas sprite where
// shape.z - 2 is its saturation and colour.rgb its brightness (shape.xy is the texture coordinate).
uniform sampler2D atlas;
uniform float dim;
varying vec4 vColor;
varying vec3 vShape;

void main() {
  vec4 c = vColor;
  if (vShape.z > 1.5) {
    // Atlas texels are premultiplied, so tinting the colour channels keeps edges clean.
    vec4 t = texture2D(atlas, vShape.xy);
    float saturation = clamp(vShape.z - 2., 0., 1.);
    vec3 rgb = mix(vec3(dot(t.rgb, vec3(.3, .59, .11))), t.rgb, saturation) * c.rgb * (1. - dim);
    gl_FragColor = vec4(rgb, t.a) * c.a;
    return;
  }
  if (vShape.z > .5) c.a *= exp(-dot(vShape.xy, vShape.xy) * 4.);
  gl_FragColor = vec4(c.rgb * (1. - dim) * c.a, c.a);
}
