// Overlay geometry is authored in plate units: x 0..1, y 0..plateAspect, y down.
attribute vec2 position;
attribute vec4 color;
attribute vec3 shape;
uniform vec2 cropOffset;
uniform vec2 cropScale;
uniform float plateAspect;
varying vec4 vColor;
varying vec3 vShape;

void main() {
  vec2 plateUv = vec2(position.x, position.y / plateAspect);
  vec2 screen = (plateUv - cropOffset) / cropScale;
  gl_Position = vec4(screen.x * 2. - 1., 1. - screen.y * 2., 0., 1.);
  vColor = color;
  vShape = shape;
}
