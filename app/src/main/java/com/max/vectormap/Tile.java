package com.max.vectormap;

import java.util.Map;

import android.opengl.GLES20;
import android.util.Log;

/**
 * Class responsible for rendering a tile consisting of many triangles.
 */
public class Tile {

    final int size;
    final int tx, ty;

    private int gpuBytes;

    final int[] vbo = new int[1];

    // per surface type data
    final int[] ibo;
    final int[] indexCount;
    final float[][] color;

    private static final int COORDS_PER_VERTEX = 2;

    private static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

    /** Sets up the drawing object data for use in an OpenGL ES context. */
    public Tile(int size, int tx, int ty, float[] verts, Map<Integer, short[]> trisByType) {
        this.size = size;
        this.tx = tx;
        this.ty = ty;

        gpuBytes = GLHelper.createVertexBuffer(verts, vbo);

        ibo = new int[trisByType.size()];
        indexCount = new int[trisByType.size()];
        color = new float[trisByType.size()][];

        // create an index array for each surface type (color)
        int type = 0;
        for (Map.Entry<Integer, short[]> tris : trisByType.entrySet()) {
            indexCount[type] = tris.getValue().length;

            color[type] = rgb(Constants.COLORS_NEW[tris.getKey()]);
//          color[0]/=2; color[1]/=2; color[2]/=2; // for testing overdraw

            gpuBytes += GLHelper.createVertexIndexBuffer(tris.getValue(), ibo, type);

            ++type;
        }

        Log.i("PerfLog", String.format("Loaded %d tris, %d verts", verts.length/9, verts.length/3));
    }

    /** Release memory held in opengl buffers. */
    public void delete() {
        GLES20.glDeleteBuffers(1, vbo, 0);
        GLES20.glDeleteBuffers(ibo.length, ibo, 0);
    }

    public static int trisDrawn = 0;

    public void draw(int program, float blend) {
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
        return gpuBytes;
    }
}
