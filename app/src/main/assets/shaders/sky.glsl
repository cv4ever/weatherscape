precision highp float;
// Shared by every scenery: the sky above the horizon and the noise it is built from.
// Coordinates are plate UVs: x 0..1 left to right, y 0..1 top to bottom, horizon near y .568.
varying vec2 uv;
uniform sampler2D stars, moonTexture, auroraTexture;
// 256x256 random bytes, nearest filtering, repeating. Arithmetic hashes break on Adreno: the
// compiler fuses each inlined call differently, so neighbouring noise cells disagree and seams appear.
uniform sampler2D grain;
uniform float phase;
// Continuous time in 18-second units. Fog and clouds advect on it without ever looping or cross-fading.
uniform float flow;
// air: wind, valley mist, cloud cover, rainfall.
uniform vec4 air;
uniform vec3 sky, horizonColor, mist;
// light: star visibility, golden-hour amount, side the sun is on (-1 morning, 1 afternoon).
uniform vec3 light;
// moon: centre x, y in plate UV, radius in plate widths, visibility. moonLight: direction sunlight hits it from.
uniform vec4 moon;
uniform vec3 moonLight;
// moonGlow: how much moonlight silvers the mist.
uniform float moonGlow, aurora;
const float tau = 6.2831853;

float hash(vec2 p) { return texture2D(grain, (mod(floor(p), 256.) + .5) / 256.).r; }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}
// Timelapse vapor: the pattern drifts with the wind and its finer octaves slide at their own
// speeds, so the shapes keep changing as they move, like clouds filmed at a high speed-up.
float vapor(vec2 xy, float speed, float seed) {
  float travel = flow * speed;
  vec2 q = xy + vec2(seed - travel, seed * .37);
  float body = noise(q + vec2(0., flow * .11));
  float billow = noise(q * 2.3 + vec2(travel * .42, -flow * .31));
  float wisp = noise(q * 5.1 - vec2(travel * .67, flow * .23));
  return smoothstep(.20, .80, .62 * body + .25 * billow + .13 * wisp);
}

// Light through the cloud breathes over a few minutes rather than pulsing every loop.
float opening() { return .5 - .5 * cos(flow * tau / 9.); }
// How much the sun lights the land through the cloud.
float sunThrough() { return 1. - air.z * .91; }
// 0 on the side away from the sun, 1 on the side it is on.
float litSide(vec2 p) { return mix(1. - p.x, p.x, .5 + .5 * light.z); }

/** The sky at [p]: gradient, golden hour, stars, moon, aurora and clouds. */
vec3 skyAt(vec2 p) {
  float cover = air.z;
  float sun = sunThrough();
  float horizon = smoothstep(.05, .56, p.y);
  vec3 aerial = mix(sky, horizonColor, horizon * horizon);
  float elevation = (p.y - .54) * 3.45;
  float warmth = exp(-elevation * elevation) * (.36 + .64 * litSide(p));
  aerial = mix(aerial, vec3(1., .63, .41), warmth * light.y * sun * .68);
  if (light.x > 0.) {
    vec2 where = vec2(p.x, p.y + .17);
    vec3 galaxy = texture2D(stars, where).rgb;
    float starlight = dot(galaxy, vec3(.24, .68, .08));
    float seed = hash(floor(where * vec2(470., 765.)));
    float twinkle = .86 + .14 * sin(tau * (phase * (2. + floor(seed * 4.)) + seed));
    float dust = pow(max(0., starlight - .029), 1.14);
    float points = smoothstep(.34, .83, starlight) * twinkle;
    // By day a partial sky is a veil; at night the clouds break into patches with stars between,
    // so only a near-overcast sky hides them everywhere.
    float clarity = mix(1. - smoothstep(.22, .57, cover), 1. - smoothstep(.8, 1., cover), light.x);
    vec2 field = where * vec2(23., 41.);
    vec2 cell = floor(field);
    float id = hash(cell);
    vec2 center = vec2(hash(cell + 14.4), hash(cell + 27.3)) * .72 + .14;
    vec2 delta = fract(field) - center;
    float core = exp(-dot(delta, delta) * mix(600., 255., id));
    float scintillation = .77 + .23 * sin(tau * (phase * (2. + floor(id * 5.)) + id));
    float pinpricks = step(.79, id) * core * scintillation * mix(.14, .39, id);
    // Against a fully dark sky the eye adapts and far more stars show.
    float adapted = 1. + .8 * (1. - smoothstep(.02, .12, dot(sky, vec3(.333))));
    aerial += vec3(.65, .74, .90) * (dust * .53 + points * .29 + pinpricks) * adapted * light.x * clarity * (1. - .87 * horizon * horizon);
  }
  aerial *= mix(.96, 1.04, opening());
  if (moon.w > .001) {
    vec2 d = (p - moon.xy) * vec2(1., 1.7777778) / moon.z;
    float r = length(d);
    // Low moons pass through more air: dimmer, warmer, softer.
    float low = smoothstep(.43, .6, moon.y);
    vec3 airTint = mix(vec3(1., .98, .94), vec3(1., .8, .6), low);
    if (r < 1.15) {
      vec3 normal = vec3(d.x, -d.y, sqrt(max(0., 1. - r * r)));
      float lit = smoothstep(-.15, .2, dot(normal, moonLight));
      // A wide blur keeps only the broad maria: through air the moon is a glow with faint markings.
      vec2 t = d * .5 + .5;
      float surface = 0.;
      for (int i = 0; i < 8; i++) {
        float a = float(i) * .785398;
        surface += dot(texture2D(moonTexture, t + vec2(cos(a), sin(a)) * .035).rgb, vec3(.33));
      }
      float markings = mix(.82, 1.08, smoothstep(.25, .7, surface / 8.));
      float limb = .9 + .1 * normal.z;
      vec3 disc = airTint * vec3(1., .99, .96) * (1.75 * lit * markings * limb + .03) * mix(1., .7, low);
      float haze = .08 + .22 * vapor(p * vec2(7., 40.), .6, 8.3) * (.4 + cover);
      float edge = 1. - smoothstep(.82, 1.12, r);
      aerial = mix(aerial, mix(disc, aerial, haze), moon.w * edge);
    }
    // Bloom: a bright core spill, a soft corona and a faint wide aureole.
    float outside = max(r - .9, 0.);
    float halo = .55 * exp(-outside * 7.) + .22 * exp(-outside * 2.2) + .07 * exp(-outside * .6);
    aerial += vec3(.78, .81, .88) * airTint * halo * moon.w * moonGlow * (1. - .5 * cover);
  }
  if (aurora > .001) {
    // One faint curtain low in the north-east, slowly folding: a photo layer warped and windowed in width.
    float v = (p.y - .2) / .32;
    if (v > 0. && v < 1.) {
      float warp = .012 * sin(v * 5. + flow * .9 + p.x * 2.5) + .025 * (noise(vec2(p.x * 1.5, flow * .2)) - .5);
      vec2 a = vec2(p.x * .42 + .28 + flow * .012 + warp, v * 1.05 - .02 + .03 * sin(p.x * 2.6 + flow * .3));
      vec3 curtains = texture2D(auroraTexture, a).rgb;
      curtains = mix(vec3(dot(curtains, vec3(.3, .55, .15))), curtains, .7);
      float window = exp(-pow((p.x - .66) / .2, 2.));
      float shimmer = .78 + .22 * noise(vec2(p.x * 90. + flow * 2., v * 2.));
      float surge = .6 + .4 * noise(vec2(p.x * 2.2 - flow * .5, 1.7));
      float fade = smoothstep(0., .25, v) * (1. - smoothstep(.65, 1., v));
      aerial += curtains * window * shimmer * surge * fade * aurora * .13 * (1. - smoothstep(.2, .6, cover));
    }
  }
  if (cover > 0.) {
    float sheet = vapor(p * vec2(2.9, 9. + 9. * (1. - cover)), 1.9 + air.x * 1.5, 6.7);
    float relief = smoothstep(.17, .86, sheet) * (.24 + .76 * (1. - horizon));
    vec3 ceiling = mix(vec3(.28, .34, .40), mist, .48 + .35 * horizon);
    // Cloud undersides only catch as much light as the sky has: dim at dusk, near black at night,
    // lifted a little when the moon is up.
    float skyLight = smoothstep(.09, .55, dot(sky, vec3(.333)));
    ceiling *= mix(1.05, .67, air.w) * mix(.12, 1., skyLight);
    ceiling += vec3(.11, .12, .15) * moonGlow * (.5 + .5 * relief);
    float veil = cover * mix(.65, .98, relief);
    // At night: separate patches whose share of the sky follows the cloud cover.
    float edge = .8 - .6 * cover;
    float patches = smoothstep(edge - .16, edge + .16, sheet) * min(1., cover * 2.5);
    aerial = mix(aerial, ceiling * (.79 + .32 * relief), mix(veil, max(patches, veil * smoothstep(.85, 1., cover)), light.x));
  }
  return aerial;
}
