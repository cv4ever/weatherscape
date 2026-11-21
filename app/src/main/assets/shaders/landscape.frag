precision highp float;
// Coordinates are plate UVs: x 0..1 left to right, y 0..1 top to bottom.
varying vec2 uv;
uniform sampler2D landscape, stars;
// 256x256 random bytes, nearest filtering, repeating. Arithmetic hashes break on Adreno: the
// compiler fuses each inlined call differently, so neighbouring noise cells disagree and seams appear.
uniform sampler2D grain;
uniform float phase, gust, flash, sunbeam, rainbow, dim;
// Continuous time in 18-second units. Fog and clouds advect on it without ever looping or cross-fading.
uniform float flow;
// air: wind, valley mist, cloud cover, rainfall.
uniform vec4 air;
uniform vec3 ground, sky, horizonColor, mist;
// light: star visibility, golden-hour amount, side the sun is on (-1 morning, 1 afternoon).
uniform vec3 light;
const float tau = 6.2831853;

float hash(vec2 p) { return texture2D(grain, (mod(floor(p), 256.) + .5) / 256.).r; }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p); f = f * f * (3. - 2. * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), f.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), f.x), f.y);
}
// Horizontal displacement for one foreground tree crown; trunks stay anchored.
float breeze(vec2 p, float x, float tip, float root, float width, float lag) {
  float dx = (p.x - x) / width;
  float crown = exp(-dx * dx) * smoothstep(tip - .016, tip + .018, p.y);
  float bend = 1. - smoothstep(tip, root, p.y);
  float wave = .52 * sin(tau * phase * 3. - lag) + .23 * sin(tau * phase * 7. - lag * 1.7) + .25 * sin(tau * phase - .9);
  return crown * bend * bend * wave * gust;
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
float bank(vec2 p, float center, float depth, float seed, float speed) {
  float position = center + .010 * sin(p.x * 5.4 + seed) + .005 * sin(p.x * 13. + seed);
  float d = (p.y - position) / depth;
  return exp(-d * d) * vapor(p * vec2(4.4, 35.), speed, seed);
}

void main() {
  vec2 p = uv;
  float shift = 0.;
  if (p.y > .59) {
    shift = air.x * (.013 * breeze(p, .058, .608, .94, .091, 0.) + .011 * breeze(p, .19, .710, .97, .062, .9)
      + .009 * breeze(p, .474, .710, .99, .078, 2.1) + .010 * breeze(p, .798, .696, .98, .071, 1.6)
      + .008 * breeze(p, .927, .701, .98, .067, 2.8));
  }
  vec3 base = texture2D(landscape, vec2(clamp(p.x - shift, 0., 1.), p.y)).rgb;
  // Light through the cloud breathes over a few minutes rather than pulsing every loop.
  float opening = .5 - .5 * cos(flow * tau / 9.);
  float cover = air.z;
  float sun = 1. - cover * .91;
  float luma = dot(base, vec3(.2126, .7152, .0722));
  vec3 scene = base * ground * mix(.97, 1.035, opening) * mix(1., .62, cover);
  scene = mix(scene, vec3(luma) * ground * mix(1., .62, cover), cover * .36);
  float groundHeight = (p.y - .59) * 5.;
  float litSide = mix(1. - p.x, p.x, .5 + .5 * light.z);
  float flank = pow(litSide, 1.6) * exp(-groundHeight * groundHeight);
  scene += light.y * sun * flank * vec3(.075, .033, .014);

  if (p.y < .568) {
    float skyMatte = smoothstep(.495, .61, luma);
    float horizon = smoothstep(.05, .56, p.y);
    vec3 aerial = mix(sky, horizonColor, horizon * horizon);
    float elevation = (p.y - .54) * 3.45;
    float warmth = exp(-elevation * elevation) * (.36 + .64 * litSide);
    aerial = mix(aerial, vec3(1., .63, .41), warmth * light.y * sun * .68);
    if (light.x > 0.) {
      vec2 where = vec2(p.x, p.y + .17);
      vec3 galaxy = texture2D(stars, where).rgb;
      float starlight = dot(galaxy, vec3(.24, .68, .08));
      float seed = hash(floor(where * vec2(470., 765.)));
      float twinkle = .86 + .14 * sin(tau * (phase * (2. + floor(seed * 4.)) + seed));
      float dust = pow(max(0., starlight - .029), 1.14);
      float points = smoothstep(.34, .83, starlight) * twinkle;
      float clarity = 1. - smoothstep(.22, .57, cover);
      vec2 field = where * vec2(23., 41.);
      vec2 cell = floor(field);
      float id = hash(cell);
      vec2 center = vec2(hash(cell + 14.4), hash(cell + 27.3)) * .72 + .14;
      vec2 delta = fract(field) - center;
      float core = exp(-dot(delta, delta) * mix(600., 255., id));
      float scintillation = .77 + .23 * sin(tau * (phase * (2. + floor(id * 5.)) + id));
      float pinpricks = step(.79, id) * core * scintillation * mix(.14, .39, id);
      aerial += vec3(.65, .74, .90) * (dust * .53 + points * .29 + pinpricks) * light.x * clarity * (1. - .87 * horizon * horizon);
    }
    aerial *= mix(.96, 1.04, opening);
    if (cover > 0.) {
      float sheet = vapor(p * vec2(2.9, 9. + 9. * (1. - cover)), 1.9 + air.x * 1.5, 6.7);
      float relief = smoothstep(.17, .86, sheet) * (.24 + .76 * (1. - horizon));
      vec3 ceiling = mix(vec3(.28, .34, .40), mist, .48 + .35 * horizon);
      // Cloud undersides only catch as much light as the sky has: dim at dusk, near black at night.
      float skyLight = smoothstep(.09, .55, dot(sky, vec3(.333)));
      ceiling *= mix(1.05, .67, air.w) * mix(.3, 1., skyLight);
      aerial = mix(aerial, ceiling * (.79 + .32 * relief), cover * mix(.65, .98, relief));
    }
    scene = mix(scene, aerial, skyMatte);
  }

  if (p.y > .537 && p.y < .8) {
    float terrain = smoothstep(.115, .265, luma) * (1. - smoothstep(.52, .65, luma));
    float motion = .77 + air.x * .24;
    float depth = .37 * bank(p, .572, .015, 2.7, 2.8 * motion) + .49 * bank(p, .63, .025, 6.1, 3.6 * motion) + .44 * bank(p, .706, .026, 9.4, 4.9 * motion);
    float strength = min(.77, depth * terrain * air.y * mix(1., .69, opening));
    scene = mix(scene, mist * mix(1., .76, cover), strength);
  }

  if (flash > .001) {
    float dist = length((p - vec2(.77, .31)) * vec2(1., 1.68));
    scene += flash * exp(-dist * dist * 8.) * vec3(.12, .14, .19);
  }

  if (sunbeam > .001) {
    // Light breaks through a cloud gap on the right and fans into the valley mist.
    vec2 source = vec2(.84, .257);
    vec2 d = p - source;
    float skyMask = (1. - smoothstep(.548, .577, p.y)) * smoothstep(.47, .63, luma);
    vec2 q = p * vec2(3.9, 11.8) + vec2(-phase * .36, .17);
    float cloud = .59 * noise(q) + .27 * noise(q * 2.1) + .14 * noise(q * 4.9);
    float edge = -d.y + .023 + .046 * (cloud - .48) - .025 * sin(p.x * 8.5);
    float gap = exp(-dot(d * vec2(5.9, 18.), d * vec2(5.9, 18.)));
    float thickness = smoothstep(-.022, .068, edge + .060 * cloud) * (1. - gap * .90);
    float cloudBand = (1. - smoothstep(.36, .47, p.y)) * smoothstep(.27, .67, cloud);
    vec3 underside = mix(vec3(.27, .315, .39), vec3(.49, .53, .56), cloud);
    scene = mix(scene, underside, sunbeam * skyMask * cloudBand * thickness * .63);
    float rim = exp(-abs(edge) * 45.) * gap * skyMask;
    scene += sunbeam * (.095 * rim + .058 * gap * skyMask) * vec3(1., .77, .50);
    float fall = max(0., d.y);
    float fan = d.x + .91 * fall;
    float feather = .023 + .112 * fall;
    float wide = fan / feather;
    float left = (fan + .041 + .046 * fall) / (feather * .53);
    float right = (fan - .034 - .035 * fall) / (feather * .37);
    float rays = .54 * exp(-wide * wide) + .49 * exp(-left * left) + .36 * exp(-right * right);
    float farHaze = smoothstep(.01, .105, fall) * exp(-2.2 * fall) * (1. - thickness * .78);
    float valleyDepth = (p.y - .656) / .058;
    float fog = exp(-valleyDepth * valleyDepth) * smoothstep(.11, .36, luma);
    float textureMix = .58 + .42 * noise(p * vec2(8., 31.) + vec2(-phase * .42, 0.));
    float scatter = sunbeam * rays * textureMix * (.091 * farHaze * skyMask + .26 * fog);
    scene += scatter * vec3(1., .79, .52);
    float pool = (p.x - .47) / .27;
    scene += sunbeam * exp(-pool * pool) * fog * vec3(.047, .038, .027);
  }

  if (rainbow > .001 && p.y < .70) {
    // Scattered color adds light without replacing the scene behind the bow.
    vec2 d = (p - vec2(.64, .708)) * vec2(1., 1.7777778);
    float radius = length(d), band = (radius - .538) / .020;
    float red = exp(-pow(abs(band - .78) * 2.55, 2.));
    float yellow = exp(-pow(abs(band - .38) * 2.65, 2.));
    float green = exp(-pow(abs(band + .01) * 2.50, 2.));
    float blue = exp(-pow(abs(band + .45) * 2.30, 2.));
    float violet = exp(-pow(abs(band + .84) * 2.22, 2.));
    vec3 spectrum = vec3(.98, .13, .04) * red + vec3(.89, .69, .05) * yellow
      + vec3(.19, .73, .12) * green + vec3(.05, .34, .97) * blue + vec3(.37, .08, .70) * violet;
    float curtain = noise(p * vec2(4.8, 10.) + vec2(-phase * .19, .37));
    float rainTexture = .76 + .24 * noise(p * vec2(16., 31.) + vec2(-phase * .26, 0.));
    float uneven = smoothstep(-.54, -.15, d.x) * mix(.32, .87, smoothstep(.22, .70, curtain)) * rainTexture;
    float behind = smoothstep(.18, .43, luma) * (1. - smoothstep(.574, .674, p.y));
    scene += (1. - clamp(scene, 0., 1.)) * spectrum * rainbow * uneven * behind * .42;
  }

  gl_FragColor = vec4(scene * (1. - dim), 1.);
}
