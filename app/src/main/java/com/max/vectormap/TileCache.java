package com.max.vectormap;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
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

        Log.d("TileCache", "Root = "+TileLoader.getTriRoot());
        for (File level0 : TileLoader.getTriRoot().listFiles()) {
            for (File level1 : level0.listFiles()) {
                for (String tileFile : level1.list()) {
                    Matcher m = p.matcher(tileFile);
                    if (m.find()) {
                        int size = Integer.valueOf(m.group(1));
                        int layer = -1;
                        for (int k = 0; k < Constants.TILE_SIZES.length; ++k) {
                            if (size == Constants.TILE_SIZES[k]) {
                                layer = k;
                                break;
                            }
                        }
                        int tx = Integer.valueOf(m.group(2));
                        int ty = Integer.valueOf(m.group(3));
                        int tilePos = ChoreographerRenderThread.getTilePos(layer, tx, ty);
                        existingTiles.add(tilePos);
                    }
                }
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
                    Log.d("TileCache", (logCacheMiss ? "CACHE MISS: " : "(no miss) ") + "Loaded tile " + ChoreographerRenderThread.getTilePos(tile.size, tile.tx, tile.ty));
                }
            }
        }
        return tile;
    }

    private int[] tilesToLoadSorted = new int[512];
    private int[] tilesToLoad = new int[512];
    int tilesToLoadCount = 0;

    private int layerOld = -1;
    private int m1x0Old, m1y0Old, m1x1Old, m1y1Old;

    /**
     * Based on camera position and potentially other factors, figure out which tiles are either
     * needed right away or could be needed within short (e.g. if user pans or zooms).
     * TODO this creates too many tiles when zoomed out too much
     * TODO BUG: when switching layers, need to load tiles for both layers, also when zooming
     * TODO: out too quickly, it ends up wanting to load way too many tiles
     */
    public void refreshForPosition(int[] screenEdges, float scaleFactor, int layer) {
        // first figure out if potential set of tiles to load changed from previous frame
        boolean setChanged = true;

 //       int layer = Common.getLayerForScaleFactor(scaleFactor);

        int lm1 = Math.max(layer - 1, 0);
        int m1x0 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[0] >> Constants.TILE_SHIFTS[lm1];
        int m1y0 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[1] >> Constants.TILE_SHIFTS[lm1];
        int m1x1 = ChoreographerRenderThread.GLOBAL_OFS_X + screenEdges[2] >> Constants.TILE_SHIFTS[lm1];
        int m1y1 = ChoreographerRenderThread.GLOBAL_OFS_Y + screenEdges[3] >> Constants.TILE_SHIFTS[lm1];

        if (layer == layerOld && m1x0 == m1x0Old && m1y0 == m1y0Old && m1x1 == m1x1Old && m1y1 == m1y1Old) {
            setChanged = false;
        } else {
            layerOld = layer;
            m1x0Old = m1x0; m1y0Old = m1y0; m1x1Old = m1x1; m1y1Old = m1y1;
        }

        if (!setChanged)
            return;

        Log.d(ChoreographerActivity.TAG, "Tile set changed: "+m1x0+","+m1y0+","+m1x1+","+m1y1);

        tilesToLoadCount = 0;

        // prio 1: tiles on screen
        int tx0, ty0, tx1, ty1;
        if (layer == 0) { tx0 = m1x0; ty0 = m1y0; tx1 = m1x1; ty1 = m1y1; }
        else { tx0 = m1x0>>Constants.TILE_SHIFT_DIFFS[lm1]; ty0 = m1y0>>Constants.TILE_SHIFT_DIFFS[lm1]; tx1 = m1x1>>Constants.TILE_SHIFT_DIFFS[lm1]; ty1 = m1y1>>Constants.TILE_SHIFT_DIFFS[lm1]; }

        for (int ty = ty0; ty <= ty1; ++ty)
            for (int tx = tx0; tx <= tx1; ++tx)
                tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx, ty);

        // prio 2: regular zoom level, just outside screen
        int tc = tilesToLoadCount;
        for (int tx = tx0-1; tx <= tx1+1; ++tx) {
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx, ty0 - 1);
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx, ty1 + 1);
        }
        for (int ty = ty0; ty <= ty1; ++ty) {
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx0 - 1, ty);
            tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer, tx1 + 1, ty);
        }

        // prio 3: one level zoomed out (plus surroundings) TODO prio 2, and show if zoomed in not loaded?
        if (layer+1 < Constants.TILE_SHIFTS.length) {
            for (int ty = (ty0>>Constants.TILE_SHIFT_DIFFS[layer])-1; ty <= (ty1>>Constants.TILE_SHIFT_DIFFS[layer])+1; ++ty)
                for (int tx = (tx0>>Constants.TILE_SHIFT_DIFFS[layer])-1; tx <= (tx1>>Constants.TILE_SHIFT_DIFFS[layer])+1; ++tx)
                    tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer + 1, tx, ty);
        }

        // prio 4: one level zoomed in
        if (layer-1 >= 0) {
            for (int ty = m1y0; ty <= m1y1; ++ty)
                for (int tx = m1x0; tx <= m1x1; ++tx)
                    tilesToLoad[tilesToLoadCount++] = ChoreographerRenderThread.getTilePos(layer - 1, tx, ty);
        }

        refresh();
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
            if (tile.size < Constants.TILE_SHIFTS.length - 1 && // never delete most zoomed out layer
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
