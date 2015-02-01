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
                    "attribute vec4 vPosition;" +
                    "void main() {" +
                    // the matrix must be included as a modifier of gl_Position
                    // Note that the uMVPMatrix factor *must be first* in order
                    // for the matrix multiplication product to be correct.
                    "  gl_Position = uMVPMatrix * vPosition;" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
                    "uniform vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}";

    private final FloatBuffer vertexBuffer;
    private final IntBuffer indexBuffer;

    private final int mProgram;
    private int mPositionHandle;
    private int mColorHandle;
    private int mMVPMatrixHandle;

    private static final int BYTES_IN_FLOAT = Float.SIZE/Byte.SIZE;
    private static final int BYTES_IN_INT = Integer.SIZE/Byte.SIZE;

    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 3;
    private final int vertexCount;
    private final int vertexStride = COORDS_PER_VERTEX * BYTES_IN_FLOAT;
    private final int indexCount;

    float color[];

    private final static float COLORS_DEBUG[][] = {
            rgb(0x3f5fff), // water
            rgb(0xf5e1d3), // urban
            rgb(0xf0f0f0), // industrial
            rgb(0xffff00), // farmland
            rgb(0xff0000), // open_land
            rgb(0xffffff), // mountain
            rgb(0x00ff00), // forest
            rgb(0x7f7f7f), // unmapped
            rgb(0xbfbfbf), // unclassified
            rgb(0x7f7f7f), // unspecified
    };

    private final static float COLORS[][] = {
            rgb(0xb9dcff), // water
            rgb(0xf5e1d3), // urban
            rgb(0xf0f0f0), // industrial
            rgb(0xe2e8dc), // farmland
            rgb(0xeceee3), // open_land
            rgb(0xffffff), // mountain
            rgb(0xd2e5c9), // forest
            rgb(0x7f7f7f), // unmapped
            rgb(0xbfbfbf), // unclassified
            rgb(0x7f7f7f), // unspecified
    };

    private static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

    final int[] vbo = new int[1];
    final int[] ibo = new int[1];

    /** Sets up the drawing object data for use in an OpenGL ES context. */
    public Triangle(float[] verts, int[] tris, int surfaceType) {
        vertexCount = verts.length / COORDS_PER_VERTEX;
        indexCount = tris.length;

        color = COLORS[surfaceType];

        // initialize vertex byte buffer
        vertexBuffer = ByteBuffer.allocateDirect(verts.length * BYTES_IN_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(verts).position(0);

        // initialize vertex index byte buffer
        indexBuffer = ByteBuffer.allocateDirect(tris.length * BYTES_IN_INT).order(ByteOrder.nativeOrder()).asIntBuffer();
        indexBuffer.put(tris).position(0);

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

        // prepare shaders and OpenGL program
        int vertexShader = CustomGLRenderer.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = CustomGLRenderer.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        Log.i("PerfLog", String.format("Type %d: %d tris, %d verts", surfaceType, indexCount/3, vertexCount));
    }

    /** @param mvpMatrix - The Model View Project matrix in which to draw this shape. */
    public void draw(float[] mvpMatrix) {
        GLES20.glUseProgram(mProgram);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);

        // get handle to vertex shader's vPosition member
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");

        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, vertexStride, 0);

        // get handle to fragment shader's vColor member
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor");

        // Set color for drawing the triangle
        GLES20.glUniform4fv(mColorHandle, 1, color, 0);

        // get handle to shape's transformation matrix
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        CustomGLRenderer.checkGlError("glGetUniformLocation");

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        CustomGLRenderer.checkGlError("glUniformMatrix4fv");

        // Draw all triangles
//        GLES20.glDrawElements(GLES20.GL_TRIANGLES, drawListCount, GLES20.GL_UNSIGNED_INT, indexBuffer);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[0]);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);

//        GLES20.glDisableVertexAttribArray(mPositionHandle);
    }
}
