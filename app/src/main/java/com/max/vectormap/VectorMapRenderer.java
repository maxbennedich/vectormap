package com.max.vectormap;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.util.Log;

import com.max.integercompression.Composition;
import com.max.integercompression.FastPFOR;
import com.max.integercompression.IntWrapper;
import com.max.integercompression.IntegerCODEC;
import com.max.integercompression.VariableByte;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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
public class VectorMapRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "MyGLRenderer";

    private final Context context;

    private LinkedHashMap<Integer, Tile> tileCache = getTileCache();

    private static final int GPU_CACHE_BYTES = 20 * 1024 * 1024;

    /** Contains all tile indices for which we have a tile on disk. */
    private Set<Integer> existingTiles = new HashSet<>();

    private static final int NR_SURFACE_TYPES = 10;

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

    public VectorMapRenderer(Context context) {
        this.context = context;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {

        // Set the background frame color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        inventoryTris();
    }

    // TODO a cache that removes multiple entries if needed (based on size)
    // TODO we should probably also assign weights to entries and e.g. remove large/remote/zoomed in entries first
    private LinkedHashMap<Integer, Tile> getTileCache() {
        return new LinkedHashMap<Integer, Tile>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            private long gpuBytes = 0;

            /**
             * Override get to load tiles not in the cache and insert them into the cache.
             *
             * @return Null if tile could not be loaded (typically out of bounds).
             */
            @Override public Tile get(Object key) {
                Tile tile = super.get(key);
                if (tile != null)
                    return tile;

                Integer tp = (Integer) key;
                if ((tile = loadTile(tp)) != null) {
                    put(tp, tile);
                    gpuBytes += tile.getBytesInGPU();
                    Log.d("Cache", "Loading tile " + tile.size + "," + tile.tx + "," + tile.ty + ": " + tile.getBytesInGPU() + " bytes, new cache size: "+((gpuBytes +512*1024)/1024/1024)+" MB");
                }
                return tile;
            }

            @Override protected boolean removeEldestEntry(Entry<Integer, Tile> eldest) {
                boolean remove = gpuBytes > GPU_CACHE_BYTES;
                if (remove) {
                    Tile tile = eldest.getValue();
                    for (SurfaceTypeTile stTile : tile.tiles)
                        stTile.tri.delete();
                    gpuBytes -= tile.getBytesInGPU();
                    Log.d("Cache", "Deleting tile " + tile.size + "," + tile.tx + "," + tile.ty + ": " + tile.getBytesInGPU() + " bytes, new cache size: "+((gpuBytes +512*1024)/1024/1024)+" MB");
                }
                return remove;
            }
        };
    }

    private Tile loadTile(Integer tp) {
        if (existingTiles.contains(tp)) {
            int layer = getLayer(tp);
            int tx = getTX(tp), ty = getTY(tp);
            int size = layer == 0 ? 16384 : (layer == 1 ? 65536 : (layer == 2 ? 262144 : (layer == 3 ? 1048576 : -1)));

            String tileName = "tris/tri_" + size + "_" + tx + "_" + ty + ".tri";
            List<SurfaceTypeTile> typeTiles = new ArrayList<>();

            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(context.getAssets().open(tileName), 65536))) {
                // per tile header data
                int vertexCount = dis.readInt();
                tx = dis.readInt();
                ty = dis.readInt();
                size = dis.readInt();

                // per surface type header data
                int[] triCount = new int[NR_SURFACE_TYPES];
                int[] stripCount = new int[NR_SURFACE_TYPES];
                int[] stripTriCount = new int[NR_SURFACE_TYPES];
                int[] fanCount = new int[NR_SURFACE_TYPES];
                int[] fanTriCount = new int[NR_SURFACE_TYPES];
                int[] primitiveCountBits = new int[NR_SURFACE_TYPES];
                for (int t = 0; t < NR_SURFACE_TYPES; ++t) {
                    triCount[t] = dis.readInt();
                    stripCount[t] = dis.readInt();
                    fanCount[t] = dis.readInt();
                    if (triCount[t] == 0 && stripCount[t] == 0 && fanCount[t] == 0)
                        continue;

                    stripTriCount[t] = dis.readInt();
                    fanTriCount[t] = dis.readInt();
                    primitiveCountBits[t] = dis.readInt();
                }

                BitReader br = new BitReader(dis);

                // major TODO don't duplicate vertices in all Triangle instances!

                // packed vertex data; shared across ALL surface types
                int ofsx = tx*size, ofsy = ty*size;
                int QUANT_BITS = 14;

//                int[] uncompressed = readPFORVertices(dis, vertexCount);
                int[] uncompressed = readBinaryPackedVertices(dis, br, vertexCount);

                float[] verts = new float[vertexCount * 2]; // 2 coords per vertex
                int prevCoord = -1;
                for (int k = 0; k < vertexCount*2; k += 2) {
                    // TODO could be solved by shifting and adding to speed things up
                    int coord = prevCoord + uncompressed[k/2] + 1;
                    prevCoord = coord;
                    double qpx = coord & ((1<<QUANT_BITS)-1);
                    double qpy = coord >> QUANT_BITS;
                    int px = (int)(qpx / ((1<<QUANT_BITS)-1) * size + 0.5);
                    int py = (int)(qpy / ((1<<QUANT_BITS)-1) * size + 0.5);
                    verts[k] = px + ofsx - GLOBAL_OFS_X;
                    verts[k + 1] = py + ofsy - GLOBAL_OFS_Y;
                }

                // per surface type index data
                for (int t = 0; t < NR_SURFACE_TYPES; ++t) {
                    if (triCount[t] == 0 && stripCount[t] == 0 && fanCount[t] == 0)
                        continue;
                    int[] tris = new int[(triCount[t] + stripTriCount[t] + fanTriCount[t]) * 3]; // 3 vertices per tri
                    int idxBits = log2(vertexCount);
                    readBinaryPackedTriIndices(br, idxBits, triCount[t], tris);
                    readBinaryPackedStripIndices(br, idxBits, stripCount[t], tris, triCount[t]*3, primitiveCountBits[t]);
                    readBinaryPackedFanIndices(br, idxBits, fanCount[t], tris, (triCount[t] + stripTriCount[t])*3, primitiveCountBits[t]);
//                int[] tris = readPFORData(dis, triCount * 3);

                    typeTiles.add(new SurfaceTypeTile(tx, ty, size, t, new TileRenderer(verts, tris, t)));
                }
            } catch (IOException ioe) {
                throw new RuntimeException("Error loading triangles", ioe);
            }

            return new Tile(layer, tx, ty, typeTiles);
        }
        return null;
    }

    class Tile {
        final int size;
        final int tx, ty;
        final List<SurfaceTypeTile> tiles;
        final int gpuBytes;

        public Tile(int size, int tx, int ty, List<SurfaceTypeTile> tiles) {
            this.size = size;
            this.tx = tx;
            this.ty = ty;
            this.tiles = tiles;

            int bytes = 0;
            for (SurfaceTypeTile t : tiles)
                bytes += t.tri.getBytesInGPU();
            this.gpuBytes = bytes;
        }

        int getBytesInGPU() {
            return gpuBytes;
        }
    }

    class SurfaceTypeTile {
        final int tx, ty;
        final int size;
        final int surfaceType;
        final TileRenderer tri;

        public SurfaceTypeTile(int tx, int ty, int size, int surfaceType, TileRenderer tri) {
            this.tx = tx;
            this.ty = ty;
            this.size = size;
            this.surfaceType = surfaceType;
            this.tri = tri;
        }
    }

    private int[] readPFORData(DataInputStream dis, int uncompressedSize) throws IOException {
        // read compressed data from disk
        int compressedLength = dis.readInt();
        int[] compressed = new int[compressedLength];
        for (int n = 0; n < compressedLength; ++n)
            compressed[n] = dis.readInt();

        // decompress
        IntegerCODEC codec = new Composition(new FastPFOR(), new VariableByte());
        int[] uncompressed = new int[uncompressedSize];
        codec.uncompress(compressed, new IntWrapper(0), compressed.length, uncompressed, new IntWrapper(0));

        return uncompressed;
    }

    private int[] readBinaryPackedVertices(DataInputStream dis, BitReader br, int vertexCount) throws IOException {
        int[] breakpoints = new int[4];
        for (int k = 0; k < breakpoints.length; ++k)
            breakpoints[k] = dis.readByte();

        int[] uncompressed = new int[vertexCount];
        for (int k = 0; k < vertexCount; ++k) {
            int bitsBits = br.read(2);
            int bits = breakpoints[bitsBits];
            uncompressed[k] = br.read(bits);
        }

        return uncompressed;
    }

    private void readBinaryPackedTriIndices(BitReader br, int idxBits, int triCount, int[] tris) throws IOException {
        for (int k = 0; k < triCount*3; ++k)
            tris[k] = br.read(idxBits);
    }

    private void readBinaryPackedStripIndices(BitReader br, int idxBits, int stripCount, int[] tris, int offset, int maxIndexBits) throws IOException {
        for (int k = 0; k < stripCount; ++k) {
            int stripLength = br.read(maxIndexBits);
            int v0 = br.read(idxBits), v1 = br.read(idxBits);
            for (int t = 0; t < stripLength; ++t) {
                tris[offset++] = v0;
                tris[offset++] = v0 = v1;
                tris[offset++] = v1 = br.read(idxBits);
            }
        }
    }

    private void readBinaryPackedFanIndices(BitReader br, int idxBits, int fanCount, int[] tris, int offset, int maxIndexBits) throws IOException {
        for (int k = 0; k < fanCount; ++k) {
            int fanLength = br.read(maxIndexBits);
            int v0 = br.read(idxBits), v1 = br.read(idxBits);
            for (int t = 0; t < fanLength; ++t) {
                tris[offset++] = v0;
                tris[offset++] = v1;
                tris[offset++] = v1 = br.read(idxBits);
            }
        }
    }

    /** 0 -> 1, 1 -> 1, 2 -> 2, 3 -> 2, 4 -> 3, 5 -> 3, etc. Note: returns 1 for k=0 since 1 bit is needed to encode 0. */
    public static final int log2(int k) {
        return k == 0 ? 1 : (32 - Integer.numberOfLeadingZeros(k));
    }

    /** Does not load anything from disk, only inventories what's there. */
    private void inventoryTris() {
        Pattern p = Pattern.compile("tri_(\\d+)_(\\d+)_(\\d+)\\.tri");

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
                int size = Integer.valueOf(m.group(1));
                int layer = size == 16384 ? 0 : (size == 65536 ? 1 : (size == 262144 ? 2 : (size == 1048576 ? 3 : -1)));
                int tx = Integer.valueOf(m.group(2));
                int ty = Integer.valueOf(m.group(3));
                int tilePos = getTilePos(layer, tx, ty);
                existingTiles.add(tilePos);
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

    static final int getLayer(int tilePos) { return tilePos >>> 28; }
    static final int getTX(int tilePos) { return (tilePos >> 14) & 0x3fff; }
    static final int getTY(int tilePos) { return tilePos & 0x3fff; }

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
                Tile tile = tileCache.get(getTilePos(layer, tx, ty));
                if (tile != null)
                    for (SurfaceTypeTile stTile : tile.tiles)
                        stTile.tri.draw(scratch, 1.0f);
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