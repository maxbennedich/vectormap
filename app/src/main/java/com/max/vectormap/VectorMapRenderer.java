package com.max.vectormap;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.util.Log;

/**
 * Provides drawing instructions for a GLSurfaceView object. This class
 * must override the OpenGL ES drawing lifecycle methods:
 * <ul>
 *   <li>{@link android.opengl.GLSurfaceView.Renderer#onSurfaceCreated}</li>
 *   <li>{@link android.opengl.GLSurfaceView.Renderer#onDrawFrame}</li>
 *   <li>{@link android.opengl.GLSurfaceView.Renderer#onSurfaceChanged}</li>
 * </ul>
 */
public class VectorMapRenderer implements GLSurfaceView.Renderer {

    private final Context context;

    private final VectorMapSurfaceView surfaceView;

    private TileCache tileCache;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];

    private float screenRatio;
    private float nearPlane = 0.01f;

    public static final int GLOBAL_OFS_X = 400000;
    public static final int GLOBAL_OFS_Y = 6200000;

    public float centerUtmX = 400000-GLOBAL_OFS_X, centerUtmY = 6170000-GLOBAL_OFS_Y, scaleFactor = 4096;

    private int glProgram;

    public VectorMapRenderer(Context context, VectorMapSurfaceView surfaceView) {
        this.context = context;
        this.surfaceView = surfaceView;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // To test overdraw: use glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) and half all RGB values!
//        GLES20.glEnable(GLES20.GL_BLEND);
//        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDisable(GLES20.GL_BLEND);

        String vertexShader = Common.readInputStream(context.getResources().openRawResource(R.raw.vertex_shader));
        String fragmentShader = Common.readInputStream(context.getResources().openRawResource(R.raw.fragment_shader));
        glProgram = ShaderHelper.createProgram(
                ShaderHelper.loadShader(GLES20.GL_VERTEX_SHADER, vertexShader),
                ShaderHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader));
        GLES20.glUseProgram(glProgram);

        tileCache = new TileCache(context);
    }

    private long prevNanoTime = System.nanoTime();
    private long startOnDrawNanoTime;

    private void logFPS() {
        long time = System.nanoTime();
        long fpsTime = time - prevNanoTime;
        long onDrawTime = time - startOnDrawNanoTime;
        Log.v("PerfLog", String.format("FPS: %.1f, time: %.1f ms, ondraw: %.1f ms, tris: %d", 1e9/fpsTime, fpsTime/1e6, onDrawTime/1e6, Tile.trisDrawn));
        prevNanoTime = time;
    }

    private float getCameraDistance() {
        return 1000*1024 / scaleFactor;
    }

    public static final int getTilePos(int layer, int tx, int ty) {
        // layer: 4 bits (0-15)
        // tx/ty: 14 bits (0-16383)
        return (layer << 28) + (tx << 14) + ty;
    }

    public static final int getLayer(int tilePos) { return tilePos >>> 28; }
    public static final int getTX(int tilePos) { return (tilePos >> 14) & 0x3fff; }
    public static final int getTY(int tilePos) { return tilePos & 0x3fff; }

    /** x0, y0, x1, y1 */
    private void getScreenEdges(int[] screenEdges) {
        float f = getCameraDistance() / nearPlane;
        screenEdges[0] = (int)(centerUtmX - f * screenRatio + 0.5);
        screenEdges[1] = (int)(centerUtmY - f + 0.5);
        screenEdges[2] = (int)(centerUtmX + f * screenRatio + 0.5);
        screenEdges[3] = (int)(centerUtmY + f + 0.5);
    }

    private int[] screenEdges = new int[4];

    public static final int[] TILE_SHIFTS = {13, 15, 17, 19};
    public static final float[] LAYER_SHIFTS = {2048, 4096, 16384};

    @Override
    public void onDrawFrame(GL10 unused) {
        startOnDrawNanoTime = System.nanoTime();

        // Draw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        Matrix.setLookAtM(mViewMatrix, 0, centerUtmX, centerUtmY, getCameraDistance(), centerUtmX, centerUtmY, 0f, 0f, 1.0f, 0.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
        int MVPMatrixHandle = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix");
        GLES20.glUniformMatrix4fv(MVPMatrixHandle, 1, false, mMVPMatrix, 0);

        int layer = scaleFactor > LAYER_SHIFTS[2] ? 0 : (scaleFactor > LAYER_SHIFTS[1] ? 1 : (scaleFactor > LAYER_SHIFTS[0] ? 2 : 3));

        getScreenEdges(screenEdges);

        int tx0 = GLOBAL_OFS_X + screenEdges[0] >> TILE_SHIFTS[layer];
        int ty0 = GLOBAL_OFS_Y + screenEdges[1] >> TILE_SHIFTS[layer];
        int tx1 = GLOBAL_OFS_X + screenEdges[2] >> TILE_SHIFTS[layer];
        int ty1 = GLOBAL_OFS_Y + screenEdges[3] >> TILE_SHIFTS[layer];

//        Log.v("View", "tx="+tx0+"-"+tx1+", ty="+ty0+"-"+ty1+", layer="+layer+", edges=["+(GLOBAL_OFS_X+screenEdges[0])+","+(GLOBAL_OFS_Y+screenEdges[1])+" - "+(GLOBAL_OFS_X+screenEdges[2])+","+(GLOBAL_OFS_Y+screenEdges[3])+"]");
        Log.v("TileCache", String.format("GPUx: %.0f kb", Tile.gpuBytes / 1024.0));
//        Log.v("TileCache", "Free vertex/index buffers: " + Tile.getFreeVertexBufferCount() + " / " + Tile.getFreeIndexBufferCount());

        tileCache.refreshForPosition(screenEdges, scaleFactor);

        Tile.trisDrawn = 0;
//        for (int tp : tileCache.existingTiles) {
//            Tile tile = tileCache.get(tp);
//            if (tile != null && tile.size == 0)
//                tile.draw(glProgram, 1.0f);
//        }
//        Log.v("View", "Triangles drawn: " + Tile.trisDrawn);

        // Idea: Loop over the tile layer zoomed out one step. For each outer tile, see if all its
        // inner (zoomed in) tiles are loaded. If not, fall back to rendering the zoomed out tile
        // (to avoid freezing the app while loading tiles from disk). This also allows us to blend
        // two neighboring tile layers easily.
        int tx0p1 = tx0 >> 2, ty0p1 = ty0 >> 2, tx1p1 = tx1 >> 2, ty1p1 = ty1 >> 2;
        for (int typ1 = ty0p1; typ1 <= ty1p1; ++typ1) {
            for (int txp1 = tx0p1; txp1 <= tx1p1; ++txp1) {
                boolean useOuterTile = false;
                int zoomedOutTilePos = getTilePos(layer + 1, txp1, typ1);

                if (layer+1 < TILE_SHIFTS.length) {
                    // If the zoomed out tile is loaded, use it if at least 1 zoomed in tile is
                    // not loaded. Otherwise, use it if at least 2 zoomed in tiles are not loaded.
                    // (Since we have to load the zoomed out tile anyway.)
                    int minTilesNotLoaded = tileCache.cache.containsKey(zoomedOutTilePos) ? 1 : 2;
                    int tilesNotLoaded = 0;

                    // loop over all (max 16) tiles in the outer tile and see if they're loaded
                    innerTilesLoop:
                    for (int ty = Math.max(ty0, typ1 << 2); ty <= Math.min(ty1, (typ1 << 2) + 3); ++ty) {
                        for (int tx = Math.max(tx0, txp1 << 2); tx <= Math.min(tx1, (txp1 << 2) + 3); ++tx) {
                            int tp = getTilePos(layer, tx, ty);
                            if (tileCache.existingTiles.contains(tp) && !tileCache.cache.containsKey(tp)) {
                                if (++tilesNotLoaded == minTilesNotLoaded) {
                                    useOuterTile = true;
                                    break innerTilesLoop;
                                }
                            }
                        }
                    }
                }

                // draw tiles
                if (useOuterTile) {
                    Tile tile = tileCache.get(zoomedOutTilePos, true);
                    if (tile != null)
                        tile.draw(glProgram, 1.0f);
                } else {
                    // always draw zoomed in tiles first and without blending
                    for (int ty = Math.max(ty0, typ1 << 2); ty <= Math.min(ty1, (typ1 << 2) + 3); ++ty) {
                        for (int tx = Math.max(tx0, txp1 << 2); tx <= Math.min(tx1, (txp1 << 2) + 3); ++tx) {
                            int tp = getTilePos(layer, tx, ty);
                            Tile tile = tileCache.get(tp, true);
                            if (tile != null)
                                tile.draw(glProgram, 1.0f);
                        }
                    }

                    // optional blending layer
                    if (layer < TILE_SHIFTS.length - 1) {
                        float blend = 2 - scaleFactor / LAYER_SHIFTS[LAYER_SHIFTS.length-1-layer];
                        if (blend > 0) {
                            GLES20.glEnable(GLES20.GL_BLEND);
                            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                            Tile tile = tileCache.get(zoomedOutTilePos, true);
                            if (tile != null)
                                tile.draw(glProgram, blend);
                            GLES20.glDisable(GLES20.GL_BLEND);
                        }
                    }
                }
            }
        }

        logFPS();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.v("VectorMap", "renderer size w="+width+", h="+height);
        // Adjust the viewport based on geometry changes, such as screen rotation
        GLES20.glViewport(0, 0, width, height);

        screenRatio = (float) width / height;

        // this projection matrix is applied to object coordinates in the onDrawFrame() method
        Matrix.frustumM(mProjectionMatrix, 0, -screenRatio, screenRatio, -1f, 1f, nearPlane, 16384);
    }
}