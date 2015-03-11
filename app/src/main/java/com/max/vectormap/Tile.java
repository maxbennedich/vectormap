package com.max.vectormap;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.opengl.GLES20;
import android.util.Log;
import android.util.Pair;

/**
 * Class responsible for rendering a tile consisting of many triangles.
 */
public class Tile {

    final int size;
    final int tx, ty;

    /** Bytes currently loaded into GPU memory. */
    public static int gpuBytes = 0;

    private int tileGpuBytes;

    private boolean loadedToGL = false;

    final int[] vbo = new int[1];

    // per surface type data
    private final int[] ibo;
    private final int vertexCount;
    private final int[] indexCount;
    private final float[][] color;

    private static final int COORDS_PER_VERTEX = 2;

    static class ClaimableBuffer<B extends Buffer> {
        B buffer;
        boolean claimed;

        ClaimableBuffer(B buffer, boolean claimed) {
            this.buffer = buffer;
            this.claimed = claimed;
        }
    }

    ClaimableBuffer<FloatBuffer> tmpVertexBuffer;
    ClaimableBuffer<ShortBuffer>[] tmpIndexBuffers;

    static List<ClaimableBuffer<FloatBuffer>> vertexBuffers = new ArrayList<>();
    static List<ClaimableBuffer<ShortBuffer>> indexBuffers = new ArrayList<>();

    private static final Object VERTEX_BUFFER_LOCK = new Object();

    // TODO share with method below for index buffer
    static ClaimableBuffer<FloatBuffer> getFreeVertexBuffer(int elements) {
        synchronized (VERTEX_BUFFER_LOCK) {
            int insertionIdx = -1;
            for (int k = 0; k < vertexBuffers.size(); ++k) {
                if (elements > vertexBuffers.get(k).buffer.capacity())
                    continue; // buffer is too small

                if (insertionIdx == -1)
                    insertionIdx = k; // insert new buffer at this position, if needed

                if (!vertexBuffers.get(k).claimed) {
                    vertexBuffers.get(k).claimed = true;
                    return vertexBuffers.get(k);
                }
            }

            // insert new buffer
            ClaimableBuffer<FloatBuffer> newBuffer = new ClaimableBuffer<>(
                    ByteBuffer.allocateDirect(elements * Constants.BYTES_IN_FLOAT).order(ByteOrder.nativeOrder()).asFloatBuffer(), true);
            vertexBuffers.add(insertionIdx == -1 ? vertexBuffers.size() : insertionIdx, newBuffer);
            Log.d("TileCache", "Created new vertex buffer at position "+insertionIdx+"/"+vertexBuffers.size()+": "+((elements*Constants.BYTES_IN_FLOAT+512)/1024)+" kb");
            return newBuffer;
        }
    }

    private static final Object INDEX_BUFFER_LOCK = new Object();

    static ClaimableBuffer<ShortBuffer> getFreeIndexBuffer(int elements) {
        synchronized (INDEX_BUFFER_LOCK) {
            int insertionIdx = -1;
            for (int k = 0; k < indexBuffers.size(); ++k) {
                if (elements > indexBuffers.get(k).buffer.capacity())
                    continue; // buffer is too small

                if (insertionIdx == -1)
                    insertionIdx = k; // insert new buffer at this position, if needed

                if (!indexBuffers.get(k).claimed) {
                    indexBuffers.get(k).claimed = true;
                    return indexBuffers.get(k);
                }
            }

            // insert new buffer
            ClaimableBuffer<ShortBuffer> newBuffer = new ClaimableBuffer<>(
                    ByteBuffer.allocateDirect(elements * Constants.BYTES_IN_SHORT).order(ByteOrder.nativeOrder()).asShortBuffer(), true);
            indexBuffers.add(insertionIdx == -1 ? indexBuffers.size() : insertionIdx, newBuffer);
            Log.d("TileCache", "Created new index buffer at position "+insertionIdx+"/"+indexBuffers.size()+": "+((elements*Constants.BYTES_IN_SHORT+512)/1024)+" kb");
            return newBuffer;
        }
    }

    /**
     * Puts data in appropriate buffers for future loading to GL. This method is GL agnostic and
     * does therefore not need to be called in the GL thread.
     * NOTE: This method is accessed by multiple threads (loading thread and GL thread).
     */
    public Tile(int size, int tx, int ty, float[] verts, int vertexCount, Map<Integer, Pair<short[], Integer>> trisByType) {
        this.size = size;
        this.tx = tx;
        this.ty = ty;

        this.vertexCount = vertexCount;

        tmpVertexBuffer = getFreeVertexBuffer(vertexCount * 2);
        tmpVertexBuffer.buffer.put(verts, 0, vertexCount * 2).position(0);

        ibo = new int[trisByType.size()];
        color = new float[trisByType.size()][];
        indexCount = new int[trisByType.size()];
        tmpIndexBuffers = new ClaimableBuffer[trisByType.size()];

        // create an index array for each surface type (color)
        int type = 0;
        for (Map.Entry<Integer, Pair<short[], Integer>> tris : trisByType.entrySet()) {
            indexCount[type] = tris.getValue().second;

            color[type] = Common.rgb(Constants.COLORS_NEW[tris.getKey()]);
//          color[0]/=2; color[1]/=2; color[2]/=2; // for testing overdraw

            tmpIndexBuffers[type] = getFreeIndexBuffer(indexCount[type]);
            tmpIndexBuffers[type].buffer.put(tris.getValue().first, 0, indexCount[type]).position(0);

            ++type;
        }

        Log.i("PerfLog", String.format("Loaded %d tris, %d verts", vertexCount / 6, vertexCount / 2));
    }

    /** Must be executed in GL thread. */
    private void loadToGL() {
        GLES20.glGenBuffers(1, vbo, 0);
        int bytes = vertexCount * 2 * Constants.BYTES_IN_FLOAT;
        if (vbo[0] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bytes, tmpVertexBuffer.buffer, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: "+vbo[0]);
        }
        tileGpuBytes = bytes;
        tmpVertexBuffer.claimed = false;

        for (int t = 0; t < tmpIndexBuffers.length; ++t) {
            GLES20.glGenBuffers(1, ibo, t);
            bytes = indexCount[t] * Constants.BYTES_IN_SHORT;
            if (ibo[t] > 0) {
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[t]);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, bytes, tmpIndexBuffers[t].buffer, GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            } else {
                throw new RuntimeException("Buffer error: " + ibo[t]);
            }
            tileGpuBytes += bytes;
            tmpIndexBuffers[t].claimed = false;
        }

        gpuBytes += tileGpuBytes;
//        Log.d("TileCache", "LOAD TO GL: " + tileGpuBytes + " bytes");

        loadedToGL = true;
    }

    /** Release any memory held by this tile, either in buffer or in GL. Must be run in GL thread. */
    public void delete() {
        if (!loadedToGL) {
            tmpVertexBuffer.claimed = false;
            for (int t = 0; t < tmpIndexBuffers.length; ++t)
                tmpIndexBuffers[t].claimed = false;
        } else {
            gpuBytes -= tileGpuBytes;
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
}
