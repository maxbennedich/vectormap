package com.max.vectormap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Map;

import android.opengl.GLES20;
import android.util.Log;

/**
 * Class responsible for rendering a tile consisting of many triangles.
 */
public class Tile {

    final int size;
    final int tx, ty;

    public static final Object LOCK = new Object();

    /** Bytes currently loaded into GPU memory. */
    public static int gpuBytes = 0;

    /** Bytes currently held in direct buffers (waiting to be loaded into GPU). */
    public static int bufferBytes;

    /** Bytes ever allocated in direct buffers. */
    public static int bufferBytesEverAllocated;

    private int tileGpuBytes;

    private boolean loadedToGL = false;

    final int[] vbo = new int[1];

    // per surface type data
    final int[] ibo;
    final int[] indexCount;
    final float[][] color;

    private static final int COORDS_PER_VERTEX = 2;

    private static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

    /**
     * Puts data in appropriate buffers for future loading to GL. This method is GL agnostic and
     * does therefore not need to be called in the GL thread.
     */
    public Tile(int size, int tx, int ty, float[] verts, Map<Integer, short[]> trisByType) {
        this.size = size;
        this.tx = tx;
        this.ty = ty;

//        tileGpuBytes = GLHelper.createVertexBuffer(verts, vbo);
        int vertexSize = verts.length * Constants.BYTES_IN_FLOAT;
        tmpVertexBuffer = ByteBuffer.allocateDirect(vertexSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tmpVertexBuffer.put(verts).position(0);
        synchronized (LOCK) {
            bufferBytes += vertexSize;
            bufferBytesEverAllocated += vertexSize;
        }

        ibo = new int[trisByType.size()];
        indexCount = new int[trisByType.size()];
        color = new float[trisByType.size()][];
        tmpIndexBuffers = new ShortBuffer[trisByType.size()];

        // create an index array for each surface type (color)
        int type = 0;
        for (Map.Entry<Integer, short[]> tris : trisByType.entrySet()) {
            indexCount[type] = tris.getValue().length;

            color[type] = rgb(Constants.COLORS_NEW[tris.getKey()]);
//          color[0]/=2; color[1]/=2; color[2]/=2; // for testing overdraw

//            tileGpuBytes += GLHelper.createVertexIndexBuffer(tris.getValue(), ibo, type);
            int indexSize = tris.getValue().length * Constants.BYTES_IN_SHORT;
            tmpIndexBuffers[type] = ByteBuffer.allocateDirect(indexSize).order(ByteOrder.nativeOrder()).asShortBuffer();
            tmpIndexBuffers[type].put(tris.getValue()).position(0);
            synchronized (LOCK) {
                bufferBytes += indexSize;
                bufferBytesEverAllocated += indexSize;
            }

            ++type;
        }

        Log.i("PerfLog", String.format("Loaded %d tris, %d verts", verts.length/9, verts.length/3));
    }

    FloatBuffer tmpVertexBuffer;
    ShortBuffer[] tmpIndexBuffers;

    /** Must be executed in GL thread. */
    private void loadToGL() {
        GLES20.glGenBuffers(1, vbo, 0);
        int bytes = tmpVertexBuffer.capacity() * Constants.BYTES_IN_FLOAT;
        if (vbo[0] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bytes, tmpVertexBuffer, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: "+vbo[0]);
        }
        tileGpuBytes = bytes;
        tmpVertexBuffer.limit(0);
        tmpVertexBuffer = null;

        for (int t = 0; t < tmpIndexBuffers.length; ++t) {
            GLES20.glGenBuffers(1, ibo, t);
            bytes = tmpIndexBuffers[t].capacity() * Constants.BYTES_IN_SHORT;
            if (ibo[t] > 0) {
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[t]);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, bytes, tmpIndexBuffers[t], GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            } else {
                throw new RuntimeException("Buffer error: " + ibo[t]);
            }
            tileGpuBytes += bytes;
            tmpIndexBuffers[t].limit(0);
            tmpIndexBuffers[t] = null;
        }

        synchronized (LOCK) {
            bufferBytes -= tileGpuBytes;
            gpuBytes += tileGpuBytes;
        }

        loadedToGL = true;
    }

    /** Release any memory held by this tile, either in buffer or in GL. Must be run in GL thread. */
    public void delete() {
        if (!loadedToGL) {
            int bytes = tmpVertexBuffer.capacity() * Constants.BYTES_IN_FLOAT;
            tmpVertexBuffer.limit(0);
            tmpVertexBuffer = null;

            for (int t = 0; t < tmpIndexBuffers.length; ++t) {
                bytes += tmpIndexBuffers[t].capacity() * Constants.BYTES_IN_SHORT;
                tmpIndexBuffers[t].limit(0);
                tmpIndexBuffers[t] = null;
            }

            synchronized (LOCK) {
                bufferBytes -= bytes;
            }
        } else {
            synchronized (LOCK) {
                gpuBytes -= tileGpuBytes;
            }

            GLES20.glDeleteBuffers(1, vbo, 0);
            GLES20.glDeleteBuffers(ibo.length, ibo, 0);
        }
    }

    public static int trisDrawn = 0;

    public void draw(int program, float blend) {
        if (!loadedToGL) {
            loadToGL();
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);

        // prepare vertex data
        int mPositionHandle = GLES20.glGetAttribLocation(program, "vPosition");
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, 0);

//        int mBlendHandle = GLES20.glGetUniformLocation(program, "blend");
//        GLES20.glUniform1f(mBlendHandle, blend);

        GLHelper.checkGlError();

        for (int t = 0; t < ibo.length; ++t) {
            int mColorHandle = GLES20.glGetUniformLocation(program, "vColor");
            color[t][3] = blend;
            GLES20.glUniform4fv(mColorHandle, 1, color[t], 0);

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[t]);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount[t], GLES20.GL_UNSIGNED_SHORT, 0);
            trisDrawn += indexCount[t]/3;
        }

        // drawing vertices:
//        int mColorHandle = GLES20.glGetUniformLocation(program, "vColor");
//        GLES20.glUniform4fv(mColorHandle, 1, new float[] {1, 0, 0, 0}, 0);
//        GLES20.glDrawElements(GLES20.GL_POINTS, indexCount, GLES20.GL_UNSIGNED_INT, 0);

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    public int getGPUBytes() {
        return tileGpuBytes;
    }
}
