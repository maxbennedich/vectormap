package com.max.vectormap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.opengl.GLES20;
import android.util.Log;
import android.util.Pair;

/**
 * Class responsible for rendering a tile consisting of many triangles.
 * 25 fps zoomed in
 * 20 fps middle
 * 16 fps zoomed out
 * -->
 * 27 fps zoomed in
 * 22 fps middle
 * 18 fps zoomed out
 * cache size: 69059 KB
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

    private final int mainProgram;
    private int mMVPMatrixHandle;

    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 2;

    private int gpuBytes;

    final int[] vbo = new int[1];

    // per surface type data
    final int[] ibo;
    final int[] indexCount;
    final float[][] color;

    private static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

    /** Sets up the drawing object data for use in an OpenGL ES context. */
    public TileRenderer(float[] verts, Map<Integer, int[]> trisByType) {
//        // reorder vertices and reindex index lists
//        Map<Pair<Float, Float>, Integer> idxByVert = new LinkedHashMap<>();
//        for (Map.Entry<Integer, int[]> tris : trisByType.entrySet()) {
//            for (int n = 0; n < tris.getValue().length; ++n) {
//                int vi = tris.getValue()[n];
//                Pair<Float, Float> v = Pair.create(verts[vi * 2], verts[vi * 2 + 1]);
//                Integer idx = idxByVert.get(v);
//                if (idx == null)
//                    idxByVert.put(v, idx = idxByVert.size());
//                tris.getValue()[n] = idx; // reindex
//            }
//        }
//        int vi = 0;
//        for (Pair<Float, Float> v : idxByVert.keySet()) {
//            verts[vi++] = v.first;
//            verts[vi++] = v.second;
//        }

        // initialize vertex byte buffer
        int vertexSize = verts.length * Constants.BYTES_IN_FLOAT;
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(vertexSize).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(verts).position(0);

        gpuBytes = vertexSize;

        GLES20.glGenBuffers(1, vbo, 0);
        if (vbo[0] > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexBuffer.capacity() * Constants.BYTES_IN_FLOAT, vertexBuffer, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        } else {
            throw new RuntimeException("Buffer error: "+vbo[0]);
        }

        ibo = new int[trisByType.size()];
        indexCount = new int[trisByType.size()];
        color = new float[trisByType.size()][];

        int type = 0;
        for (Map.Entry<Integer, int[]> tris : trisByType.entrySet()) {
            indexCount[type] = tris.getValue().length;

            color[type] = rgb(Constants.COLORS_NEW[tris.getKey()]);
//          color[0]/=2; color[1]/=2; color[2]/=2; // for testing overdraw

            // initialize vertex index byte buffer
            short[] newIdx = new short[tris.getValue().length];
            for (int k = 0; k < tris.getValue().length; ++k)
                newIdx[k] = (short)tris.getValue()[k];
            int indexSize = tris.getValue().length * Constants.BYTES_IN_SHORT;
            ShortBuffer indexBuffer = ByteBuffer.allocateDirect(indexSize).order(ByteOrder.nativeOrder()).asShortBuffer();
            indexBuffer.put(newIdx).position(0);

            gpuBytes += indexSize;

            GLES20.glGenBuffers(1, ibo, type);
            if (ibo[type] > 0) {
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[type]);
                GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer.capacity() * Constants.BYTES_IN_SHORT, indexBuffer, GLES20.GL_STATIC_DRAW);
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
            } else {
                throw new RuntimeException("Buffer error: " + ibo[type]);
            }

            ++type;
        }

        // prepare shaders and OpenGL program
        int vertexShader = VectorMapRenderer.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = VectorMapRenderer.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mainProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mainProgram, vertexShader);
        GLES20.glAttachShader(mainProgram, fragmentShader);
        GLES20.glLinkProgram(mainProgram);

        Log.i("PerfLog", String.format("Loaded %d tris, %d verts", verts.length/9, verts.length/3));
    }

    /** Release memory held in opengl buffers. */
    public void delete() {
        GLES20.glDeleteBuffers(1, vbo, 0);
        GLES20.glDeleteBuffers(ibo.length, ibo, 0);
    }

    public static int drawn = 0;

    /** @param mvpMatrix - The Model View Project matrix in which to draw this shape. */
    public void draw(float[] mvpMatrix, float blend) {
        prepareProgram(mainProgram, mvpMatrix);

        for (int t = 0; t < ibo.length; ++t) {
            int mColorHandle = GLES20.glGetUniformLocation(mainProgram, "vColor");
            color[t][3] = blend;
            GLES20.glUniform4fv(mColorHandle, 1, color[t], 0);

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo[t]);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount[t], GLES20.GL_UNSIGNED_SHORT, 0);
            drawn += indexCount[t]/3;
        }

        // drawing vertices:
//        int mColorHandle = GLES20.glGetUniformLocation(mainProgram, "vColor");
//        GLES20.glUniform4fv(mColorHandle, 1, new float[] {1, 0, 0, 0}, 0);
//        GLES20.glDrawElements(GLES20.GL_POINTS, indexCount, GLES20.GL_UNSIGNED_INT, 0);

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    // TODO things started crashing in the method below (when accessing the program) when I started
    // to load tiles dynamically, i.e. not preload all the content
    private void prepareProgram(int program, float[] mvpMatrix) {
        GLES20.glUseProgram(program);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0]);

        // get handle to vertex shader's vPosition member
        int mPositionHandle = GLES20.glGetAttribLocation(program, "vPosition");

        // Enable a handle to the triangle vertices
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        // Prepare the triangle coordinate data
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, 0);

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
