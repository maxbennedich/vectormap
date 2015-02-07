package com.max.vectormap;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides drawing instructions for a GLSurfaceView object. This class
 * must override the OpenGL ES drawing lifecycle methods:
 * <ul>
 *   <li>{@link android.opengl.GLSurfaceView.Renderer#onSurfaceCreated}</li>
 *   <li>{@link android.opengl.GLSurfaceView.Renderer#onDrawFrame}</li>
 *   <li>{@link android.opengl.GLSurfaceView.Renderer#onSurfaceChanged}</li>
 * </ul>
 */
public class CustomGLRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "MyGLRenderer";

    private final Context context;

    private List<Triangle> mTris = new ArrayList<>();

    // mMVPMatrix is an abbreviation for "Model View Projection Matrix"
    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mRotationMatrix = new float[16];

    private float mAngle;

    public float centerUtmX = 721391, centerUtmY = 6388723, scaleFactor = 4096;

    public CustomGLRenderer(Context context) {
        this.context = context;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {

        // Set the background frame color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        mTris.add(loadTris(R.raw.tris10_0));
        mTris.add(loadTris(R.raw.tris0));
        mTris.add(loadTris(R.raw.tris1));
        mTris.add(loadTris(R.raw.tris2));
        mTris.add(loadTris(R.raw.tris3));
        mTris.add(loadTris(R.raw.tris4));
    }

    private Triangle loadTris(int resourceId) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(context.getResources().openRawResource(resourceId), 65536))) {
            int triCount = dis.readInt();

            float[] verts = new float[triCount * 9]; // 9 floats per triangle (3 vertices, each with x, y, type)
            for (int k = 0; k < triCount; ++k) {
                for (int c = 0; c < 6; ++c)
                    verts[k*9 + c + (c/2)] = dis.readInt();
                verts[k*9+2] = verts[k*9+5] = verts[k*9+8] = dis.readInt();
            }

            return new Triangle(verts);
        } catch (IOException ioe) {
            throw new RuntimeException("Error loading triangles", ioe);
        }
    }

    private long prevNanoTime = System.nanoTime();

    private void logFPS() {
        long time = System.nanoTime();
        Log.v("PerfLog", String.format("FPS=%.1f", 1e9/(time-prevNanoTime)));
        prevNanoTime = time;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        logFPS();

        float[] scratch = new float[16];

        // Draw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Set the camera position (View matrix)
        Matrix.setLookAtM(mViewMatrix, 0, centerUtmX, centerUtmY, 1024 / scaleFactor, centerUtmX, centerUtmY, 0f, 0f, 1.0f, 0.0f);

        // Calculate the projection and view transformation
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);

//        // Draw square
//        mSquare.draw(mMVPMatrix);

        // Create a rotation for the triangle

        // Use the following code to generate constant rotation.
        // Leave this code out when using TouchEvents.
        // long time = SystemClock.uptimeMillis() % 4000L;
        // float angle = 0.090f * ((int) time);

        Matrix.setRotateM(mRotationMatrix, 0, mAngle, 0, 0, 1.0f);

        // Combine the rotation matrix with the projection and camera view
        // Note that the mMVPMatrix factor *must be first* in order
        // for the matrix multiplication product to be correct.
        Matrix.multiplyMM(scratch, 0, mMVPMatrix, 0, mRotationMatrix, 0);

//        GLES20.glDisable(GLES20.GL_CULL_FACE);
//        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

//        GLES20.glDisable(GLES20.GL_BLEND);
//        GLES20.glEnable(GLES20.GL_BLEND);
//        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
//        for (int t = 1; t < mTris.size(); ++t)
//            mTris.get(t).draw(scratch, 1.0f);
//        mTris.get(0).draw(scratch, 1.0f);

        // To test overdraw: use glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) and half all RGB values!

        // Draw triangles
        GLES20.glDisable(GLES20.GL_BLEND);
        if (scaleFactor < 8192) {
            mTris.get(0).draw(scratch, 1.0f);
        } else if (scaleFactor > 16384) {
            for (int t = 1; t < mTris.size(); ++t)
                mTris.get(t).draw(scratch, 1.0f);
        } else {
            for (int t = 1; t < mTris.size(); ++t)
                mTris.get(t).draw(scratch, 1.0f);

            float blend = (16384-scaleFactor)/8192;
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            mTris.get(0).draw(scratch, blend);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.v("VectorMap", "renderer size w="+width+", h="+height);
        // Adjust the viewport based on geometry changes,
        // such as screen rotation
        GLES20.glViewport(0, 0, width, height);

        float ratio = (float) width / height;

        // this projection matrix is applied to object coordinates
        // in the onDrawFrame() method
        float near = 1f;
        Matrix.frustumM(mProjectionMatrix, 0, -ratio*near, ratio*near, -1f*near, 1f*near, 0.00001f*near, 16384);

    }

    /**
     * Utility method for compiling a OpenGL shader.
     *
     * <p><strong>Note:</strong> When developing shaders, use the checkGlError()
     * method to debug shader coding errors.</p>
     *
     * @param type - Vertex or fragment shader type.
     * @param shaderCode - String containing the shader code.
     * @return - Returns an id for the shader.
     */
    public static int loadShader(int type, String shaderCode){

        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        int shader = GLES20.glCreateShader(type);

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        return shader;
    }

    /**
     * Utility method for debugging OpenGL calls. Provide the name of the call
     * just after making it:
     *
     * <pre>
     * mColorHandle = GLES20.glGetUniformLocation(mProgram, "vColor");
     * MyGLRenderer.checkGlError("glGetUniformLocation");</pre>
     *
     * If the operation is not successful, the check throws an error.
     *
     * @param glOperation - Name of the OpenGL call to check.
     */
    public static void checkGlError(String glOperation) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, glOperation + ": glError " + error);
            throw new RuntimeException(glOperation + ": glError " + error);
        }
    }

    /**
     * Returns the rotation angle of the triangle shape (mTriangle).
     *
     * @return - A float representing the rotation angle.
     */
    public float getAngle() {
        return mAngle;
    }

    /**
     * Sets the rotation angle of the triangle shape (mTriangle).
     */
    public void setAngle(float angle) {
        mAngle = angle;
    }

}