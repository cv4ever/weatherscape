// Rock Jetty: a stabilised, looping video of the sea and rocks under the drawn sky from sky.glsl.
// The program prepends the external-texture extension and sky.glsl.
uniform samplerExternalOES video;
// 1 over the sea, 0 over the rocks, in video coordinates (v from the top of the video).
uniform sampler2D seaMask;
uniform mat4 videoMatrix;
uniform float flash, dim, snowCover, reveal;
// wet: 1 while it rains, fading over the two hours after.
uniform float wet;
uniform vec3 ground;
uniform vec4 season;

// Plate layout: the video fills the bottom of a 9:16 plate; its horizon sits at horizonLine.
const float videoTop = .63222;
const float horizonLine = .64087;
// The shared sky is drawn for a horizon at .568, Pine Valley's ridge line.
const float skyShift = horizonLine - .568;
// Half a plate pixel, to antialias the horizon.
const float edge = .0006;
// The sea's average colour in the video, with the sun glints taken out.
const vec3 seaTone = vec3(.515, .671, .787);

void main() {
  vec2 p = uv;
  float cover = air.z;
  float sun = sunThrough();
  // Haze over the water, thickest at the horizon; an unlock thins it for a moment.
  float haze = clamp(air.y * .75 + air.w * .35, 0., .85) * (1. - .45 * reveal);
  vec3 hazeColor = mist * mix(1., .76, cover) + vec3(.05, .06, .08) * moonGlow;

  vec3 aerial = vec3(0.);
  if (p.y < horizonLine + edge) {
    aerial = skyAt(vec2(p.x, p.y - skyShift));
    float above = (horizonLine - p.y) / horizonLine;
    aerial = mix(aerial, hazeColor, haze * exp(-above * 14.) * .7);
  }
  if (p.y < horizonLine - edge) {
    aerial += flash * vec3(.12, .14, .19) * (1. - p.y);
    gl_FragColor = vec4(aerial * (1. - dim), 1.);
    return;
  }

  float v = (p.y - videoTop) / (1. - videoTop);
  vec3 base = texture2D(video, (videoMatrix * vec4(p.x, 1. - v, 0., 1.)).xy).rgb;
  float sea = texture2D(seaMask, vec2(p.x, v)).r;
  float luma = dot(base, vec3(.2126, .7152, .0722));
  // Distance below the horizon, 0 there and 1 at the bottom of the plate.
  float depth = (p.y - horizonLine) / (1. - horizonLine);

  // Sun glints are the brightest few percent of the water; split them off so cloud and night can take them away.
  float glint = smoothstep(.8, .97, luma) * sea;
  vec3 body = mix(base, seaTone * (.85 + .3 * depth), glint);
  float bodyLuma = dot(body, vec3(.2126, .7152, .0722));
  vec3 scene = body * ground * mix(.97, 1.035, opening()) * mix(1., .62, cover);
  scene = mix(scene, vec3(bodyLuma) * ground * mix(1., .62, cover), cover * .36);

  // Water mirrors the sky, most of all towards the horizon and under cloud.
  float skyLight = smoothstep(.09, .55, dot(sky, vec3(.333)));
  vec3 reflected = mix(mix(sky, horizonColor, .65), mist * mix(.12, 1., skyLight) * .8, cover);
  float fresnel = pow(1. - depth, 4.);
  scene = mix(scene, reflected, sea * clamp(fresnel * .55 + cover * .3 + light.x * .6, 0., .9));

  // The glitter returns where light reaches the water: the sun by day, a path under the moon at night.
  float sunGlitter = sun * sun * (1. - light.x) * smoothstep(.15, .5, ground.g);
  vec2 toMoon = vec2((p.x - moon.x) / (.05 + .2 * depth), 0.);
  float moonPath = exp(-dot(toMoon, toMoon)) * moonGlow * moon.w * (1. - cover);
  vec3 glitterColor = mix(vec3(1., .97, .9), vec3(1., .72, .45), light.y) * sunGlitter + vec3(.75, .8, .9) * moonPath;
  scene += glint * glitterColor * .9;

  // Rocks: warm low sun on the lit side, rain darkening, frost or snow, season light, moonlight at night.
  float rock = 1. - sea;
  scene += rock * light.y * sun * pow(litSide(p), 1.6) * vec3(.075, .033, .014) * 1.5;
  scene *= mix(1., mix(1., .6, wet), rock);
  scene += rock * wet * smoothstep(.45, .85, luma) * reflected * .06;
  scene = mix(scene, scene * season.rgb, rock * .6);
  if (snowCover > .001) {
    float settle = smoothstep(.3, .7, luma) * (.55 + .45 * noise(p * vec2(160., 280.)));
    vec3 snow = vec3(.86, .9, .96) * ground * mix(1., .7, cover);
    scene = mix(scene, snow, rock * snowCover * settle * .8);
  }
  // At night the pale rocks and water would glow under the plate's ground light: sink them, and let
  // the moon give a faint cool light back.
  scene *= mix(1., .7, light.x);
  scene += base * vec3(.06, .075, .11) * moonGlow * light.x;

  scene = mix(scene, hazeColor, haze * exp(-depth * 3.5) * mix(1., .5, rock));
  scene += flash * vec3(.07, .08, .11) * (1. - depth);

  // A one-pixel blend into the drawn sky keeps the horizon crisp without stair steps.
  scene = mix(aerial, scene, smoothstep(horizonLine - edge, horizonLine + edge, p.y));
  gl_FragColor = vec4(scene * (1. - dim), 1.);
}
