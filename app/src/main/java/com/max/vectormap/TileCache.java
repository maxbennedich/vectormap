package com.max.vectormap;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TODO a cache that removes multiple entries if needed (based on size)
 * TODO we should probably also assign weights to entries and e.g. remove large/remote/zoomed in entries first
 */
public class TileCache extends LinkedHashMap<Integer, Tile> {
    private static final int GPU_CACHE_BYTES = 20 * 1024 * 1024 * 100;

    /** Contains all tile indices for which we have a tile on disk. */
    Set<Integer> existingTiles = new HashSet<>();

    private final Context context;
    private final TileLoader tileLoader;

    private long gpuBytes = 0;

    /** Creates a new tile cache and inventories all tiles available on disk. */
    public TileCache(Context context) {
        super(64, 0.75f, true);

        this.context = context;
        tileLoader = new TileLoader(context);
        inventoryTris();
    }

    private Tile loadTile(Integer tp) {
        return existingTiles.contains(tp) ? tileLoader.loadTile(tp) : null;
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
                int layer = size == 8192 ? 0 : (size == 32768 ? 1 : (size == 131072 ? 2 : (size == 524288 ? 3 : -1)));
                int tx = Integer.valueOf(m.group(2));
                int ty = Integer.valueOf(m.group(3));
                int tilePos = VectorMapRenderer.getTilePos(layer, tx, ty);
                existingTiles.add(tilePos);
            }
        }
    }

    /**
     * Override get to load tiles not in the cache and insert them into the cache.
     * @return Null if tile could not be loaded (typically out of bounds).
     */
    @Override public Tile get(Object key) {
        Tile tile = super.get(key);
        if (tile != null)
            return tile;

        Integer tp = (Integer) key;
        if ((tile = loadTile(tp)) != null) {
            put(tp, tile);
            gpuBytes += tile.getGPUBytes();
            Log.d("Cache", "Loading tile " + tile.size + "," + tile.tx + "," + tile.ty + ": " + tile.getGPUBytes() + " bytes, new cache size: " + ((gpuBytes + 512 * 1024) / 1024) + " KB");
        }
        return tile;
    }

    @Override protected boolean removeEldestEntry(Entry<Integer, Tile> eldest) {
        boolean remove = gpuBytes > GPU_CACHE_BYTES;
        if (remove) {
            Tile tile = eldest.getValue();
            tile.delete();
            gpuBytes -= tile.getGPUBytes();
            Log.d("Cache", "Deleting tile " + tile.size + "," + tile.tx + "," + tile.ty + ": " + tile.getGPUBytes() + " bytes, new cache size: "+((gpuBytes +512*1024)/1024)+" KB");
        }
        return remove;
    }
}
