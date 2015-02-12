package com.max.vectormap;

import android.content.Context;
import android.content.res.AssetManager;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private Map<Integer, List<Tile>> tileMap = new HashMap<>();

    // mMVPMatrix is an abbreviation for "Model View Projection Matrix"
    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mRotationMatrix = new float[16];

    private float mAngle;

    private float screenRatio;
    private float nearPlane = 0.01f;

    public static final int GLOBAL_OFS_X = 400000;
    public static final int GLOBAL_OFS_Y = 6200000;

    public float centerUtmX = 400000-GLOBAL_OFS_X, centerUtmY = 6170000-GLOBAL_OFS_Y, scaleFactor = 4096;

    public CustomGLRenderer(Context context) {
        this.context = context;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {

        // Set the background frame color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        loadAllTris();
    }

    class Tile {
        final int tx, ty;
        final int size;
        final int surfaceType;
        final Triangle tri;

        public Tile(String asset) {
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(context.getAssets().open(asset), 65536))) {
                int vertexCount = dis.readInt();
                int triCount = dis.readInt();
                tx = dis.readInt();
                ty = dis.readInt();
                size = dis.readInt();
                surfaceType = dis.readInt();

                float[] verts = new float[vertexCount * 2]; // 2 coords per vertex
                for (int k = 0; k < vertexCount*2; k += 2) {
                    verts[k] = dis.readInt() - GLOBAL_OFS_X;
                    verts[k+1] = dis.readInt() - GLOBAL_OFS_Y;
                }

                int[] tris = new int[triCount * 3]; // 3 vertices per tri
                for (int k = 0; k < triCount*3; ++k)
                    tris[k] = dis.readInt();

                tri = new Triangle(verts, tris, surfaceType);
            } catch (IOException ioe) {
                throw new RuntimeException("Error loading triangles", ioe);
            }
        }
    }

    /** Load and initialize all triangle tiles from disk. */
    private void loadAllTris() {
        Pattern p = Pattern.compile(".+_(\\d+)_(\\d+)_(\\d+)\\.tri");

        AssetManager manager = context.getAssets();
        String[] assets;
        try {
            assets = manager.list("tris");
        } catch (IOException e) {
            throw new IllegalStateException("Error loading assets", e);
        }

        for (String asset : assets) {
            Matcher m = p.matcher(asset);
            if (m.find()) {
                // below info is contained in the tile anyway, but eventually we want to avoid pre-loading all tiles
//                int tx = Integer.valueOf(m.group(1));
//                int ty = Integer.valueOf(m.group(2));
//                int surfaceType = Integer.valueOf(m.group(3));
                Tile tile = new Tile("tris/" + asset);
                int layer = tile.size == 16384 ? 0 : (tile.size == 65536 ? 1 : (tile.size == 262144 ? 2 : (tile.size == 1048576 ? 3 : -1)));
                if (layer == -1)
                    throw new IllegalStateException("Unknown tile size: "+tile.size+" for tile <" + asset + ">");
                int tilePos = getTilePos(layer, tile.tx, tile.ty);
                List<Tile> tileTypes = tileMap.get(tilePos);
                if (tileTypes == null)
                    tileMap.put(tilePos, tileTypes = new ArrayList<>());
                tileTypes.add(tile);
            }
        }
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

    static final int getTilePos(int layer, int tx, int ty) {
        // layer: 4 bits (0-15)
        // tx/ty: 14 bits (0-16383)
        return (layer << 28) + (tx << 14) + ty;
    }

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

        float[] scratch = new float[16];

        // Draw background color
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Set the camera position (View matrix)
        Matrix.setLookAtM(mViewMatrix, 0, centerUtmX, centerUtmY, getCameraDistance(), centerUtmX, centerUtmY, 0f, 0f, 1.0f, 0.0f);

        // Calculate the projection and view transformation
        Matrix.multiplyMM(mMVPMatrix, 0, mProjectionMatrix, 0, mViewMatrix, 0);

        // Rotate scene
        // long time = SystemClock.uptimeMillis() % 4000L;
        // float angle = 0.090f * ((int) time);
        Matrix.setRotateM(mRotationMatrix, 0, mAngle, 0, 0, 1.0f);
        Matrix.multiplyMM(scratch, 0, mMVPMatrix, 0, mRotationMatrix, 0);

//        GLES20.glDisable(GLES20.GL_CULL_FACE);
//        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // To test overdraw: use glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) and half all RGB values!
//        GLES20.glEnable(GLES20.GL_BLEND);
//        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
        GLES20.glDisable(GLES20.GL_BLEND);

        int[] tileShifts = {14, 16, 18, 20};
        float[] layerShifts = {2048, 4096, 16384};
        int layer = scaleFactor > layerShifts[2] ? 0 : (scaleFactor > layerShifts[1] ? 1 : (scaleFactor > layerShifts[0] ? 2 : 3));

        getScreenEdges(screenEdges);

        int tx0 = GLOBAL_OFS_X + screenEdges[0] >> tileShifts[layer];
        int ty0 = GLOBAL_OFS_Y + screenEdges[1] >> tileShifts[layer];
        int tx1 = GLOBAL_OFS_X + screenEdges[2] >> tileShifts[layer];
        int ty1 = GLOBAL_OFS_Y + screenEdges[3] >> tileShifts[layer];

        Log.v("View", "tx="+tx0+"-"+tx1+", ty="+ty0+"-"+ty1+", layer="+layer+", edges=["+(GLOBAL_OFS_X+screenEdges[0])+","+(GLOBAL_OFS_Y+screenEdges[1])+" - "+(GLOBAL_OFS_X+screenEdges[2])+","+(GLOBAL_OFS_Y+screenEdges[3])+"]");

        for (int ty = ty0; ty <= ty1; ++ty) {
            for (int tx = tx0; tx <= tx1; ++tx) {
                List<Tile> tiles = tileMap.get(getTilePos(layer, tx, ty));
                if (tiles != null)
                    for (Tile tile : tiles)
                        tile.tri.draw(scratch, 1.0f);
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