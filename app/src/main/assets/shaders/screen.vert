// A full-screen quad; uv has its origin bottom-left, like the framebuffer copy.
attribute vec2 corner;
varying vec2 vUv;

void main() {
  vUv = corner * .5 + .5;
  gl_Position = vec4(corner, 0., 1.);
}
