uniform mat4 uMVPMatrix;

attribute vec2 aTexCoordinate; // Per-vertex texture coordinate information we will pass in.
attribute vec4 vPosition;

varying vec2 vTexCoordinate;   // This will be passed into the fragment shader.

void main() {
  // Pass through the texture coordinate.
  vTexCoordinate = aTexCoordinate;
 
  gl_Position = uMVPMatrix * vPosition;
}