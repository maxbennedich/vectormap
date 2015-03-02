package com.max.vectormap;

import android.os.Bundle;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.app.Activity;

public class ChoreographerActivity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    public static final String TAG = "Choreographer";

    // Rendering code runs on this thread.  The thread's life span is tied to the Surface.
    private ChoreographerRenderThread mRenderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "ChoreographerActivity: onCreate");

        Common.logAvailableMemory(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SurfaceView sv = (SurfaceView) findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(this);

        zoomDetector = new ScaleGestureDetector(this, new ScaleListener());
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
        mRenderThread = new ChoreographerRenderThread(sv.getHolder(), this);
        mRenderThread.setName("VectorMap GL render");
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

        screenMidX = width * 0.5f;
        screenMidY = height * 0.5f;
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

    float screenMidX, screenMidY;

    final float pixelToUtm(float pixel) {
        // using camera
        return pixel * 1e5f / mRenderThread.globalScaleFactor;
    }

    enum ActionMode { NONE, PAN, ZOOM }

    private ScaleGestureDetector zoomDetector;
    private ActionMode actionMode = ActionMode.NONE;
    private float panPrevX, panPrevY;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                panPrevX = event.getX();
                panPrevY = event.getY();
                actionMode = ActionMode.PAN;
                break;
            case MotionEvent.ACTION_MOVE:
                if (actionMode == ActionMode.PAN) {
                    float dx = event.getX() - panPrevX;
                    float dy = event.getY() - panPrevY;
                    panPrevX = event.getX();
                    panPrevY = event.getY();

                    synchronized (mRenderThread.CAMERA_POSITION_LOCK) {
                        mRenderThread.globalCenterUtmX -= pixelToUtm(dx);
                        mRenderThread.globalCenterUtmY += pixelToUtm(dy);
                    }

//                    mapCenterUpdated();

//                    Log.v("Touch", "dx="+dx+", dy="+dy+", x="+(mRenderer.centerUtmX+ VectorMapRenderer.GLOBAL_OFS_X)+", y="+(mRenderer.centerUtmY+ VectorMapRenderer.GLOBAL_OFS_Y)+", scale="+mRenderer.scaleFactor);

//                    requestRender();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                actionMode = ActionMode.ZOOM;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                actionMode = ActionMode.NONE;
                break;
        }

        if (actionMode == ActionMode.ZOOM)
            zoomDetector.onTouchEvent(event);

        return true;
    }

    /** Ensure map center is valid and for example not scrolled outside map extreme borders. */
//    private void mapCenterUpdated() {
//        double utmMidX = pixelToUtm(screenMidX);
//        double utmMidY = pixelToUtm(screenMidY);
//        centerUtmX = Math.max(MapConstants.UTM_EXTREME_X0 + utmMidX, Math.min(MapConstants.UTM_EXTREME_X1 - utmMidX, centerUtmX));
//        centerUtmY = Math.max(MapConstants.UTM_EXTREME_Y0 + utmMidY, Math.min(MapConstants.UTM_EXTREME_Y1 - utmMidY, centerUtmY));
//    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        float prevFocusX, prevFocusY;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            prevFocusX = detector.getFocusX();
            prevFocusY = detector.getFocusY();
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float focusX = detector.getFocusX(), focusY = detector.getFocusY();

            synchronized (mRenderThread.CAMERA_POSITION_LOCK) {
                float oldScaleFactor = mRenderThread.globalScaleFactor;

                mRenderThread.globalScaleFactor *= detector.getScaleFactor();
                mRenderThread.globalScaleFactor = Math.max(1, Math.min(64 * 65536, mRenderThread.globalScaleFactor));

                // translate due to focus point moving and zoom due to pinch
                float omScale = 1 - mRenderThread.globalScaleFactor / oldScaleFactor;
                mRenderThread.globalCenterUtmX += pixelToUtm((screenMidX - focusX) * omScale - focusX + prevFocusX);
                mRenderThread.globalCenterUtmY -= pixelToUtm((screenMidY - focusY) * omScale - focusY + prevFocusY);

//             mapCenterUpdated();
            }

            prevFocusX = focusX;
            prevFocusY = focusY;

//            requestRender();

            return true;
        }
    }
}
