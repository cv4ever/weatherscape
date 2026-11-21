precision highp float;
// One water drop or splash on the camera glass, refracting a copy of the frame drawn behind it.
varying vec2 vUv;
varying vec2 vLocal;
varying vec3 vShape;
uniform sampler2D scene;
uniform vec2 texel;
uniform vec2 reach;
uniform float daylight;

vec3 look(vec2 p) { return texture2D(scene, clamp(p, vec2(.002), vec2(.998))).rgb; }

void main() {
  float r = length(vLocal);
  if (r >= 1.) discard;
  vec2 n;
  float thick;
  float alpha;
  if (vShape.x > .5) {
    // A splash: a thin, uneven ring of water racing outwards and thinning as it spreads.
    float age = vShape.y;
    float angle = atan(vLocal.y, vLocal.x);
    float ragged = .75 + .25 * sin(angle * 7. + vShape.z * 40.) * sin(angle * 3. - vShape.z * 17.);
    float ring = .3 + .62 * sqrt(age);
    float band = (r - ring) / (.1 + .06 * age);
    thick = exp(-band * band) * ragged * (1. - age);
    if (thick < .02) discard;
    n = (r > .001 ? vLocal / r : vec2(0.)) * clamp(-band, -1., 1.) * thick;
    n.y = -n.y;
    alpha = min(1., thick * 1.6) * (1. - age * age);
  } else {
    // Gravity flattens the top and gathers water low in the drop.
    thick = sqrt(1. - r * r) * (.78 + .22 * smoothstep(-.9, .7, vLocal.y));
    alpha = 1. - smoothstep(.82, 1., r);
    n = vec2(vLocal.x, -vLocal.y);
  }
  // A water bead is a tiny wide-angle lens: it shows a wider, inverted view of the scene behind it.
  vec2 p = vUv - n * reach * (.35 + .65 * thick);
  vec2 s = texel * 2.5;
  vec3 c = (look(p) * 2. + look(p + vec2(s.x, 0.)) + look(p - vec2(s.x, 0.)) + look(p + vec2(0., s.y)) + look(p - vec2(0., s.y))) / 6.;
  c = c * 1.06 + .015;
  // Light leaves the rim at a grazing angle, so the edge reads dark.
  float rim = 1. - smoothstep(.08, .55, thick);
  c *= 1. - .7 * rim * step(vShape.x, .5);
  // A soft highlight from the bright sky above.
  vec3 normal = normalize(vec3(n * 1.4, thick + .15));
  float spec = pow(max(dot(normal, normalize(vec3(-.3, .75, .6))), 0.), 60.);
  c += spec * (.05 + .3 * daylight) * vec3(.95, .97, 1.);
  gl_FragColor = vec4(c, 1.) * alpha;
}
