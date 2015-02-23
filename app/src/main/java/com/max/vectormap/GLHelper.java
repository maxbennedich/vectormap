package com.max.vectormap;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class GLHelper {

    /** Utility method for debugging OpenGL calls. If the operation is not successful,
     * the check throws an error. */
    public static void checkGlError() {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR)
            throw new RuntimeException("GL Error " + error);
    }

    /** @return GPU memory used in bytes. */
    public static int createVertexBuffer(float[] verts, int[] vbo) {
        int vertexSize = verts.length * Constants.BYTES_IN_FLOAT;
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(verts).position(0);

        GLES20.glGenBuffers(1, vbo, 0);
        if (vbo[0] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * Constants.BYTES_IN_FLOAT, vertexBuffer, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: "+vbo[0]);
        }

        return vertexSize;
    }

    /** @return GPU memory used in bytes. */
    public static int createVertexIndexBuffer(short[] idx, int[] ibo, int ofs) {
        int indexSize = idx.length * Constants.BYTES_IN_SHORT;
        ShortBuffer indexBuffer = ByteBuffer.allocateDirect(indexSize).order(ByteOrder.nativeOrder()).asShortBuffer();
        indexBuffer.put(idx).position(0);

        GLES20.glGenBuffers(1, ibo, ofs);
        if (ibo[ofs] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[ofs]);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.capacity() * Constants.BYTES_IN_SHORT, indexBuffer, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: " + ibo[ofs]);
        }

        return indexSize;
    }
}
