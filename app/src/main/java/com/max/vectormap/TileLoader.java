package com.max.vectormap;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class TileLoader {
    private final Context context;

    final int HASH_SIZE = 16384;
    final int BUCKET_BITS = 5;
    final int BUCKET_SIZE = 1 << BUCKET_BITS;
    byte[] bucketLen = new byte[HASH_SIZE];
    short[] hashMap = new short[HASH_SIZE << BUCKET_BITS];

    public TileLoader(Context context) {
        this.context = context;
    }

    public static final int hash(int x) {
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        x = ((x >>> 16) ^ x) * 0x45d9f3b;
        x = ((x >>> 16) ^ x);
        return x;
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

    public Tile loadTile(int tp) {
        int layer = VectorMapRenderer.getLayer(tp);
        int tx = VectorMapRenderer.getTX(tp), ty = VectorMapRenderer.getTY(tp);
        int size = layer == 0 ? 8192 : (layer == 1 ? 32768 : (layer == 2 ? 131072 : (layer == 3 ? 524288 : -1)));

        String tileName = "tris/tri_" + size + "_" + tx + "_" + ty + ".tri";

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(context.getAssets().open(tileName), 65536))) {
            // per tile header data
            int vertexCount = dis.readInt();
            if (vertexCount >= 65535)
                throw new IllegalStateException("Max vertex count is 65534, got " + vertexCount);

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

            int[] uncompressedVertices = readBinaryPackedVertices(dis, br, vertexCount);

            Map<Integer, short[]> trisByType = new LinkedHashMap<>();

            // per surface type index data
            for (int t = 0; t < Constants.NR_SURFACE_TYPES; ++t) {
                if (triCount[t] == 0 && stripCount[t] == 0 && fanCount[t] == 0)
                    continue;
                short[] tris = new short[(triCount[t] + stripTriCount[t] + fanTriCount[t]) * 3]; // 3 vertices per tri
                int idxBits = Common.log2(vertexCount);
                readBinaryPackedTriIndices(br, idxBits, triCount[t], tris);
                readBinaryPackedStripIndices(br, idxBits, stripCount[t], tris, triCount[t]*3, primitiveCountBits[t]);
                readBinaryPackedFanIndices(br, idxBits, fanCount[t], tris, (triCount[t] + stripTriCount[t])*3, primitiveCountBits[t]);

                trisByType.put(t, tris);
            }

            // delta-decode vertices
            int[] intVerts = new int[vertexCount];
            int prevCoord = -1;
            for (int k = 0; k < vertexCount; ++k)
                prevCoord = intVerts[k] = prevCoord + uncompressedVertices[k] + 1;

            // reorder vertices by draw order and reindex index lists
            // using a custom hash map implementation (3 times faster than default java version)
            int[] newOrder = new int[vertexCount];
            int newVertexCount = 0;
            Arrays.fill(bucketLen, (byte) 0);
            Log.d("VertexCount", "" + vertexCount);

            for (Map.Entry<Integer, short[]> tris : trisByType.entrySet()) {
                for (int n = 0; n < tris.getValue().length; ++n) {
                    int vi = intVerts[tris.getValue()[n]&0xffff];
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
                    tris.getValue()[n] = (short)found; // reindex
                }
            }

            // un-quantize vertices
            float[] verts = new float[vertexCount * 2]; // 2 coords per vertex
            int ofsx = tx*size, ofsy = ty*size;
            int QUANT_BITS = 13;
            int vi = 0;
            for (int coord : newOrder) {
                // TODO could be solved by shifting and adding to speed things up
                double qpx = coord & ((1<<QUANT_BITS)-1);
                double qpy = coord >> QUANT_BITS;
                int px = (int)(qpx / ((1<<QUANT_BITS)-1) * size + 0.5);
                int py = (int)(qpy / ((1<<QUANT_BITS)-1) * size + 0.5);
                verts[vi++] = px + ofsx - VectorMapRenderer.GLOBAL_OFS_X;
                verts[vi++] = py + ofsy - VectorMapRenderer.GLOBAL_OFS_Y;
            }

            return new Tile(layer, tx, ty, verts, trisByType);
        } catch (IOException ioe) {
            throw new RuntimeException("Error loading triangles", ioe);
        }
    }
}
