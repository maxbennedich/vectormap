package com.max.vectormap;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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
                        int tilePos = Common.getTilePos(layer, tx, ty);
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
                    Log.d("TileCache", (logCacheMiss ? "CACHE MISS: " : "(no miss) ") + "Loaded tile " + Common.getTilePos(tile.size, tile.tx, tile.ty) +
                            " (" + tile.size + ", " + tile.tx + ", " + tile.ty + ")");
                }
            }
        }
        return tile;
    }

    Map<Integer, Float> timeFirstDrawn = new HashMap<>();
    Map<Integer, Float> timeLastDrawn = new HashMap<>();

    private static float getGlobalTimeInSeconds() {
        return System.nanoTime()/1e9f;
    }

    public boolean isLoaded(int tp) {
        return cache.containsKey(tp);
    }

    private BlendedTile tile(int tp) {
        Float firstDrawn = timeFirstDrawn.get(tp);
        if (firstDrawn == null)
            timeFirstDrawn.put(tp, firstDrawn = getGlobalTimeInSeconds()); // TODO should subtract frame time!
        float blend = Math.min(1, getGlobalTimeInSeconds() - firstDrawn);
        return new BlendedTile(tp, blend);
    }

    private int[] screenEdges;

    public static void getTileEdges(int[] screenEdges, int layer, int[] tileEdges) {
        tileEdges[0] = Constants.GLOBAL_OFS_X + screenEdges[0] >> Constants.TILE_SHIFTS[layer];
        tileEdges[1] = Constants.GLOBAL_OFS_Y + screenEdges[1] >> Constants.TILE_SHIFTS[layer];
        tileEdges[2] = Constants.GLOBAL_OFS_X + screenEdges[2] >> Constants.TILE_SHIFTS[layer];
        tileEdges[3] = Constants.GLOBAL_OFS_Y + screenEdges[3] >> Constants.TILE_SHIFTS[layer];
    }

    /**
     * NOT THREAD SAFE
     * @param screenEdges UTM int coordinates
     */
    public List<BlendedTile> getDrawOrder(int[] screenEdges, float scaleFactor) {
        this.screenEdges = screenEdges;
        int minLayerDrawn = Common.getLayerForScaleFactor(scaleFactor);

        List<BlendedTile> drawOrder = new ArrayList<>();
        int[] txy = new int[4];
        getTileEdges(screenEdges, Constants.TOP_LAYER, txy);
        for (int ty = txy[1]; ty <= txy[3]; ++ty)
            for (int tx = txy[0]; tx <= txy[2]; ++tx)
                drawOrder.addAll(addTileRecursive(Common.getTilePos(Constants.TOP_LAYER, tx, ty), minLayerDrawn));

        // move tiles that are no longer drawn from "first drawn" to "last drawn" map TODO optimize
        Set<Integer> allDrawn = new HashSet<>();
        for (BlendedTile tile : drawOrder)
            allDrawn.add(tile.tilePos);
        for (Iterator<Integer> it = timeFirstDrawn.keySet().iterator(); it.hasNext(); ) {
            int tp = it.next();
            if (!allDrawn.contains(tp)) {
                it.remove();
                timeLastDrawn.remove(tp);
//                timeLastDrawn.put(tp, getGlobalTimeInSeconds());
            }
        }

        Log.d("DrawOrder", "layer="+minLayerDrawn+": "+drawOrder.toString());

        return drawOrder;
    }

    private List<BlendedTile> addTileRecursive(int tp, int minLayerDrawn) {
        List<BlendedTile> drawOrder = new ArrayList<>();

        for (int child : getChildren(tp))
            if (positionHasContent(child))
                drawOrder.addAll(addTileRecursive(child, minLayerDrawn));

        // draw tile if either within the given scale, or it's more zoomed in but still blending out (recently removed)
        int layer = Common.getLayer(tp);
        if (isLoaded(tp) && (layer >= minLayerDrawn || blendingOut(tp)))
            if (!allChildrenCovered(tp, minLayerDrawn) || blendingIn(tp, drawOrder)) {
                // if tile is blended and has children, blend children instead (since children are drawn after this tile)
                Log.d("DrawOrder", "tp="+Common.getTilePosStr(tp)+", blendingout="+blendingOut(tp)+", childrencovered="+allChildrenCovered(tp, minLayerDrawn)+", blendingin="+blendingIn(tp, drawOrder));
                BlendedTile bt = tile(tp);
                if (bt.blend < 1 && !drawOrder.isEmpty()) {
                    float childBlend = 1 - bt.blend;
                    bt.blend = 1;
                    for (BlendedTile child : drawOrder)
                        child.blend = childBlend;
                }

                drawOrder.add(0, bt);
                if (layer >= minLayerDrawn)
                    timeLastDrawn.put(tp, getGlobalTimeInSeconds());
            }

        return drawOrder;
    }

    /** Whether tile has been removed and is still blending out. */
    private boolean blendingOut(int tp) {
        Float t = timeLastDrawn.get(tp);
        if (t == null) return false;
        return getGlobalTimeInSeconds() - t < 1;
    }

    /**
     * If the given tile is older than any drawn child, we need to draw it even if all children are loaded since
     * otherwise we'd have "popping" when a child replaces a parent that has not finished blending.
     */
    private boolean blendingIn(int tp, List<BlendedTile> drawnChildren) {
        float minChildBlend = 1;
        for (BlendedTile child : drawnChildren)
            minChildBlend = Math.min(minChildBlend, child.blend);
        Float firstDrawn = timeFirstDrawn.get(tp);
        if (firstDrawn == null)
            return false;
        float parentBlend = Math.min(1, getGlobalTimeInSeconds() - firstDrawn);
        return parentBlend > minChildBlend;
    }

    /** @return Whether any part of the tile is on screen. */
    private boolean onScreen(int tp) {
        int tx = Common.getTX(tp), ty = Common.getTY(tp);
        int[] txy = new int[4];
        getTileEdges(screenEdges, Common.getLayer(tp), txy);
        return tx >= txy[0] && tx <= txy[2] && ty >= txy[1] && ty <= txy[3];
    }

    /**
     * @return Whether the square defined by the tile at the given position is entirely covered,
     * either by a single tile or multiple tiles (or is off screen).
     */
    private boolean positionCovered(int tp, int minLayerDrawn) {
        return !onScreen(tp) || isLoaded(tp) || allChildrenCovered(tp, minLayerDrawn);
    }

    private boolean allChildrenCovered(int tp, int minLayerDrawn) {
        if (Common.getLayer(tp) <= minLayerDrawn)
            return false;
        for (int child : getChildren(tp))
            if (!positionCovered(child, minLayerDrawn))
                return false;
        return true;
    }

    /**
     * @return Whether the square defined by the tile at the given position has any content at all,
     * for example entirely filled by a single tile or filled by one or more sub-tiles.
     */
    private boolean positionHasContent(int tp) {
        return isLoaded(tp) || anyChildHasContent(tp);
    }

    private boolean anyChildHasContent(int tp) {
        for (int child : getChildren(tp))
            if (positionHasContent(child))
                return true;
        return false;
    }

    /** All children of the given position, including those off screen. */
    int[] getChildren(int tp) {
        int layer = Common.getLayer(tp);
        if (layer == 0) return new int[0];
        int shiftDiff = Constants.TILE_SHIFT_DIFFS[layer-1];
        int x = Common.getTX(tp), y = Common.getTY(tp);
        int children[] = new int[1 << shiftDiff*2];
        for (int yi = y << shiftDiff, ci = 0; yi < y+1 << shiftDiff; ++yi)
            for (int xi = x << shiftDiff; xi < x+1 << shiftDiff; ++xi, ++ci)
                children[ci] = Common.getTilePos(layer-1, xi, yi);
        return children;
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

        layer = Common.getLayerForScaleFactor(scaleFactor);

        int lm1 = Math.max(layer - 1, 0);
        int m1x0 = Constants.GLOBAL_OFS_X + screenEdges[0] >> Constants.TILE_SHIFTS[lm1];
        int m1y0 = Constants.GLOBAL_OFS_Y + screenEdges[1] >> Constants.TILE_SHIFTS[lm1];
        int m1x1 = Constants.GLOBAL_OFS_X + screenEdges[2] >> Constants.TILE_SHIFTS[lm1];
        int m1y1 = Constants.GLOBAL_OFS_Y + screenEdges[3] >> Constants.TILE_SHIFTS[lm1];

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
                tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer, tx, ty);

        // prio 2: regular zoom level, just outside screen
        int tc = tilesToLoadCount;
        for (int tx = tx0-1; tx <= tx1+1; ++tx) {
            tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer, tx, ty0 - 1);
            tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer, tx, ty1 + 1);
        }
        for (int ty = ty0; ty <= ty1; ++ty) {
            tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer, tx0 - 1, ty);
            tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer, tx1 + 1, ty);
        }

        // prio 3: one level zoomed out (plus surroundings) TODO prio 2, and show if zoomed in not loaded?
        if (layer+1 < Constants.NR_LAYERS) {
            for (int ty = (ty0>>Constants.TILE_SHIFT_DIFFS[layer])-1; ty <= (ty1>>Constants.TILE_SHIFT_DIFFS[layer])+1; ++ty)
                for (int tx = (tx0>>Constants.TILE_SHIFT_DIFFS[layer])-1; tx <= (tx1>>Constants.TILE_SHIFT_DIFFS[layer])+1; ++tx)
                    tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer + 1, tx, ty);
        }

        // prio 4: one level zoomed in
        if (layer-1 >= 0) {
            for (int ty = m1y0; ty <= m1y1; ++ty)
                for (int tx = m1x0; tx <= m1x1; ++tx)
                    tilesToLoad[tilesToLoadCount++] = Common.getTilePos(layer - 1, tx, ty);
        }

        Log.d("TileCache", "(miss) " + String.format("layer %d: %d,%d-%d,%d, layer %d: %d,%d-%d,%d", layer, tx0, ty0, tx1, ty1, lm1, m1x0, m1y0, m1x1, m1y1));
        StringBuilder sb = new StringBuilder();
        for (int k : tilesToLoad) sb.append(k+", ");
        Log.d("TileCache", "(miss) tiles to load for layer " + layer + ": "+sb);

        refresh(layer);
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
    private void refresh(int layer) {
        tileDiskLoader.tilesToLoad.clear();

        // delete unused tiles from cache, memory and GPU
        for (int k = 0; k < tilesToLoadCount; ++k)
            tilesToLoadSorted[k] = tilesToLoad[k];
        Arrays.sort(tilesToLoadSorted, 0, tilesToLoadCount);
        for (Map.Entry<Integer, Tile> entry : cache.entrySet()) {
            Tile tile = entry.getValue();
            if (tile.size != Constants.TOP_LAYER && // never delete most zoomed out layer
                    Arrays.binarySearch(tilesToLoadSorted, 0, tilesToLoadCount, entry.getKey()) < 0) { // not present among tiles to load
                Log.d("TileCache", "Deleting (miss) tile " + entry.getKey() + " (" + Common.getTilePosStr(entry.getKey()) + ")");
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
