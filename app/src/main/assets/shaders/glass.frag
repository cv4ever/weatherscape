precision highp float;
// One water drop on the camera glass, refracting a copy of the frame drawn behind it.
varying vec2 vUv;
varying vec2 vLocal;
uniform sampler2D scene;
uniform vec2 texel;
uniform vec2 reach;
uniform float daylight;

vec3 look(vec2 p) { return texture2D(scene, clamp(p, vec2(.002), vec2(.998))).rgb; }

void main() {
  float r = length(vLocal);
  if (r >= 1.) discard;
  // Gravity flattens the top and gathers water low in the drop.
  float thick = sqrt(1. - r * r) * (.78 + .22 * smoothstep(-.9, .7, vLocal.y));
  float alpha = 1. - smoothstep(.82, 1., r);
  vec2 n = vec2(vLocal.x, -vLocal.y);
  // A water bead is a tiny wide-angle lens: it shows a wider, inverted view of the scene behind it.
  vec2 p = vUv - n * reach * (.35 + .65 * thick);
  vec2 s = texel * 2.5;
  vec3 c = (look(p) * 2. + look(p + vec2(s.x, 0.)) + look(p - vec2(s.x, 0.)) + look(p + vec2(0., s.y)) + look(p - vec2(0., s.y))) / 6.;
  c = c * 1.06 + .015;
  // Light leaves the rim at a grazing angle, so the edge reads dark.
  float rim = 1. - smoothstep(.08, .55, thick);
  c *= 1. - .7 * rim;
  // A soft highlight from the bright sky above.
  vec3 normal = normalize(vec3(n * 1.4, thick + .15));
  float spec = pow(max(dot(normal, normalize(vec3(-.3, .75, .6))), 0.), 60.);
  c += spec * (.05 + .3 * daylight) * vec3(.95, .97, 1.);
  gl_FragColor = vec4(c, 1.) * alpha;
}
