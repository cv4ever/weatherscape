precision highp float;
// Condensation on the camera glass: a frosted veil that gathers at the edges and clears from the middle.
varying vec2 vUv;
uniform sampler2D scene;
uniform sampler2D grain;
uniform vec2 texel;
/** Screen height over width, so the veil's shape and grain stay round. */
uniform float ratio;
/** 0 clear glass, 1 the edges fully fogged. */
uniform float amount;
uniform float flow;

float hash(vec2 p) { return texture2D(grain, (mod(floor(p), 256.) + .5) / 256.).r; }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3. - 2. * f);
  return mix(mix(hash(i), hash(i + vec2(1., 0.)), f.x), mix(hash(i + vec2(0., 1.)), hash(i + vec2(1., 1.)), f.x), f.y);
}

void main() {
  vec2 q = vec2(vUv.x, vUv.y * ratio);
  vec2 c = vec2(vUv.x - .5, (vUv.y - .5) * ratio * .62);
  float edge = smoothstep(.16, .6, length(c));
  // Fine breath-like patches over a slow, larger structure.
  float patches = .62 * noise(q * 5. + vec2(flow * .015, 0.)) + .38 * noise(q * 17. + 3.1);
  float veil = clamp(amount * 1.35 * (edge * 1.25 - .55 + .75 * patches), 0., 1.);
  if (veil < .004) discard;
  // Scattering: a wide blur of what is behind, lifted towards pale grey.
  vec2 s = texel * 9.;
  vec3 b = texture2D(scene, vUv).rgb * .2;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * .785398;
    b += texture2D(scene, clamp(vUv + vec2(cos(a), sin(a)) * s * (1. + mod(float(i), 2.)), vec2(.002), vec2(.998))).rgb * .1;
  }
  vec3 haze = mix(b, vec3(dot(b, vec3(.333))), .3) * .86 + .1;
  float a = veil * .88;
  gl_FragColor = vec4(haze, 1.) * a;
}
