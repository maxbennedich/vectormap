package com.max.vectormap;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;

/**
 * A view container where OpenGL ES graphics can be drawn on screen.
 * This view can also be used to capture touch events, such as a user
 * interacting with drawn objects.
 */
class CustomGLSurfaceView extends GLSurfaceView {

    private final CustomGLRenderer mRenderer;

    public CustomGLSurfaceView(Context context) {
        super(context);

        zoomDetector = new ScaleGestureDetector(getContext(), new ScaleListener());

        // Create an OpenGL ES 2.0 context.
        setEGLContextClientVersion(2);

        // Set the Renderer for drawing on the GLSurfaceView
        mRenderer = new CustomGLRenderer(context);
        setRenderer(mRenderer);

        // Render the view only when there is a change in the drawing data
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    float screenMidX, screenMidY;

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        super.surfaceChanged(holder, format, w, h);
        Log.i("VectorMap", "Surface changed, wid="+w+", h="+h);
        screenMidX = w * 0.5f;
        screenMidY = h * 0.5f;
    }

    final float pixelToUtm(float pixel) {
        // using camera
        return pixel * 1e5f / mRenderer.scaleFactor;
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
                    mRenderer.centerUtmX -= pixelToUtm(dx);
                    mRenderer.centerUtmY += pixelToUtm(dy);
                    panPrevX = event.getX();
                    panPrevY = event.getY();

//                    mapCenterUpdated();

                    Log.v("Touch", "dx="+dx+", dy="+dy+", x="+(mRenderer.centerUtmX+CustomGLRenderer.GLOBAL_OFS_X)+", y="+(mRenderer.centerUtmY+CustomGLRenderer.GLOBAL_OFS_Y)+", scale="+mRenderer.scaleFactor);

                    requestRender();
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

    /** Ensure scale factor is valid and update all scale factor related variables. */
    private void scaleFactorUpdated() {
        mRenderer.scaleFactor = Math.max(1, Math.min(64*65536, mRenderer.scaleFactor));

//        zoomLevel = 31 - Integer.numberOfLeadingZeros((int)(scaleFactor+(1e-12)));
//        zoomLevel = Math.max(MIN_ZOOM_LEVEL, Math.min(MAX_ZOOM_LEVEL, zoomLevel));
//        scalingZoom = scaleFactor / (1 << zoomLevel);
    }

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
            float oldScaleFactor = mRenderer.scaleFactor;

            mRenderer.scaleFactor *= detector.getScaleFactor();
            scaleFactorUpdated();

            // translate due to focus point moving and zoom due to pinch
            float focusX = detector.getFocusX(), focusY = detector.getFocusY();
            float omScale = 1 - mRenderer.scaleFactor / oldScaleFactor;
            mRenderer.centerUtmX += pixelToUtm((screenMidX - focusX) * omScale - focusX + prevFocusX);
            mRenderer.centerUtmY -= pixelToUtm((screenMidY - focusY) * omScale - focusY + prevFocusY);

//            mapCenterUpdated();

            prevFocusX = focusX;
            prevFocusY = focusY;

            requestRender();

            return true;
        }
    }

/*    private final float TOUCH_SCALE_FACTOR = 180.0f / 320;
    private float mPreviousX;
    private float mPreviousY;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // MotionEvent reports input details from the touch screen
        // and other input controls. In this case, you are only
        // interested in events where the touch position changed.

        float x = e.getX();
        float y = e.getY();

        switch (e.getAction()) {
            case MotionEvent.ACTION_MOVE:

                float dx = x - mPreviousX;
                float dy = y - mPreviousY;

                // reverse direction of rotation above the mid-line
                if (y > getHeight() / 2) {
                    dx = dx * -1 ;
                }

                // reverse direction of rotation to left of the mid-line
                if (x < getWidth() / 2) {
                    dy = dy * -1 ;
                }

                mRenderer.setAngle(
                        mRenderer.getAngle() +
                                ((dx + dy) * TOUCH_SCALE_FACTOR));  // = 180.0f / 320
                requestRender();
        }

        mPreviousX = x;
        mPreviousY = y;
        return true;
    }
*/
}
