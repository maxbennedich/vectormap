package com.max.vectormap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import android.opengl.GLES20;
import android.util.Log;

/**
 * Class responsible for rendering a tile consisting of many triangles.
 */
public class TileRenderer {

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
            "attribute vec4 vPosition;" +
            "void main() {" +
            "  gl_Position = uMVPMatrix * vPosition;" +
//            "  gl_PointSize = 16.;" +
            "}";

    private static final String FRAGMENT_SHADER =
            "precision lowp float;" +
            "uniform vec4 vColor;" +
            "void main() {" +
            "  gl_FragColor = vColor;" +
            "}";

    private final int mainProgram; //, pointProgram;
    private int mMVPMatrixHandle;

    private static final int BYTES_IN_FLOAT = Float.SIZE/Byte.SIZE;
    private static final int BYTES_IN_INT = Integer.SIZE/Byte.SIZE;

    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 2;
    private final int indexCount;

    private final int gpuBytes;

    float color[];

    private static int[] COLORS_DEBUG_INT = {
            0x3f5fff, // water
            0xf5e1d3, // urban
            0xf0f0f0, // industrial
            0xffff00, // farmland
            0xff0000, // open_land
            0xffffff, // mountain
            0x00ff00, // forest
            0x7f7f7f, // unmapped
            0xbfbfbf, // unclassified
            0x7f7f7f, // unspecified
    };

    private static int[] COLORS_INT = {
            0xb9dcff, // water
            0xf5e1d3, // urban
            0xf0f0f0, // industrial
            0xe2e8dc, // farmland
            0xeceee3, // open_land
            0xffffff, // mountain
            0xd2e5c9, // forest
            0x7f7f7f, // unmapped
            0xbfbfbf, // unclassified
            0x7f7f7f, // unspecified
    };

    private static int[] COLORS_NEW = {
            0xb9dcff, // water
            0xfad999, // urban
            0xdcddc5, // industrial
            0xfff7a6, // farmland
            0xffffe0, // open_land
            0xffffff, // mountain
            0xc2e6a2, // forest
            0x7f7f7f, // unmapped
            0xbfbfbf, // unclassified
            0x7f7f7f, // unspecified
    };

    private static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

    final int[] vbo = new int[1];
    final int[] ibo = new int[1];

//    final int[] iboWireframe = new int[1];
//
//    private static boolean DRAW_WIREFRAME = false;
//    private static boolean DRAW_VERTICES = false;

    /** Sets up the drawing object data for use in an OpenGL ES context. */
    public TileRenderer(float[] verts, int[] tris, int surfaceType) {
        indexCount = tris.length;

        color = rgb(COLORS_NEW[surfaceType]);
//        color[0]/=2; color[1]/=2; color[2]/=2; // for testing overdraw

//        for (int k = 2; k < verts.length; k += 3) {
//            int rgb = COLORS_INT[(int) verts[k]];
//            int r = rgb>>16, g = (rgb>>8)&0xff, b = rgb&0xff;
////            r/=2; g/=2; b/=2;
//            verts[k] = r/256.0f + g/65536.0f + b/16777216.0f;
//        }

        // initialize vertex byte buffer
        int vertexSize = verts.length * BYTES_IN_FLOAT;
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(verts).position(0);

        // initialize vertex index byte buffer
        int indexSize = tris.length * BYTES_IN_INT;
        IntBuffer indexBuffer = ByteBuffer.allocateDirect(indexSize).order(ByteOrder.nativeOrder()).asIntBuffer();
        indexBuffer.put(tris).position(0);

        gpuBytes = vertexSize + indexSize;

        GLES20.glGenBuffers(1, vbo, 0);
        GLES20.glGenBuffers(1, ibo, 0);
        if (vbo[0] > 0 && ibo[0] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * BYTES_IN_FLOAT, vertexBuffer, GLES20.GL_STATIC_DRAW);

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0]);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.capacity() * BYTES_IN_INT, indexBuffer, GLES20.GL_STATIC_DRAW);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: "+vbo[0]+","+ibo[0]);
        }

/*        if (DRAW_WIREFRAME) {
            int[] indices = new int[vertexCount * 2];
            for (int t = 0; t < vertexCount; t += 3) {
                indices[t*2] = indices[t*2+5] = t;
                indices[t*2+1] = indices[t*2+2] = t+1;
                indices[t*2+3] = indices[t*2+4] = t+2;
            }
            IntBuffer indexBuffer = ByteBuffer.allocateDirect(vertexCount * 2 * BYTES_IN_INT).order(ByteOrder.nativeOrder()).asIntBuffer();
            indexBuffer.put(indices).position(0);

            GLES20.glGenBuffers(1, iboWireframe, 0);
            if (iboWireframe[0] > 0) {
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboWireframe[0]);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.capacity() * BYTES_IN_INT, indexBuffer, GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            } else {
                throw new RuntimeException("Buffer error: " + iboWireframe[0]);
            }
        }*/

        // prepare shaders and OpenGL program
        int vertexShader = VectorMapRenderer.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = VectorMapRenderer.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mainProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mainProgram, vertexShader);
        GLES20.glAttachShader(mainProgram, fragmentShader);
        GLES20.glLinkProgram(mainProgram);

/*        if (DRAW_VERTICES) {
            int vertexShaderPoint = CustomGLRenderer.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_POINT);
            pointProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(pointProgram, vertexShaderPoint);
            GLES20.glAttachShader(pointProgram, fragmentShader);
            GLES20.glLinkProgram(pointProgram);
        } else {
            pointProgram = -1;
        }*/

        Log.i("PerfLog", String.format("Loaded %d tris, %d verts", verts.length/9, verts.length/3));
    }

    /** Release memory held in opengl buffers. */
    public void delete() {
        GLES20.glDeleteBuffers(2, new int[] {vbo[0], ibo[0]}, 0);
    }

    /** @param mvpMatrix - The Model View Project matrix in which to draw this shape. */
    public void draw(float[] mvpMatrix, float blend) {
        prepareProgram(mainProgram, mvpMatrix, blend);

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0]);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, 0);

        // drawing vertices:
//        int mColorHandle = GLES20.glGetUniformLocation(mainProgram, "vColor");
//        GLES20.glUniform4fv(mColorHandle, 1, new float[] {1, 0, 0, 0}, 0);
//        GLES20.glDrawElements(GLES20.GL_POINTS, indexCount, GLES20.GL_UNSIGNED_INT, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

        // Draw all triangles
//        if (DRAW_WIREFRAME) {
//            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboWireframe[0]);
//            GLES20.glDrawElements(GLES20.GL_LINES, vertexCount * 2, GLES20.GL_UNSIGNED_INT, 0);
//        } else {
            // fill triangles
//            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
//        }
    }

    // TODO things started crashing in the method below (when accessing the program) when I started
    // to load tiles dynamically, i.e. not preload all the content
    private void prepareProgram(int program, float[] mvpMatrix, float blend) {
        GLES20.glUseProgram(program);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);

        // get handle to vertex shader's vPosition member
        int mPositionHandle = GLES20.glGetAttribLocation(program, "vPosition");

        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, 0);

        // get handle to fragment shader's vColor member and set color for all triangles
        int mColorHandle = GLES20.glGetUniformLocation(program, "vColor");
        color[3] = blend;
        GLES20.glUniform4fv(mColorHandle, 1, color, 0);

        // set blend uniform in shader
//        int mBlendHandle = GLES20.glGetUniformLocation(program, "blend");
//        GLES20.glUniform1f(mBlendHandle, blend);

        // get handle to shape's transformation matrix
        mMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        VectorMapRenderer.checkGlError("glGetUniformLocation");

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        VectorMapRenderer.checkGlError("glUniformMatrix4fv");
    }

    public int getBytesInGPU() {
        return gpuBytes;
    }
}
