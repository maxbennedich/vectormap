package com.max.vectormap;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.app.Activity;

import com.max.vectormap.gles.Drawable2d;
import com.max.vectormap.gles.EglCore;
import com.max.vectormap.gles.GeneratedTexture;
import com.max.vectormap.gles.GlUtil;
import com.max.vectormap.gles.Sprite2d;
import com.max.vectormap.gles.Texture2dProgram;
import com.max.vectormap.gles.WindowSurface;
import com.max.vectormap.R;

import java.lang.ref.WeakReference;

public class ChoreographerActivity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private static final String TAG = "Choreographer";

    // Rendering code runs on this thread.  The thread's life span is tied to the Surface.
    private RenderThread mRenderThread;

    private float mPreviousX;
    private float mPreviousY;

    private long lastNano = System.nanoTime();

    private static long T0 = System.nanoTime();

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        switch (e.getAction()) {
            case MotionEvent.ACTION_MOVE:

                final float dx = x - mPreviousX;
                final float dy = y - mPreviousY;

                long time = System.nanoTime();
                Log.v("VSYNC", String.format("Delta=%.1f ms: "+(System.nanoTime()-T0)/1000000, (time-lastNano)/1e6));
                lastNano = time;

                mRenderThread.move(dx, dy);
        }

        mPreviousX = x;
        mPreviousY = y;
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "HardwareScalerActivity: onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // TODO, correct?

        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // If the callback was posted, remove it.  This stops the notifications.  Ideally we
        // would send a message to the thread letting it know, so when it wakes up it can
        // reset its notion of when the previous Choreographer event arrived.
        Log.d(TAG, "onPause unhooking choreographer");
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If we already have a Surface, we just need to resume the frame notifications.
        if (mRenderThread != null) {
            Log.d(TAG, "onResume re-hooking choreographer");
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder);

        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        mRenderThread = new RenderThread(sv.getHolder());
        mRenderThread.setName("HardwareScaler GL render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSetRenderOption(true);
            rh.sendSurfaceCreated();
        }

        // start the draw events
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendSurfaceChanged(format, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed holder=" + holder);

        // We need to wait for the render thread to shut down before continuing because we
        // don't want the Surface to disappear out from under it mid-render.  The frame
        // notifications will have been stopped back in onPause(), but there might have
        // been one in progress.

        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            rh.sendShutdown();
            try {
                mRenderThread.join();
            } catch (InterruptedException ie) {
                // not expected
                throw new RuntimeException("join was interrupted", ie);
            }
        }
        mRenderThread = null;

        Log.d(TAG, "surfaceDestroyed complete");
    }

    /*
     * Choreographer callback, called near vsync.
     *
     * @see android.view.Choreographer.FrameCallback#doFrame(long)
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        RenderHandler rh = mRenderThread.getHandler();
        if (rh != null) {
            Choreographer.getInstance().postFrameCallback(this);
            rh.sendDoFrame(frameTimeNanos);
        }
    }

    /**
     * This class handles all OpenGL rendering.
     * <p>
     * We use Choreographer to coordinate with the device vsync.  We deliver one frame
     * per vsync.  We can't actually know when the frame we render will be drawn, but at
     * least we get a consistent frame interval.
     * <p>
     * Start the render thread after the Surface has been created.
     */
    private static class RenderThread extends Thread {
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
        private Texture2dProgram mTexProgram;
        private int mCoarseTexture;

        // Orthographic projection matrix.
        private float[] mDisplayProjectionMatrix = new float[16];

        private final Drawable2d mRectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE);

        private Sprite2d mRect;

        private final float[] mIdentityMatrix;

        // Previous frame time.
        private long mPrevTimeNanos;

        /** Pass in the SurfaceView's SurfaceHolder.  Note the Surface may not yet exist. */
        public RenderThread(SurfaceHolder holder) {
            mSurfaceHolder = holder;

            mIdentityMatrix = new float[16];
            Matrix.setIdentityM(mIdentityMatrix, 0);

            mRect = new Sprite2d(mRectDrawable);
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

            Log.d(TAG, "looper quit");
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
        private void shutdown() {
            Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
        }

        /** Returns the render thread's Handler.  This may be called from any thread. */
        public RenderHandler getHandler() {
            return mHandler;
        }

        /** Prepares the surface. */
        private void surfaceCreated() {
            Surface surface = mSurfaceHolder.getSurface();
            prepareGl(surface);
        }

        /** Prepares window surface and GL state. */
        private void prepareGl(Surface surface) {
            Log.d(TAG, "prepareGl");

            mWindowSurface = new WindowSurface(mEglCore, surface, false);
            mWindowSurface.makeCurrent();

            // Programs used for drawing onto the screen.
            mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
            mCoarseTexture = GeneratedTexture.createTestTexture(GeneratedTexture.Image.COARSE);

            // Set the background color.
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            // Disable depth testing -- we're 2D only.
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            // Don't need backface culling.  (If you're feeling pedantic, you can turn it on to
            // make sure we're defining our shapes correctly.)
            GLES20.glDisable(GLES20.GL_CULL_FACE);
        }

        /**
         * Handles changes to the size of the underlying surface.  Adjusts viewport as needed.
         * Must be called before we start drawing. (Called from RenderHandler.)
         */
        private void surfaceChanged(int width, int height) {
            Log.d(TAG, "surfaceChanged " + width + "x" + height);

            // Use full window.
            GLES20.glViewport(0, 0, width, height);

            // Simple orthographic projection, with (0,0) in lower-left corner.
            Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

            int smallDim = Math.min(width, height);

            // Set initial shape size / position / velocity based on window size.  Movement
            // has the same "feel" on all devices, but the actual path will vary depending
            // on the screen proportions.  We do it here, rather than defining fixed values
            // and tweaking the projection matrix, so that our squares are square.
            mRect.setColor(0.9f, 0.1f, 0.1f);
            mRect.setTexture(mCoarseTexture);
            mRect.setScale(smallDim / 5.0f, smallDim / 5.0f);
            mRect.setPosition(width / 2.0f, height / 2.0f);
            xpos = width/2.0f; ypos = height / 2.0f;

            Log.d(TAG, "mRect: " + mRect);
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
            if (mTexProgram != null) {
                mTexProgram.release();
                mTexProgram = null;
            }
            GlUtil.checkGlError("releaseGl done");

            mEglCore.makeNothingCurrent();
        }

        /** Option proof of concept. */
        private void setRenderOption(boolean option) { }

        /**
         * Handles the frame update.  Runs when Choreographer signals.
         */
        private void doFrame(long timeStampNanos) {
            // If we're not keeping up 60fps -- maybe something in the system is busy, maybe
            // recording is too expensive, maybe the CPU frequency governor thinks we're
            // not doing and wants to drop the clock frequencies -- we need to drop frames
            // to catch up.  The "timeStampNanos" value is based on the system monotonic
            // clock, as is System.nanoTime(), so we can compare the values directly.

            update(timeStampNanos);

            long diff = (System.nanoTime() - timeStampNanos) / 1000000;
            if (diff > 15) {
                // too much, drop a frame
                Log.d(TAG, "diff is " + diff + ", skipping render");
                return;
            }

            draw();
            Log.v("VSYNC", "Swap buffer "+(System.nanoTime()-T0)/1000000);
            mWindowSurface.swapBuffers();
        }

        /**
         * Advances animation state.
         * <p/>
         * We use the time delta from the previous event to determine how far everything
         * moves.  Ideally this will yield identical animation sequences regardless of
         * the device's actual refresh rate.
         */
        private void update(long timeStampNanos) {
            // Compute time from previous frame.
            long intervalNanos;
            if (mPrevTimeNanos == 0) {
                intervalNanos = 0;
            } else {
                intervalNanos = timeStampNanos - mPrevTimeNanos;

                final long ONE_SECOND_NANOS = 1000000000L;
                if (intervalNanos > ONE_SECOND_NANOS) {
                    // A gap this big should only happen if something paused us.  We can
                    // either cap the delta at one second, or just pretend like this is
                    // the first frame and not advance at all.
                    Log.d(TAG, "Time delta too large: " +
                            (double) intervalNanos / ONE_SECOND_NANOS + " sec");
                    intervalNanos = 0;
                }
            }
            mPrevTimeNanos = timeStampNanos;

            final float ONE_BILLION_F = 1000000000.0f;
            final float elapsedSeconds = intervalNanos / ONE_BILLION_F;

            // TODO any updates here using 'elapsedSeconds'
        }

        float xpos, ypos;

        public void move(float dx, float dy) {
            xpos += dx; ypos -= dy;
            mRect.setPosition(xpos, ypos);
        }

        /** Draws the scene. */
        private void draw() {
            GlUtil.checkGlError("draw start");

            // Clear to a non-black color to make the content easily differentiable from
            // the pillar-/letter-boxing.
            GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            mRect.draw(mTexProgram, mDisplayProjectionMatrix);

            GlUtil.checkGlError("draw done");
        }
    }

    /**
     * Handler for RenderThread.  Used for messages sent from the UI thread to the render thread.
     * <p>
     * The object is created on the render thread, and the various "send" methods are called
     * from the UI thread.
     */
    private static class RenderHandler extends Handler {
        private static final int MSG_SURFACE_CREATED = 0;
        private static final int MSG_SURFACE_CHANGED = 1;
        private static final int MSG_DO_FRAME = 2;
        private static final int MSG_OPTION = 3;
        private static final int MSG_SHUTDOWN = 5;

        // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
        // but no real harm in it.
        private WeakReference<RenderThread> mWeakRenderThread;

        /** Call from render thread. */
        public RenderHandler(RenderThread rt) {
            mWeakRenderThread = new WeakReference<RenderThread>(rt);
        }

        /** Sends the "surface created" message. */
        public void sendSurfaceCreated() {
            sendMessage(obtainMessage(MSG_SURFACE_CREATED));
        }

        /** Sends the "surface changed" message, forwarding what we got from the SurfaceHolder. */
        public void sendSurfaceChanged(@SuppressWarnings("unused") int format, int width, int height) {
            sendMessage(obtainMessage(MSG_SURFACE_CHANGED, width, height));
        }

        /** Sends the "do frame" message, forwarding the Choreographer event. */
        public void sendDoFrame(long frameTimeNanos) {
            sendMessage(obtainMessage(MSG_DO_FRAME, (int) (frameTimeNanos >> 32), (int) frameTimeNanos));
        }

        /** Sends a new value for some render option. */
        public void sendSetRenderOption(boolean option) {
            sendMessage(obtainMessage(MSG_OPTION, option ? 1:0, 0));
        }

        /** Sends the "shutdown" message, which tells the render thread to halt. */
        public void sendShutdown() {
            sendMessage(obtainMessage(RenderHandler.MSG_SHUTDOWN));
        }

        @Override  // runs on RenderThread
        public void handleMessage(Message msg) {
            int what = msg.what;
            //Log.d(TAG, "RenderHandler [" + this + "]: what=" + what);

            RenderThread renderThread = mWeakRenderThread.get();
            if (renderThread == null) {
                Log.w(TAG, "RenderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_SURFACE_CREATED:
                    renderThread.surfaceCreated();
                    break;
                case MSG_SURFACE_CHANGED:
                    renderThread.surfaceChanged(msg.arg1, msg.arg2);
                    break;
                case MSG_DO_FRAME:
                    long timestamp = (((long) msg.arg1) << 32) | (((long) msg.arg2) & 0xffffffffL);
                    renderThread.doFrame(timestamp);
                    break;
                case MSG_OPTION:
                    renderThread.setRenderOption(msg.arg1 != 0);
                    break;
                case MSG_SHUTDOWN:
                    renderThread.shutdown();
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
    }
}
