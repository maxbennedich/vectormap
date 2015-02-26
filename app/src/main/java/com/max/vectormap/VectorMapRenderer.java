package com.max.vectormap;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
//    private TileDiskLoader tileDiskLoader;

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
        // Set the background frame color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        String vertexShader = Common.readInputStream(context.getResources().openRawResource(R.raw.vertex_shader));
        String fragmentShader = Common.readInputStream(context.getResources().openRawResource(R.raw.fragment_shader));
        glProgram = ShaderHelper.createProgram(
                ShaderHelper.loadShader(GLES20.GL_VERTEX_SHADER, vertexShader),
                ShaderHelper.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader));

        tileCache = new TileCache(context);
//        tileDiskLoader = new TileDiskLoader(tileCache);
//
//        new Thread(tileDiskLoader).start();
    }

    private long prevNanoTime = System.nanoTime();

    private void logFPS() {
        long time = System.nanoTime();
        Log.v("PerfLog", String.format("FPS=%.1f", 1e9/(time-prevNanoTime)));
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

    @Override
    public void onDrawFrame(GL10 unused) {
        logFPS();

        // Draw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // To test overdraw: use glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) and half all RGB values!
//        GLES20.glEnable(GLES20.GL_BLEND);
//        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDisable(GLES20.GL_BLEND);

        GLES20.glUseProgram(glProgram);

        Matrix.setLookAtM(mViewMatrix, 0, centerUtmX, centerUtmY, getCameraDistance(), centerUtmX, centerUtmY, 0f, 0f, 1.0f, 0.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
        int MVPMatrixHandle = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix");
        GLES20.glUniformMatrix4fv(MVPMatrixHandle, 1, false, mMVPMatrix, 0);

        int[] tileShifts = {13, 15, 17, 19};
        float[] layerShifts = {2048, 4096, 16384};
        int layer = scaleFactor > layerShifts[2] ? 0 : (scaleFactor > layerShifts[1] ? 1 : (scaleFactor > layerShifts[0] ? 2 : 3));

        getScreenEdges(screenEdges);

        int tx0 = GLOBAL_OFS_X + screenEdges[0] >> tileShifts[layer];
        int ty0 = GLOBAL_OFS_Y + screenEdges[1] >> tileShifts[layer];
        int tx1 = GLOBAL_OFS_X + screenEdges[2] >> tileShifts[layer];
        int ty1 = GLOBAL_OFS_Y + screenEdges[3] >> tileShifts[layer];

//        Log.v("View", "tx="+tx0+"-"+tx1+", ty="+ty0+"-"+ty1+", layer="+layer+", edges=["+(GLOBAL_OFS_X+screenEdges[0])+","+(GLOBAL_OFS_Y+screenEdges[1])+" - "+(GLOBAL_OFS_X+screenEdges[2])+","+(GLOBAL_OFS_Y+screenEdges[3])+"]");
        Log.v("TileCache", String.format("%.0f kb in GPU, %.0f kb in memory, %.0f kb ever allocated", Tile.gpuBytes/1024.0, Tile.bufferBytes/1024.0, Tile.bufferBytesEverAllocated/1024.0));

        updateTilesToLoad();

//        Tile.trisDrawn = 0;
//        for (int tp : tileCache.existingTiles) {
//            Tile tile = tileCache.get(tp);
//            if (tile != null && tile.size == 0)
//                tile.draw(glProgram, 1.0f);
//        }
//        Log.v("View", "Triangles drawn: " + Tile.trisDrawn);

        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx) {
                int tp = getTilePos(layer, tx, ty);
                if (tileCache.existingTiles.contains(tp) && !tileCache.cache.containsKey(tp)) {
                    Log.d("TileCache", "ERROR: tile not loaded:" + layer + "," + tx + "," + ty);
                }
            }

        for (int ty = ty0; ty <= ty1; ++ty) {
            for (int tx = tx0; tx <= tx1; ++tx) {
                int tp = getTilePos(layer, tx, ty);
                Tile tile = tileCache.get(tp);
                if (tile != null)
                    tile.draw(glProgram, 1.0f);
            }
        }


//        float[] layerShifts = {2048,4096, 8192,16384};
//
//        // Draw triangles
//        GLES20.glDisable(GLES20.GL_BLEND);
//        if (scaleFactor < layerShifts[0]) {
//            drawLayer(scratch, 2);
//        } else if (scaleFactor < layerShifts[1]) {
//            blendLayers(scratch, 1, 2, (layerShifts[1]-scaleFactor)/layerShifts[0]);
//        } else if (scaleFactor < layerShifts[2]) {
//            drawLayer(scratch, 1);
//        } else if (scaleFactor < layerShifts[3]) {
//            blendLayers(scratch, 0, 1, (layerShifts[3]-scaleFactor)/layerShifts[2]);
//        } else {
//            drawLayer(scratch, 0);
//        }
    }

    BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<Runnable>();
    ThreadPoolExecutor tileDiskLoaderExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, workQueue);

    Random rnd = new Random();

    private void updateTiles(final List<Integer> tilesToLoad) {
//        Log.d("TileCache", "Removing "+workQueue.size() + " entries from work queue");
        workQueue.clear();

        final int R = rnd.nextInt();
//        Log.d("TileCache", R+" tiles to load: "+tilesToLoad);

        Map<Integer, Tile> loadedSnapshot = new HashMap<>(tileCache.cache);
        loadedSnapshot.keySet().removeAll(tilesToLoad);
        for (Tile tile : loadedSnapshot.values()) {
//            Log.d("TileCache", R+" deleting tile: "+getTilePos(tile.size, tile.tx, tile.ty));
            tile.delete();
            int tp = getTilePos(tile.size, tile.tx, tile.ty);
            tileCache.cache.remove(tp);
        }

        for (final int tp : tilesToLoad) {
            if (tileCache.existingTiles.contains(tp)) {
                tileDiskLoaderExecutor.execute(new Runnable() {
                    @Override public void run() {
//                        Log.d("TileCache", R+" requesting from cache: "+tp);
                        tileCache.get(tp);
                    }
                });
            }
        }

//        Log.d("TileCache", R+" tiles loaded: "+loadedTiles.keySet());
//        Log.d("TileCache", "Tiles to load: " + sb);
    }

//    static class TileDiskLoader implements Runnable {
//        BlockingQueue<Integer> tilesToLoad = new LinkedBlockingDeque<>();
//
//        private static final Integer DELETE_TILES = new Integer(-1);
//
//        Map<Integer, Tile> loadedTiles = new HashMap<>();
//
//        private final TileCache tileCache;
//
//        TileDiskLoader(TileCache tileCache) {
//            this.tileCache = tileCache;
//        }
//
//        @Override public void run() {
//            try {
//                while (true) {
//                    final Integer tp = tilesToLoad.take();
//                    if (tp == DELETE_TILES) {
//                        for (Tile tile : tilesToDelete)
//                            tile.delete();
//                    } else if (!loadedTiles.containsKey(tp)) {
//                        Log.d("TileCache", "Loader thread requests tile "+getLayer(tp)+","+getTX(tp)+","+getTY(tp));
//                        synchronized (tileCache) {
//                            loadedTiles.put(tp, tileCache.get(tp));
//                        }
//                    }
//                }
//            } catch (InterruptedException ex) {
//                throw new RuntimeException("Unexpected interruption", ex);
//            }
//        }
//
//        Set<Tile> tilesToDelete;
//
//        public void updateTiles(List<Integer> updatedTiles) {
//            tilesToLoad.clear();
//
//            Set<Integer> toLoadSet = new HashSet<>(updatedTiles);
//            tilesToDelete = new HashSet<>();
//            for (Map.Entry<Integer, Tile> entry : loadedTiles.entrySet())
//                if (toLoadSet.contains(entry.getKey()))
//                    tilesToDelete.add(entry.getValue());
//
//            tilesToLoad.add(DELETE_TILES);
//
//            StringBuilder sb = new StringBuilder();
//            for (int tp : updatedTiles) {
//                if (tileCache.existingTiles.contains(tp)) {
//                    sb.append("("+getLayer(tp)+","+getTX(tp)+","+getTY(tp)+") ");
//                    tilesToLoad.add(tp);
//                }
//            }
//
//            Log.d("TileCache", "Tiles to load: " + sb);
//        }
//    };

    void updateTilesToLoad() {
        List<Integer> tilesToLoad = new ArrayList<>();

        getScreenEdges(screenEdges);
        int[] tileShifts = {13, 15, 17, 19};
        float[] layerShifts = {2048, 4096, 16384};
        int layer = scaleFactor > layerShifts[2] ? 0 : (scaleFactor > layerShifts[1] ? 1 : (scaleFactor > layerShifts[0] ? 2 : 3));

        // prio 1: tiles on screen
        int tx0 = GLOBAL_OFS_X + screenEdges[0] >> tileShifts[layer];
        int ty0 = GLOBAL_OFS_Y + screenEdges[1] >> tileShifts[layer];
        int tx1 = GLOBAL_OFS_X + screenEdges[2] >> tileShifts[layer];
        int ty1 = GLOBAL_OFS_Y + screenEdges[3] >> tileShifts[layer];

        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx)
                tilesToLoad.add(getTilePos(layer, tx, ty));

        // prio 2: one level zoomed out (plus surroundings)
        if (layer+1 < tileShifts.length) {
            int p1x0 = GLOBAL_OFS_X + screenEdges[0] >> tileShifts[layer + 1];
            int p1y0 = GLOBAL_OFS_Y + screenEdges[1] >> tileShifts[layer + 1];
            int p1x1 = GLOBAL_OFS_X + screenEdges[2] >> tileShifts[layer + 1];
            int p1y1 = GLOBAL_OFS_Y + screenEdges[3] >> tileShifts[layer + 1];

            for (int ty = p1y0-1; ty <= p1y1+1; ++ty)
                for (int tx = p1x0-1; tx <= p1x1+1; ++tx)
                    tilesToLoad.add(getTilePos(layer + 1, tx, ty));
        }

        // prio 3: regular zoom level, just outside screen
        for (int tx = tx0-1; tx <= tx1+1; ++tx) {
            tilesToLoad.add(getTilePos(layer, tx, ty0-1));
            tilesToLoad.add(getTilePos(layer, tx, ty1+1));
        }
        for (int ty = ty0; ty <= ty1; ++ty) {
            tilesToLoad.add(getTilePos(layer, tx0-1, ty));
            tilesToLoad.add(getTilePos(layer, tx1+1, ty));
        }

        // prio 4: one level zoomed in
        if (layer-1 >= 0) {
            int m1x0 = GLOBAL_OFS_X + screenEdges[0] >> tileShifts[layer - 1];
            int m1y0 = GLOBAL_OFS_Y + screenEdges[1] >> tileShifts[layer - 1];
            int m1x1 = GLOBAL_OFS_X + screenEdges[2] >> tileShifts[layer - 1];
            int m1y1 = GLOBAL_OFS_Y + screenEdges[3] >> tileShifts[layer - 1];

            for (int ty = m1y0; ty <= m1y1; ++ty)
                for (int tx = m1x0; tx <= m1x1; ++tx)
                    tilesToLoad.add(getTilePos(layer - 1, tx, ty));
        }

        updateTiles(tilesToLoad);
//        tileDiskLoader.updateTiles(tilesToLoad);
    }

//    void drawLayer(float[] mvpMatrix, int layer) { drawLayer(mvpMatrix, layer, 1.0f); }
//
//    void drawLayer(float[] mvpMatrix, int layer, float blend) {
//        for (Triangle tri : mTris.get(layer))
//            tri.draw(mvpMatrix, blend);
//    }
//
//    void blendLayers(float[] mvpMatrix, int layer1, int layer2, float blend) {
//        drawLayer(mvpMatrix, layer1);
//
//        GLES20.glEnable(GLES20.GL_BLEND);
//        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
//        drawLayer(mvpMatrix, layer2, blend);
//        GLES20.glDisable(GLES20.GL_BLEND);
//    }

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