package com.max.vectormap;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.max.vectormap.gles.Drawable2d;
import com.max.vectormap.gles.EglCore;
import com.max.vectormap.gles.GeneratedTexture;
import com.max.vectormap.gles.GlUtil;
import com.max.vectormap.gles.Sprite2d;
import com.max.vectormap.gles.Texture2dProgram;
import com.max.vectormap.gles.WindowSurface;

import java.util.List;

/**
 * This class handles all OpenGL rendering.
 * <p>
 * We use Choreographer to coordinate with the device vsync.  We deliver one frame
 * per vsync.  We can't actually know when the frame we render will be drawn, but at
 * least we get a consistent frame interval.
 * <p>
 * Start the render thread after the Surface has been created.
 */
public class ChoreographerRenderThread extends Thread {
    // Object must be created on render thread to get correct Looper, but is used from
    // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
    // constructed object.
    private volatile RenderHandler mHandler;

    // Used to wait for the thread to start.
    private Object mStartLock = new Object();
    private boolean mReady = false;

    private volatile SurfaceHolder mSurfaceHolder;  // contents may be updated by UI thread
    private EglCore mEglCore;
    private WindowSurface mWindowSurface;

    // Previous frame time.
    private long mPrevTimeNanos;

    private final Context context;

    private TileCache tileCache;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];

    private float screenRatio;
    private float nearPlane = 0.01f;

    /**
     * Used to synchronize access to the global camera position. Direct synchronized access is
     * used instead of passing messages in order to reduce the overhead and ensure that the
     * position is updated immediately.
     */
    public final Object CAMERA_POSITION_LOCK = new Object();

    public float globalCenterUtmX = 400000 - Constants.GLOBAL_OFS_X;
    public float globalCenterUtmY = 6170000 - Constants.GLOBAL_OFS_Y;
    public float globalScaleFactor = 4096;

    /**
     * Used to blend from one layer to the next. For example, if this starts out at 2, and the
     * user zooms out to layer 3, this number will be increased gradually each frame update
     * until it reaches 3, and layers will be blended accordingly.
     */
    public float blendLayer = Common.getLayerForScaleFactor(globalScaleFactor);

    // camera position specific to a single frame (thread safe), instance level to avoid passing
    // around to all methods using it
    private float frameCenterUtmX, frameCenterUtmY, frameScaleFactor;

    private int glProgram;

    /**
     * Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist.
     * The context is needed to access resources.
     */
    public ChoreographerRenderThread(SurfaceHolder holder, Context context) {
        mSurfaceHolder = holder;
        this.context = context;
        tileCache = new TileCache(context);
    }

    /**
     * Thread entry point.
     * <p>
     * The thread should not be started until the Surface associated with the SurfaceHolder
     * has been created.  That way we don't have to wait for a separate "surface created"
     * message to arrive.
     */
    @Override
    public void run() {
        Looper.prepare();
        mHandler = new RenderHandler(this);
        mEglCore = new EglCore(null, 0);
        synchronized (mStartLock) {
            mReady = true;
            mStartLock.notify();    // signal waitUntilReady()
        }

        Looper.loop();

        Log.d(ChoreographerActivity.TAG, "looper quit");
        releaseGl();
        mEglCore.release();

        synchronized (mStartLock) {
            mReady = false;
        }
    }

    /**
     * Waits until the render thread is ready to receive messages.
     * <p>
     * Call from the UI thread.
     */
    public void waitUntilReady() {
        synchronized (mStartLock) {
            while (!mReady) {
                try {
                    mStartLock.wait();
                } catch (InterruptedException ie) { /* not expected */ }
            }
        }
    }

    /** Shuts everything down. */
    void shutdown() {
        Log.d(ChoreographerActivity.TAG, "shutdown");
        Looper.myLooper().quit();
    }

    /** Returns the render thread's Handler.  This may be called from any thread. */
    public RenderHandler getHandler() {
        return mHandler;
    }

    /** Prepares the surface. */
    void surfaceCreated() {
        Surface surface = mSurfaceHolder.getSurface();
        prepareGl(surface);
    }

    /** Prepares window surface and GL state. */
    private void prepareGl(Surface surface) {
        Log.d(ChoreographerActivity.TAG, "prepareGl");

        mWindowSurface = new WindowSurface(mEglCore, surface, false);
        mWindowSurface.makeCurrent();

        float[] water = Common.rgb(Constants.COLORS_NEW[0]);
        GLES20.glClearColor(water[0], water[1], water[2], 1.0f);

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
    }

    /**
     * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
     * Must be called before we start drawing. (Called from RenderHandler.)
     */
    void surfaceChanged(int width, int height) {
        Log.d(ChoreographerActivity.TAG, "surfaceChanged " + width + "x" + height);

        // Adjust the viewport based on geometry changes, such as screen rotation
        GLES20.glViewport(0, 0, width, height);

        screenRatio = (float) width / height;

        // this projection matrix is applied to object coordinates in the onDrawFrame() method
        Matrix.frustumM(mProjectionMatrix, 0, -screenRatio, screenRatio, -1f, 1f, nearPlane, 16384);
    }

    /**
     * Releases most of the GL resources we currently hold.
     * <p>
     * Does not release EglCore.
     */
    private void releaseGl() {
        GlUtil.checkGlError("releaseGl start");

        if (mWindowSurface != null) {
            mWindowSurface.release();
            mWindowSurface = null;
        }
        GlUtil.checkGlError("releaseGl done");

        mEglCore.makeNothingCurrent();
    }

    /** Option proof of concept. */
    void setRenderOption(boolean option) { }

    /** Handles the frame update.  Runs when Choreographer signals. */
    void doFrame(long timeStampNanos) {
        synchronized (CAMERA_POSITION_LOCK) {
            frameCenterUtmX = globalCenterUtmX;
            frameCenterUtmY = globalCenterUtmY;
            frameScaleFactor = globalScaleFactor;
        }

        float elapsedSeconds = update(timeStampNanos);

        // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
        // recording is too expensive, maybe the CPU frequency governor thinks we're
        // not doing and wants to drop the clock frequencies -- we need to drop frames
        // to catch up.  The "timeStampNanos" value is based on the system monotonic
        // clock, as is System.nanoTime(), so we can compare the values directly.
        long diff = (System.nanoTime() - timeStampNanos) / 1000000;
        if (diff > 15) {
            // too much, drop a frame
            Log.d(ChoreographerActivity.TAG, "diff is " + diff + ", skipping render");
            return;
        }

        draw(elapsedSeconds);
        mWindowSurface.swapBuffers();
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
        return 1000*1024 / frameScaleFactor;
    }

    /** x0, y0, x1, y1 (utm coordinates) */
    private void getScreenEdges(int[] screenEdges) {
        float f = getCameraDistance() / nearPlane;
        screenEdges[0] = (int)(frameCenterUtmX - f * screenRatio + 0.5);
        screenEdges[1] = (int)(frameCenterUtmY - f + 0.5);
        screenEdges[2] = (int)(frameCenterUtmX + f * screenRatio + 0.5);
        screenEdges[3] = (int)(frameCenterUtmY + f + 0.5);
    }

    private int[] screenEdges = new int[4];

    /**
     * Advances animation state.
     * <p/>
     * We use the time delta from the previous event to determine how far everything
     * moves.  Ideally this will yield identical animation sequences regardless of
     * the device's actual refresh rate.
     */
    private float update(long timeStampNanos) {
        // Compute time from previous frame.
        long intervalNanos;
        if (mPrevTimeNanos == 0) {
            intervalNanos = 0;
        } else {
            intervalNanos = timeStampNanos - mPrevTimeNanos;

            if (intervalNanos > Constants.ONE_SECOND_NANOS) {
                // A gap this big should only happen if something paused us.  We can
                // either cap the delta at one second, or just pretend like this is
                // the first frame and not advance at all.
                Log.d(ChoreographerActivity.TAG, "Time delta too large: " +
                        (double) intervalNanos / Constants.ONE_SECOND_NANOS + " sec");
                intervalNanos = 0;
            }
        }
        mPrevTimeNanos = timeStampNanos;

        float elapsedSeconds = intervalNanos / 1000000000.0f;

        // update layer blending
        float currentLayer = Common.getLayerForScaleFactor(frameScaleFactor);
        if (currentLayer > blendLayer)
            blendLayer = Math.min(currentLayer, blendLayer + elapsedSeconds);
        else if (currentLayer < blendLayer)
            blendLayer = Math.max(currentLayer, blendLayer - elapsedSeconds);

        return elapsedSeconds;
    }

    /** Draws the scene. */
    private void draw(float elapsedSeconds) {
        GlUtil.checkGlError("draw start");

        startOnDrawNanoTime = System.nanoTime();

        // Draw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        Matrix.setLookAtM(mViewMatrix, 0, frameCenterUtmX, frameCenterUtmY, getCameraDistance(), frameCenterUtmX, frameCenterUtmY, 0f, 0f, 1.0f, 0.0f);
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);
        int MVPMatrixHandle = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix");
        GLES20.glUniformMatrix4fv(MVPMatrixHandle, 1, false, mMVPMatrix, 0);

        int layer = (int)Math.floor(blendLayer);

        getScreenEdges(screenEdges);

        int tx0 = Constants.GLOBAL_OFS_X + screenEdges[0] >> Constants.TILE_SHIFTS[layer];
        int ty0 = Constants.GLOBAL_OFS_Y + screenEdges[1] >> Constants.TILE_SHIFTS[layer];
        int tx1 = Constants.GLOBAL_OFS_X + screenEdges[2] >> Constants.TILE_SHIFTS[layer];
        int ty1 = Constants.GLOBAL_OFS_Y + screenEdges[3] >> Constants.TILE_SHIFTS[layer];

//        Log.v("View", "tx="+tx0+"-"+tx1+", ty="+ty0+"-"+ty1+", layer="+layer+", edges=["+(GLOBAL_OFS_X+screenEdges[0])+","+(GLOBAL_OFS_Y+screenEdges[1])+" - "+(GLOBAL_OFS_X+screenEdges[2])+","+(GLOBAL_OFS_Y+screenEdges[3])+"]");
//        Log.v("TileCache", String.format("GPUx: %.0f kb", Tile.gpuBytes / 1024.0));
//        Log.v("TileCache", "Free vertex/index buffers: " + Tile.getFreeVertexBufferCount() + " / " + Tile.getFreeIndexBufferCount());

        tileCache.refreshForPosition(screenEdges, frameScaleFactor, layer);

        Tile.trisDrawn = 0;

        for (BlendedTile blendedTile : tileCache.getDrawOrder(screenEdges, frameScaleFactor, elapsedSeconds)) {
            if (blendedTile.blend < 1) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            }

            tileCache.get(blendedTile.tilePos, true).draw(glProgram, blendedTile.blend);

            if (blendedTile.blend < 1)
                GLES20.glDisable(GLES20.GL_BLEND);
        }

//        for (int tp : tileCache.existingTiles) {
//            Tile tile = tileCache.get(tp);
//            if (tile != null && tile.size == 0)
//                tile.draw(glProgram, 1.0f);
//        }
//        Log.v("View", "Triangles drawn: " + Tile.trisDrawn);

//        // Idea: Loop over the tile layer zoomed out one step. For each outer tile, see if all its
//        // inner (zoomed in) tiles are loaded. If not, fall back to rendering the zoomed out tile
//        // (to avoid freezing the app while loading tiles from disk). This also allows us to blend
//        // two neighboring tile layers easily.
//        int shift = Constants.TILE_SHIFT_DIFFS[layer];
//        int tx0p1 = tx0 >> shift, ty0p1 = ty0 >> shift, tx1p1 = tx1 >> shift, ty1p1 = ty1 >> shift;
//        for (int typ1 = ty0p1; typ1 <= ty1p1; ++typ1) {
//            for (int txp1 = tx0p1; txp1 <= tx1p1; ++txp1) {
//                boolean useOuterTile = false;
//                int zoomedOutTilePos = getTilePos(layer + 1, txp1, typ1);
//
//                if (layer+1 < Constants.TILE_SHIFTS.length) {
//                    // If the zoomed out tile is loaded, use it if at least 1 zoomed in tile is
//                    // not loaded. Otherwise, use it if at least 2 zoomed in tiles are not loaded.
//                    // (Since we have to load the zoomed out tile anyway.)
//                    int minTilesNotLoaded = tileCache.cache.containsKey(zoomedOutTilePos) ? 1 : 2;
//                    int tilesNotLoaded = 0;
//
//                    // loop over all (max 16 for shift diff 2) tiles in the outer tile and see if they're loaded
//                    innerTilesLoop:
//                    for (int ty = Math.max(ty0, typ1 << shift); ty <= Math.min(ty1, (typ1 + 1 << shift) - 1); ++ty) {
//                        for (int tx = Math.max(tx0, txp1 << shift); tx <= Math.min(tx1, (txp1 + 1 << shift) - 1); ++tx) {
//                            int tp = getTilePos(layer, tx, ty);
//                            if (tileCache.existingTiles.contains(tp) && !tileCache.cache.containsKey(tp)) {
//                                if (++tilesNotLoaded == minTilesNotLoaded) {
//                                    useOuterTile = true;
//                                    break innerTilesLoop;
//                                }
//                            }
//                        }
//                    }
//                }
//
//                // draw tiles
//                if (useOuterTile) {
//                    // we don't have all zoomed in tiles loaded, so fall back to zoomed out tile
//                    Tile tile = tileCache.get(zoomedOutTilePos, true);
//                    if (tile != null)
//                        tile.draw(glProgram, 1.0f);
//
//                    // if blending, only draw tiles that are loaded (to avoid stalling pipeline)
//                    float blend = 1 - (blendLayer - layer);
//                    if (blend > 0) {
//                        GLES20.glEnable(GLES20.GL_BLEND);
//                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
//                        for (int ty = Math.max(ty0, typ1 << shift); ty <= Math.min(ty1, (typ1 + 1 << shift) - 1); ++ty) {
//                            for (int tx = Math.max(tx0, txp1 << shift); tx <= Math.min(tx1, (txp1 + 1 << shift) - 1); ++tx) {
//                                int tp = getTilePos(layer, tx, ty);
//                                if (tileCache.cache.containsKey(tp))
//                                    tileCache.get(tp, true).draw(glProgram, 1.0f);
//                            }
//                        }
//                        GLES20.glDisable(GLES20.GL_BLEND);
//                    } else if (blend < 0) {
//                        throw new IllegalStateException("Unexpected blend: "+blend);
//                    }
//                } else {
//                    // always draw zoomed in tiles first and without blending
//                    for (int ty = Math.max(ty0, typ1 << shift); ty <= Math.min(ty1, (typ1 + 1 << shift) - 1); ++ty) {
//                        for (int tx = Math.max(tx0, txp1 << shift); tx <= Math.min(tx1, (txp1 + 1 << shift) - 1); ++tx) {
//                            int tp = getTilePos(layer, tx, ty);
//                            Tile tile = tileCache.get(tp, true);
//                            if (tile != null)
//                                tile.draw(glProgram, 1.0f);
//                        }
//                    }
//
//                    // optional blending layer (zoomed out)
//                    if (layer < Constants.TILE_SHIFTS.length - 1) {
//                        float blend = blendLayer - layer;
//                        if (blend > 0) {
//                            GLES20.glEnable(GLES20.GL_BLEND);
//                            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
//                            Tile tile = tileCache.get(zoomedOutTilePos, true);
//                            if (tile != null)
//                                tile.draw(glProgram, blend);
//                            GLES20.glDisable(GLES20.GL_BLEND);
//                        } else if (blend < 0) {
//                            throw new IllegalStateException("Unexpected blend: "+blend);
//                        }
//                    }
//                }
//            }
//        }

//        tileCache.get(738032, true).draw(glProgram, 1.0f); // for debugging

//        logFPS();

        GlUtil.checkGlError("draw done");
    }
}
