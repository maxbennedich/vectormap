package com.max.vectormap;

import android.app.Activity;
import android.app.ActivityManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;

public class MainActivity extends Activity {

    private GLSurfaceView mGLView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Common.logAvailableMemory(this);

        mGLView = new VectorMapSurfaceView(this);
        setContentView(mGLView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Consider de-allocating objects that consume significant memory here.
        mGLView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-allocate objects here if they were de-allocated for onPause().
        mGLView.onResume();
    }
}