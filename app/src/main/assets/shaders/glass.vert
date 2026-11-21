// position: screen x, y in 0..1 with y down; local: -1..1 across the drop.
attribute vec2 position;
attribute vec2 local;
varying vec2 vUv;
varying vec2 vLocal;

void main() {
  // The scene copy comes from the framebuffer, whose origin is bottom-left.
  vUv = vec2(position.x, 1. - position.y);
  vLocal = local;
  gl_Position = vec4(position.x * 2. - 1., 1. - position.y * 2., 0., 1.);
}
