precision mediump float;

uniform sampler2D uTexture;

uniform vec4 vColor;

varying vec2 vTexCoordinate;

void main() {
  gl_FragColor = vColor * texture2D(uTexture, vTexCoordinate);
}
