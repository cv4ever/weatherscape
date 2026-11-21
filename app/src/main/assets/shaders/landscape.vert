attribute vec2 corner;
uniform vec2 cropOffset;
uniform vec2 cropScale;
varying vec2 uv;

void main() {
  vec2 screen = vec2(corner.x * .5 + .5, .5 - corner.y * .5);
  uv = cropOffset + screen * cropScale;
  gl_Position = vec4(corner, 0., 1.);
}
