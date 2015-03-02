package com.max.vectormap;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** This class deals with loading tiles from disk. Instances of this class are NOT thread safe. */
public class TileLoader {
    private final Context context;

    private final static int HASH_SIZE = 16384;
    private final static int BUCKET_BITS = 5;
    private final static int BUCKET_SIZE = 1 << BUCKET_BITS;
    final byte[] bucketLen = new byte[HASH_SIZE];
    short[] hashMap = new short[HASH_SIZE << BUCKET_BITS];

    int[] breakpoints = new int[4];

    public final static int MAX_VERTEX_COUNT = 65534;
    int[] intVerts = new int[MAX_VERTEX_COUNT];
    int[] newOrder = new int[MAX_VERTEX_COUNT];
    float[] verts = new float[MAX_VERTEX_COUNT*2];

    short[][] tris = new short[Constants.NR_SURFACE_TYPES][0];

    public TileLoader(Context context) {
        this.context = context;
    }

    private static final int hash(int x) {
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        x = ((x >>> 16) ^ x);
        return x;
    }

    /** Uncompressed vertex data into the intVerts array. */
    private void readBinaryPackedVertices(DataInputStream dis, BitReader br, int vertexCount) throws IOException {
        for (int k = 0; k < breakpoints.length; ++k)
            breakpoints[k] = dis.readByte();

        for (int k = 0; k < vertexCount; ++k) {
            int bitsBits = br.read(2);
            int bits = breakpoints[bitsBits];
            intVerts[k] = br.read(bits);
        }
    }

    private void readBinaryPackedTriIndices(BitReader br, int idxBits, int triCount, short[] tris) throws IOException {
        for (int k = 0; k < triCount*3; ++k)
            tris[k] = br.readShort(idxBits);
    }

    private void readBinaryPackedStripIndices(BitReader br, int idxBits, int stripCount, short[] tris, int offset, int maxIndexBits) throws IOException {
        for (int k = 0; k < stripCount; ++k) {
            int stripLength = br.read(maxIndexBits);
            short v0 = br.readShort(idxBits), v1 = br.readShort(idxBits);
            for (int t = 0; t < stripLength; ++t) {
                tris[offset++] = v0;
                tris[offset++] = v0 = v1;
                tris[offset++] = v1 = br.readShort(idxBits);
            }
        }
    }

    private void readBinaryPackedFanIndices(BitReader br, int idxBits, int fanCount, short[] tris, int offset, int maxIndexBits) throws IOException {
        for (int k = 0; k < fanCount; ++k) {
            int fanLength = br.read(maxIndexBits);
            short v0 = br.readShort(idxBits), v1 = br.readShort(idxBits);
            for (int t = 0; t < fanLength; ++t) {
                tris[offset++] = v0;
                tris[offset++] = v1;
                tris[offset++] = v1 = br.readShort(idxBits);
            }
        }
    }

    private static byte[] assetBuffer = new byte[65536];

    static class CustomBufferInputStream extends BufferedInputStream {
        public CustomBufferInputStream(InputStream in, byte[] buffer) {
            super(in, 1); // allocate the minimum buffer possible (somewhat of a hack)
            buf = buffer;
        }
    }

    /** Never returns null. */
    public Tile loadTile(int tp) {
        int layer = ChoreographerRenderThread.getLayer(tp);
        int tx = ChoreographerRenderThread.getTX(tp), ty = ChoreographerRenderThread.getTY(tp);
        int size = layer == 0 ? 8192 : (layer == 1 ? 32768 : (layer == 2 ? 131072 : (layer == 3 ? 524288 : -1)));

        String tileName = "tris/tri_" + size + "_" + tx + "_" + ty + ".tri";

        try (DataInputStream dis = new DataInputStream(new CustomBufferInputStream(context.getAssets().open(tileName), assetBuffer))) {
//            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(context.getAssets().open(tileName), 65536))) {
            // per tile header data
            int vertexCount = dis.readInt();
            if (vertexCount > MAX_VERTEX_COUNT)
                throw new IllegalStateException("Max vertex count is " + MAX_VERTEX_COUNT + ", got " + vertexCount);

            tx = dis.readInt();
            ty = dis.readInt();
            size = dis.readInt();

            // per surface type header data
            int[] triCount = new int[Constants.NR_SURFACE_TYPES];
            int[] stripCount = new int[Constants.NR_SURFACE_TYPES];
            int[] stripTriCount = new int[Constants.NR_SURFACE_TYPES];
            int[] fanCount = new int[Constants.NR_SURFACE_TYPES];
            int[] fanTriCount = new int[Constants.NR_SURFACE_TYPES];
            int[] primitiveCountBits = new int[Constants.NR_SURFACE_TYPES];
            for (int t = 0; t < Constants.NR_SURFACE_TYPES; ++t) {
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

            readBinaryPackedVertices(dis, br, vertexCount);

            Map<Integer, Pair<short[], Integer>> trisByType = new LinkedHashMap<>();

            // per surface type index data
            for (int t = 0; t < Constants.NR_SURFACE_TYPES; ++t) {
                if (triCount[t] == 0 && stripCount[t] == 0 && fanCount[t] == 0)
                    continue;
                int triIdxCount = (triCount[t] + stripTriCount[t] + fanTriCount[t]) * 3;
                if (tris[t].length < triIdxCount) {
                    Log.d("Memory", "Reallocate tri index " + t + ": " + tris[t].length*2/1024 + " KB -> " + triIdxCount*2/1024 + " KB");
                    tris[t] = new short[triIdxCount];
                }
                int idxBits = Common.log2(vertexCount);
                readBinaryPackedTriIndices(br, idxBits, triCount[t], tris[t]);
                readBinaryPackedStripIndices(br, idxBits, stripCount[t], tris[t], triCount[t]*3, primitiveCountBits[t]);
                readBinaryPackedFanIndices(br, idxBits, fanCount[t], tris[t], (triCount[t] + stripTriCount[t])*3, primitiveCountBits[t]);

                trisByType.put(t, Pair.create(tris[t], triIdxCount));
            }

            // delta-decode vertices
            int prevCoord = -1;
            for (int k = 0; k < vertexCount; ++k)
                prevCoord = intVerts[k] += prevCoord + 1;

            // reorder vertices by draw order and reindex index lists
            // using a custom hash map implementation (3 times faster than default java version)
            int newVertexCount = 0;
            Arrays.fill(bucketLen, (byte) 0);
            Log.d("VertexCount", "" + vertexCount);

            for (Map.Entry<Integer, Pair<short[], Integer>> tris : trisByType.entrySet()) {
                for (int n = 0; n < tris.getValue().second; ++n) {
                    int vi = intVerts[tris.getValue().first[n]&0xffff];
                    int hash = hash(vi) & (HASH_SIZE-1);
                    int bucket = hash << BUCKET_BITS;
                    int found = -1;
                    for (int k = 0; k < bucketLen[hash]; ++k) {
                        int idx = hashMap[bucket+k]&0xffff; // <-- can optimize here by explicitly storing the values in the hash map in addition
                        if (newOrder[idx] == vi) {          //     to the indices, this will however double the space used
                            found = idx;
                            break;
                        }
                    }
                    if (found == -1) {
                        newOrder[newVertexCount] = vi;
                        found = hashMap[bucket + bucketLen[hash]] = (short)newVertexCount++;
                        if (++bucketLen[hash] >= BUCKET_SIZE)
                            throw new IllegalStateException("Length " + bucketLen[hash] + " for vertex count " + newVertexCount + "/" + vertexCount);
                    }
                    tris.getValue().first[n] = (short)found; // reindex
                }
            }

            // un-quantize vertices
            int ofsx = tx*size, ofsy = ty*size;
            int QUANT_BITS = 13;
            for (int k = 0; k < vertexCount; ++k) {
                // TODO could be solved by shifting and adding to speed things up
                double qpx = newOrder[k] & ((1<<QUANT_BITS)-1);
                double qpy = newOrder[k] >> QUANT_BITS;
                int px = (int)(qpx / ((1<<QUANT_BITS)-1) * size + 0.5);
                int py = (int)(qpy / ((1<<QUANT_BITS)-1) * size + 0.5);
                verts[k*2] = px + ofsx - ChoreographerRenderThread.GLOBAL_OFS_X;
                verts[k*2+1] = py + ofsy - ChoreographerRenderThread.GLOBAL_OFS_Y;
            }

            return new Tile(layer, tx, ty, verts, vertexCount, trisByType);
        } catch (IOException ioe) {
            throw new RuntimeException("Error loading triangles", ioe);
        }
    }
}
