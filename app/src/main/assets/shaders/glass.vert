// position: screen x, y in 0..1 with y down; local: -1..1 across the drop;
// shape: kind (0 drop, 1 splash), splash age 0..1, seed.
attribute vec2 position;
attribute vec2 local;
attribute vec3 shape;
varying vec2 vUv;
varying vec2 vLocal;
varying vec3 vShape;

void main() {
  // The scene copy comes from the framebuffer, whose origin is bottom-left.
  vUv = vec2(position.x, 1. - position.y);
  vLocal = local;
  vShape = shape;
  gl_Position = vec4(position.x * 2. - 1., 1. - position.y * 2., 0., 1.);
}
