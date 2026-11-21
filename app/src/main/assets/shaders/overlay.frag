precision mediump float;
// shape.z selects the fill: 0 flat color, 1 soft radial glow, 2 atlas sprite (shape.xy is the texture coordinate).
uniform sampler2D atlas;
uniform float dim;
varying vec4 vColor;
varying vec3 vShape;

void main() {
  vec4 c = vColor;
  if (vShape.z > 1.5) {
    vec4 t = texture2D(atlas, vShape.xy);
    gl_FragColor = t * c.a * vec4(vec3(1. - dim), 1.);
    return;
  }
  if (vShape.z > .5) c.a *= exp(-dot(vShape.xy, vShape.xy) * 4.);
  gl_FragColor = vec4(c.rgb * (1. - dim) * c.a, c.a);
}
