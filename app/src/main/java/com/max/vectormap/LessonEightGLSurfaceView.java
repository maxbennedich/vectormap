package com.max.vectormap;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.Toast;

public class LessonEightGLSurfaceView extends GLSurfaceView implements ErrorHandler
{
    private LessonEightRenderer renderer;

    // Offsets for touch events
    private float previousX;
    private float previousY;

    private float density;

    public LessonEightGLSurfaceView(Context context)
    {
        super(context);
    }

    public LessonEightGLSurfaceView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
    }

    @Override
    public void handleError(final ErrorType errorType, final String cause) {
        // Queue on UI thread.
        post(new Runnable() {
            @Override
            public void run() {
                final String text;

                switch (errorType) {
                    case BUFFER_CREATION_ERROR:
                        text = String.format("Could not create VBO: %s", cause);
                        break;
                    default:
                        text = String.format("Unknown error: %s", cause);
                }

                Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();

            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        if (event != null)
        {
            float x = event.getX();
            float y = event.getY();

            if (event.getAction() == MotionEvent.ACTION_MOVE)
            {
                if (renderer != null)
                {
                    float deltaX = (x - previousX) / density / 2f;
                    float deltaY = (y - previousY) / density / 2f;

                    renderer.deltaX += deltaX;
                    renderer.deltaY += deltaY;
                }
            }

            previousX = x;
            previousY = y;

            return true;
        }
        else
        {
            return super.onTouchEvent(event);
        }
    }

    // Hides superclass method.
    public void setRenderer(LessonEightRenderer renderer, float density)
    {
        this.renderer = renderer;
        this.density = density;
        super.setRenderer(renderer);
    }
}