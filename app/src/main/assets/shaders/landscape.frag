// Pine Valley. The sky above the ridges comes from sky.glsl, which the program prepends.
uniform sampler2D landscape, fogTexture;
uniform float gust, flash, sunbeam, rainbow, dim;
uniform vec3 ground;
// fogRoll: x advance 0..1, y strength.
uniform float snowCover, reveal;
uniform vec2 fogRoll;
// season: land colour multiplier and saturation.
uniform vec4 season;

// Horizontal displacement for one foreground tree crown; trunks stay anchored.
float breeze(vec2 p, float x, float tip, float root, float width, float lag) {
  float dx = (p.x - x) / width;
  float crown = exp(-dx * dx) * smoothstep(tip - .016, tip + .018, p.y);
  float bend = 1. - smoothstep(tip, root, p.y);
  float wave = .52 * sin(tau * phase * 3. - lag) + .23 * sin(tau * phase * 7. - lag * 1.7) + .25 * sin(tau * phase - .9);
  return crown * bend * bend * wave * gust;
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
  float opened = opening();
  float cover = air.z;
  float sun = sunThrough();
  float luma = dot(base, vec3(.2126, .7152, .0722));
  vec3 scene = base * ground * mix(.97, 1.035, opened) * mix(1., .62, cover);
  scene = mix(scene, vec3(luma) * ground * mix(1., .62, cover), cover * .36);
  float groundHeight = (p.y - .59) * 5.;
  float lit = litSide(p);
  float flank = pow(lit, 1.6) * exp(-groundHeight * groundHeight);
  scene += light.y * sun * flank * vec3(.075, .033, .014);
  // Moonlight: a cool, faint light that lets the ridges and treetops read at night.
  scene += base * vec3(.13, .16, .22) * moonGlow * light.x;
  // At night the eye reads shapes, not colour: trees sink towards black while the paler valley
  // floor and ridges keep a little light, so the silhouettes stay readable.
  scene *= mix(1., .35 + 1.25 * smoothstep(.04, .5, luma), light.x * .8);

  // Seasons tint the land only; the sky keeps its own light.
  float landLuma = dot(scene, vec3(.2126, .7152, .0722));
  scene = mix(vec3(landLuma), scene, season.w) * season.rgb;
  if (snowCover > .001) {
    // Snow settles on the brighter, upward-facing needles; distant ridges read as evenly white.
    float settle = smoothstep(.10, .32, luma) * (.55 + .45 * noise(p * vec2(160., 280.)));
    settle = mix(settle, .8, 1. - smoothstep(.58, .66, p.y));
    vec3 snow = vec3(.86, .9, .96) * ground * mix(1., .7, cover);
    scene = mix(scene, snow, snowCover * settle * .75);
  }

  if (p.y < .568) {
    float skyMatte = smoothstep(.495, .61, luma);
    scene = mix(scene, skyAt(p), skyMatte);
  }

  if (p.y > .537 && p.y < .8) {
    float terrain = smoothstep(.115, .265, luma) * (1. - smoothstep(.52, .65, luma));
    float motion = .77 + air.x * .24;
    float depth = .37 * bank(p, .572, .015, 2.7, 2.8 * motion) + .49 * bank(p, .63, .025, 6.1, 3.6 * motion) + .44 * bank(p, .706, .026, 9.4, 4.9 * motion);
    float strength = min(.77, depth * terrain * air.y * mix(1., .69, opened)) * (1. - .45 * reveal);
    vec3 fogColor = mist * mix(1., .76, cover) + vec3(.05, .06, .08) * moonGlow;
    scene = mix(scene, fogColor, strength);
    if (fogRoll.y > .001) {
      // A real fog bank, tiled along the valley, pours over the right-hand ridge and floods leftwards.
      float front = mix(1.18, -.25, fogRoll.x);
      float edgeNoise = .08 * (noise(vec2(p.y * 40., flow * .6)) - .5) + .05 * (noise(vec2(p.y * 90. + 5., flow)) - .5);
      float behind = smoothstep(front - .03, front + .16, p.x + edgeNoise);
      float top = .648 - .03 * smoothstep(.62, 1., p.x) + .008 * sin(p.x * 9. + flow * .4);
      float v = (p.y - top) / .11;
      if (v > 0. && v < 1.) {
        vec4 bank1 = texture2D(fogTexture, vec2(p.x * 1.4 - flow * .06, v * .7 + .15));
        vec4 bank2 = texture2D(fogTexture, vec2(p.x * 2.3 + .31 - flow * .1, v * .75 + .12));
        float density = max(bank1.a, bank2.a * .8) * smoothstep(0., .35, v) * (1. - smoothstep(.6, 1., v));
        // The bitmap is premultiplied; divide alpha back out to read the fog's own shading.
        float shade = .5 * (bank1.r / max(bank1.a, .02) + bank2.r / max(bank2.a, .02));
        float tone = mix(.88, 1.04, clamp(shade, 0., 1.));
        // A thin veil that thickens only in its core, so the valley stays readable through it.
        float roll = fogRoll.y * behind * density * density * (.35 + .65 * terrain) * .55;
        scene = mix(scene, fogColor * tone, min(.42, roll));
      }
    }
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
