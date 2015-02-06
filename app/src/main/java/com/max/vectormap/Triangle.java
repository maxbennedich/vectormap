package com.max.vectormap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import android.opengl.GLES20;
import android.util.Log;

/**
 * A two-dimensional triangle for use as a drawn object in OpenGL ES 2.0.
 */
public class Triangle {

    private static final String VERTEX_SHADER =
            // This matrix member variable provides a hook to manipulate
            // the coordinates of the objects that use this vertex shader
                    "uniform mat4 uMVPMatrix;" +
                    "uniform float blend;" +
                    "attribute vec4 vPosition;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  vColor = vec4(1., 256., 65536., 0) * vPosition.z;" +
                    "  vColor = fract(vColor);" +
                    "  vColor -= vColor.yzww * vec4(1./256., 1./256., 0, 0);" +
                    "  vColor.a = blend;" +
                    "  vec4 vNewPos = vec4(vPosition.x, vPosition.y, 0, 1);" +
                    // the matrix must be included as a modifier of gl_Position
                    // Note that the uMVPMatrix factor *must be first* in order
                    // for the matrix multiplication product to be correct.
                    "  gl_Position = uMVPMatrix * vNewPos;" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
//                    "  gl_FragColor = vec4(vColor.r, vColor.g, vColor.b, blend);" +
                    "}";

    private final int mProgram;
    private int mMVPMatrixHandle;

    private static final int BYTES_IN_FLOAT = Float.SIZE/Byte.SIZE;
    private static final int BYTES_IN_INT = Integer.SIZE/Byte.SIZE;

    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 3;
    private final int vertexCount;
    private final int vertexStride = COORDS_PER_VERTEX * BYTES_IN_FLOAT;

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

    private static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

    final int[] vbo = new int[1];

    final int[] iboWireframe = new int[1];

    private static boolean WIREFRAME = false;

    /** Sets up the drawing object data for use in an OpenGL ES context. */
    public Triangle(float[] verts) {
        vertexCount = verts.length / COORDS_PER_VERTEX;

        for (int k = 2; k < verts.length; k += 3) {
            int rgb = COLORS_INT[(int) verts[k]];
            int r = rgb>>16, g = (rgb>>8)&0xff, b = rgb&0xff;
            verts[k] = r/256.0f + g/65536.0f + b/16777216.0f;
        }

        // initialize vertex byte buffer
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(verts.length * BYTES_IN_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(verts).position(0);

        GLES20.glGenBuffers(1, vbo, 0);
        if (vbo[0] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * BYTES_IN_FLOAT, vertexBuffer, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: " + vbo[0]);
        }

        if (WIREFRAME) {
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
        }

        // prepare shaders and OpenGL program
        int vertexShader = CustomGLRenderer.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = CustomGLRenderer.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        Log.i("PerfLog", String.format("Loaded %d tris, %d verts", verts.length/9, verts.length/3));
    }

    /** @param mvpMatrix - The Model View Project matrix in which to draw this shape. */
    public void draw(float[] mvpMatrix, float blend) {
        GLES20.glUseProgram(mProgram);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);

        // get handle to vertex shader's vPosition member
        int mPositionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");

        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, vertexStride, 0);

        // set blend uniform in shader
        int mBlendHandle = GLES20.glGetUniformLocation(mProgram, "blend");
        GLES20.glUniform1f(mBlendHandle, blend);

        // get handle to shape's transformation matrix
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        CustomGLRenderer.checkGlError("glGetUniformLocation");

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        CustomGLRenderer.checkGlError("glUniformMatrix4fv");

        // Draw all triangles
        // draw triangles (wireframes)
//        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount);
        if (WIREFRAME) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboWireframe[0]);
            GLES20.glDrawElements(GLES20.GL_LINES, vertexCount * 2, GLES20.GL_UNSIGNED_INT, 0);
        } else {
            // fill triangles
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
        }

//        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawListCount, GLES20.GL_UNSIGNED_INT, indexBuffer);
//        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0]);
//        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, 0);

//        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
//        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

//        GLES20.glDisableVertexAttribArray(mPositionHandle);
    }
}
