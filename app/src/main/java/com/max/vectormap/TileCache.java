package com.max.vectormap;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
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

    static class TileNode {
        final int layer;
        final int tx, ty;
        final int tp;
        float blend;
        /** Blend as actually drawn. Differs from blend since parents whose children are all drawn get a draw blend of 0 although their actual blend might still be 1. */
        float drawnBlend;
        boolean fullyOverdrawn;
        TileNode parent;
        TileNode[] children;

        private static TileNode[] NO_CHILDREN = new TileNode[0];

        public TileNode(int layer, int tx, int ty, float blend, TileNode parent) {
            this.layer = layer;
            this.tx = tx;
            this.ty = ty;
            this.tp = Common.getTilePos(layer, tx, ty);
            this.blend = blend;
            this.parent = parent;
            this.children = layer <= 0 ? NO_CHILDREN : new TileNode[1 << 2*Constants.TILE_SHIFT_DIFFS[layer-1]];
        }

        @Override public String toString() {
            return String.format("%d,%d,%d:%.2f", layer, tx, ty, blend);
        }
    }

    public static void getTileEdges(int[] screenEdges, int layer, int[] tileEdges) {
        tileEdges[0] = Constants.GLOBAL_OFS_X + screenEdges[0] >> Constants.TILE_SHIFTS[layer];
        tileEdges[1] = Constants.GLOBAL_OFS_Y + screenEdges[1] >> Constants.TILE_SHIFTS[layer];
        tileEdges[2] = Constants.GLOBAL_OFS_X + screenEdges[2] >> Constants.TILE_SHIFTS[layer];
        tileEdges[3] = Constants.GLOBAL_OFS_Y + screenEdges[3] >> Constants.TILE_SHIFTS[layer];
    }

    public boolean isLoaded(int tp) {
        return cache.containsKey(tp);
    }

    /** Top level extreme points (x0, y0, x1, y1). TODO don't hard code. */
    int[] rootEdges = {0, 23, 3, 29};

    int[][] prevTileEdges = new int[Constants.NR_LAYERS][4];
    { prevTileEdges[0][0] = Integer.MAX_VALUE; } // indicates no previous state (e.g. first time rendering)
    int[][] tileEdges = new int[Constants.NR_LAYERS][4];

    private TileNode getRootNode() {
        TileNode node = new TileNode(-1, -1, -1, 0, null);
        node.children = new TileNode[(rootEdges[2] - rootEdges[0] + 1) * (rootEdges[3] - rootEdges[1] + 1)];
        return node;
    }

    TileNode rootNode = getRootNode();

    int MAX_DRAWN_TILES = 256; // 80 were enough during a test run; use 256 for extra margin
    public int[] drawnTilePosArray = new int[MAX_DRAWN_TILES];
    public float[] drawnBlendArray = new float[MAX_DRAWN_TILES];
    public int nrDrawnTiles = 0;

    public void getDrawOrder(int[] screenEdges, float scaleFactor, float elapsedTime) {
        for (int layer = Constants.TOP_LAYER; layer >= 0; --layer)
            getTileEdges(screenEdges, layer, tileEdges[layer]);

        int desiredLayer = Common.getLayerForScaleFactor(scaleFactor);

        refreshTree(desiredLayer, elapsedTime);

        getDrawOrderFromTree(rootNode);

        // TODO don't this
        drawnTilePos.clear();
        for (int k = 0; k < nrDrawnTiles; ++k)
            drawnTilePos.add(drawnTilePosArray[k]);

        // TODO don't copy; oscillate between two copies!
        for (int layer = 0; layer <= Constants.TOP_LAYER; ++layer)
            for (int k = 0; k < 4; ++k)
                prevTileEdges[layer][k] = tileEdges[layer][k];
    }

    /** compute new draw order given screen edges and scale factor, keeping all tiles that were on screen the previous round,
     * and removing those that became off screen */
    private void refreshTree(int desiredLayer, float elapsedTime) {
        for (int ty = rootEdges[1]; ty <= rootEdges[3]; ++ty) {
            for (int tx = rootEdges[0]; tx <= rootEdges[2]; ++tx) {
                int idx = (ty - rootEdges[1]) * (rootEdges[2] - rootEdges[0] + 1) + tx - rootEdges[0];
                refreshNode(rootNode, idx, Constants.TOP_LAYER, tx, ty, desiredLayer, elapsedTime);
            }
        }
    }

    /** Refresh the given tile and all its children recursively, keeping all tiles that were on screen the previous round,
     * and removing those that became off screen. */
    private void refreshNode(TileNode parent, int idx, int layer, int tx, int ty, int desiredLayer, float elapsedTime) {
        // if tile is outside screen, remove it and stop recursion
        if (tx < tileEdges[layer][0] || tx > tileEdges[layer][2] || ty < tileEdges[layer][1] || ty > tileEdges[layer][3]) {
            parent.children[idx] = null;
            return;
        }
        // if tile did not previously exist and should not be visible, stop recursion
        TileNode node = parent.children[idx];
        if (node == null && layer < desiredLayer)
            return;

        if (node == null) {
            // tile does not currently exist; start blended out if it would have been visible (although not drawn) last frame
            // (to prevent "popping"), and start blended in if tile was panned into view
            float initialBlend = previouslyOnScreen(layer, tx, ty) ? 0 : 1;
            parent.children[idx] = node = new TileNode(layer, tx, ty, initialBlend, parent);
        } else {
            // Tile already exists; keep blend from previously drawn tree. If the tile was fully overdrawn,
            // start blend at 1 and have children blend out, rather than blending the parent in from 0.
            if (node.fullyOverdrawn)
                node.blend = 1;
        }

        updateBlending(parent, idx, desiredLayer, elapsedTime);

        // recursive call
        if (node.fullyOverdrawn = layer > 0) {
            int shift = Constants.TILE_SHIFT_DIFFS[layer-1];
            for (int y = ty << shift; y < ty + 1 << shift; ++y) {
                for (int x = tx << shift; x < tx + 1 << shift; ++x) {
                    int childIdx = ((y - (ty << shift)) << shift) + x - (tx << shift);
                    refreshNode(node, childIdx, layer-1, x, y, desiredLayer, elapsedTime);

                    // if any on-screen child is not fully drawn, set this node to not fully drawn
                    if (node.fullyOverdrawn && x >= tileEdges[layer-1][0] && x <= tileEdges[layer-1][2] && y >= tileEdges[layer-1][1] && y <= tileEdges[layer-1][3]) {
                        TileNode child = node.children[childIdx];
                        node.fullyOverdrawn = child != null && (child.blend == 1 || child.fullyOverdrawn);
                    }
                }
            }
        }

        // Set drawnBlend. For any tile whose on-screen children all have a blend of 1, drawBlend is 0 since the tile is completely overdrawn.
        if ((node.drawnBlend = node.blend) > 0 && node.fullyOverdrawn)
            node.drawnBlend = 0;
    }

    private boolean previouslyOnScreen(int layer, int tx, int ty) {
        if (prevTileEdges[0][0] == Integer.MAX_VALUE) // special value indicating there was no previous state recorded
            return false;
        return tx >= prevTileEdges[layer][0] && tx <= prevTileEdges[layer][2] && ty >= prevTileEdges[layer][1] && ty <= prevTileEdges[layer][3];
    }

    /** Update blend values; blend in and out depending on if tile layer is within the desired range of layers.
     * Remove tile if it blended completely out. */
    private void updateBlending(TileNode parent, int idx, int desiredLayer, float elapsedTime) {
        TileNode node = parent.children[idx];
        if (node.parent == null) // exclude root node
            return;

        if (!isLoaded(node.tp)) {
            node.blend = 0; // tiles not loaded always get blend 0
        } else if (node.layer >= desiredLayer) {
            node.blend = Math.min(1, node.blend + elapsedTime * Constants.LAYER_BLEND_SPEED);
        } else {
            // don't start blending out until any parent up until the desired layer is loaded
            for (TileNode parentAtDesiredLayer = node.parent; parentAtDesiredLayer.layer <= desiredLayer && parentAtDesiredLayer.layer != -1; parentAtDesiredLayer = parentAtDesiredLayer.parent) {
                if (isLoaded(parentAtDesiredLayer.tp)) {
                    if ((node.blend -= elapsedTime * Constants.LAYER_BLEND_SPEED) <= 0)
                        parent.children[idx] = null; // remove if completely blended out
                    break;
                }
            }
        }
    }

    private void getDrawOrderFromTree(TileNode node) {
        if (node == rootNode) {
            nrDrawnTiles = 0;
        } else if (node.drawnBlend > 0) {
            drawnTilePosArray[nrDrawnTiles] = node.tp;
            drawnBlendArray[nrDrawnTiles] = node.drawnBlend;
            ++nrDrawnTiles;
        }
        for (TileNode child : node.children)
            if (child != null)
                getDrawOrderFromTree(child);
    }

    Set<Integer> drawnTilePos = new HashSet<>();

    private boolean drawn(int tp) {
        return drawnTilePos.contains(tp);
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

//        Log.d("TileCache", "(miss) " + String.format("layer %d: %d,%d-%d,%d, layer %d: %d,%d-%d,%d", layer, tx0, ty0, tx1, ty1, lm1, m1x0, m1y0, m1x1, m1y1));
//        StringBuilder sb = new StringBuilder();
//        for (int k : tilesToLoad) sb.append(k+", ");
//        Log.d("TileCache", "(miss) tiles to load for layer " + layer + ": "+sb);

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
                    Arrays.binarySearch(tilesToLoadSorted, 0, tilesToLoadCount, entry.getKey()) < 0 && // not present among tiles to load
                    !drawn(entry.getKey())) { // don't remove tiles currently being drawn
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
