package com.max.vectormap;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TileCache {

    Map<Integer, Tile> cache = new ConcurrentHashMap<>();

    /** Contains all tile indices for which we have a tile on disk. */
    Set<Integer> existingTiles = new HashSet<>();

    private final Context context;
    private final TileLoader tileLoader;

    private final TileDiskLoader tileDiskLoader;

    /** Creates a new tile cache and inventories all tiles available on disk. */
    public TileCache(Context context) {
        this.context = context;
        tileLoader = new TileLoader(context);
        inventoryTris();

        tileDiskLoader = new TileDiskLoader();
        new Thread(tileDiskLoader).start();
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
                int tilePos = ChoreographerRenderThread.getTilePos(layer, tx, ty);
                existingTiles.add(tilePos);
            }
        }
    }

    /**
     * @return Existing tile if already in cache, otherwise a freshly loaded tile.
     * Returns null if tile is out of bounds.
     */
    public Tile get(int tilePos, boolean logCacheMiss) {
        if (!existingTiles.contains(tilePos))
            return null;

        Tile tile = cache.get(tilePos);
        if (tile == null) {
            synchronized (this) {
                if ((tile = cache.get(tilePos)) == null) { // test again in case another thread just populated it
                    cache.put(tilePos, tile = tileLoader.loadTile(tilePos));
                    Log.d("TileCache", (logCacheMiss ? "CACHE MISS: " : "") + "Loaded tile " + ChoreographerRenderThread.getTilePos(tile.size, tile.tx, tile.ty));
                }
            }
        }
        return tile;
    }

    public void refreshForPosition(int[] screenEdges, float scaleFactor) {
        getTilesToLoad(screenEdges, scaleFactor);
        refresh();
    }

    private int[] tilesToLoadSorted = new int[256];
    private int[] tilesToLoad = new int[256];
    int tilesToLoadCount = 0;

    /**
     * Based on camera position and potentially other factors, figure out which tiles are either
     * needed right away or could be needed within short (e.g. if user pans or zooms).
     */
    private void getTilesToLoad(int[] screenEdges, float scaleFactor) {
        tilesToLoadCount = 0;
        int layer = scaleFactor > ChoreographerRenderThread.LAYER_SHIFTS[2] ? 0 : (scaleFactor > ChoreographerRenderThread.LAYER_SHIFTS[1] ? 1 : (scaleFactor > ChoreographerRenderThread.LAYER_SHIFTS[0] ? 2 : 3));

        // prio 1: tiles on screen
        int tx0 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[0] >> ChoreographerRenderThread.TILE_SHIFTS[layer];
        int ty0 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[1] >> ChoreographerRenderThread.TILE_SHIFTS[layer];
        int tx1 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[2] >> ChoreographerRenderThread.TILE_SHIFTS[layer];
        int ty1 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[3] >> ChoreographerRenderThread.TILE_SHIFTS[layer];

        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx)
                tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx, ty);

        // prio 2: one level zoomed out (plus surroundings)
        if (layer+1 < ChoreographerRenderThread.TILE_SHIFTS.length) {
            int p1x0 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[0] >> ChoreographerRenderThread.TILE_SHIFTS[layer + 1];
            int p1y0 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[1] >> ChoreographerRenderThread.TILE_SHIFTS[layer + 1];
            int p1x1 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[2] >> ChoreographerRenderThread.TILE_SHIFTS[layer + 1];
            int p1y1 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[3] >> ChoreographerRenderThread.TILE_SHIFTS[layer + 1];

            for (int ty = p1y0-1; ty <= p1y1+1; ++ty)
                for (int tx = p1x0-1; tx <= p1x1+1; ++tx)
                    tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer + 1, tx, ty);
        }

        // prio 3: regular zoom level, just outside screen
        for (int tx = tx0-1; tx <= tx1+1; ++tx) {
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx, ty0 - 1);
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx, ty1 + 1);
        }
        for (int ty = ty0; ty <= ty1; ++ty) {
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx0 - 1, ty);
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx1 + 1, ty);
        }

        // prio 4: one level zoomed in
        if (layer-1 >= 0) {
            int m1x0 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[0] >> ChoreographerRenderThread.TILE_SHIFTS[layer - 1];
            int m1y0 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[1] >> ChoreographerRenderThread.TILE_SHIFTS[layer - 1];
            int m1x1 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[2] >> ChoreographerRenderThread.TILE_SHIFTS[layer - 1];
            int m1y1 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[3] >> ChoreographerRenderThread.TILE_SHIFTS[layer - 1];

            for (int ty = m1y0; ty <= m1y1; ++ty)
                for (int tx = m1x0; tx <= m1x1; ++tx)
                    tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer - 1, tx, ty);
        }
    }

    class TileDiskLoader implements Runnable {
        BlockingQueue<Integer> tilesToLoad = new LinkedBlockingDeque<>();

        @Override public void run() {
            try {
                while (true)
                    get(tilesToLoad.take(), false);
            } catch (InterruptedException ex) {
                throw new RuntimeException("Unexpected interruption", ex);
            }
        }
    }
    
    /** Delete unused tiles and start loading new ones into cache (asynchronously). */
    private void refresh() {
        tileDiskLoader.tilesToLoad.clear();

        // delete unused tiles from cache, memory and GPU
        for (int k = 0; k < tilesToLoadCount; ++k)
            tilesToLoadSorted[k] = tilesToLoad[k];
        Arrays.sort(tilesToLoadSorted, 0, tilesToLoadCount);
        for (Map.Entry<Integer, Tile> entry : cache.entrySet()) {
            Tile tile = entry.getValue();
            if (tile.size < ChoreographerRenderThread.TILE_SHIFTS.length - 1 && // never delete most zoomed out layer
                    Arrays.binarySearch(tilesToLoadSorted, 0, tilesToLoadCount, entry.getKey()) < 0) { // not present among tiles to load
                tile.delete();
                cache.remove(entry.getKey());
            }
        }

        // start loading new tiles
        for (int k = 0; k < tilesToLoadCount; ++k) {
            final int tp = tilesToLoad[k];
            if (existingTiles.contains(tp))
                tileDiskLoader.tilesToLoad.add(tp);
        }
    }
}
