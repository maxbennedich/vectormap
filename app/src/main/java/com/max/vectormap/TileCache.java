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

    public boolean isLoaded(int tp) {
        return cache.containsKey(tp);
    }

    static class TileNode {
        int layer;
        int tx, ty;
        float blend;
        /** Blend as actually drawn. Differs from blend since parents whose children are all drawn get a draw blend of 0 although their actual blend might still be 1. */
        float drawnBlend;
        LinkedHashSet<TileNode> children;

        public TileNode(int layer, int tx, int ty, float blend) {
            this.layer = layer;
            this.tx = tx;
            this.ty = ty;
            this.blend = blend;
            this.children = new LinkedHashSet<>();
        }

        public int tp() {
            return Common.getTilePos(layer, tx, ty);
        }

        @Override public String toString() {
            return String.format("%d,%d,%d:%.2f", layer, tx, ty, blend);
        }

        @Override public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + layer;
            result = prime * result + tx;
            result = prime * result + ty;
            return result;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            TileNode other = (TileNode) obj;
            if (layer != other.layer)
                return false;
            if (tx != other.tx)
                return false;
            if (ty != other.ty)
                return false;
            return true;
        }
    }

    public static void getTileEdges(int[] screenEdges, int layer, int[] tileEdges) {
        tileEdges[0] = Constants.GLOBAL_OFS_X + screenEdges[0] >> Constants.TILE_SHIFTS[layer];
        tileEdges[1] = Constants.GLOBAL_OFS_Y + screenEdges[1] >> Constants.TILE_SHIFTS[layer];
        tileEdges[2] = Constants.GLOBAL_OFS_X + screenEdges[2] >> Constants.TILE_SHIFTS[layer];
        tileEdges[3] = Constants.GLOBAL_OFS_Y + screenEdges[3] >> Constants.TILE_SHIFTS[layer];
    }

    private int[] screenEdges;
    private int[] prevScreenEdges = null;

    private static final TileNode getRootNode() { return new TileNode(-1, -1, -1, 0); }

    TileNode prevRootNode = getRootNode();

    public List<BlendedTile> getDrawOrder(int[] screenEdges, float scaleFactor, float elapsedTime) {
        this.screenEdges = screenEdges;

        int desiredLayer = Common.getLayerForScaleFactor(scaleFactor);

        // compute new draw order given screen edges and scale factor
        TileNode rootNode = getRootNode();
        int[] txy = new int[4];
        getTileEdges(screenEdges, Constants.TOP_LAYER, txy);
        for (int ty = txy[1]; ty <= txy[3]; ++ty)
            for (int tx = txy[0]; tx <= txy[2]; ++tx)
                rootNode.children.add(visit(Common.getTilePos(Constants.TOP_LAYER, tx, ty), desiredLayer));

        Map<Integer, TileNode> tileNodeByPos = new HashMap<>();
        addNodeToMap(rootNode, tileNodeByPos);

        // update draw order with any on-screen tiles from the last round; also updates all blend values from the previously drawn tree
        updateTreeWithOnScreenTiles(prevRootNode, null, tileNodeByPos);

        // update blend values; blend in and out depending on if tile layer is within the desired range of layers
        updateBlending(rootNode, null, desiredLayer, elapsedTime);

        for (TileNode topNode : rootNode.children)
            hideParentsOverdrawnByChildren(topNode);

        List<BlendedTile> drawOrder = getDrawOrderFromTree(rootNode);

        prevRootNode = rootNode;
        if (prevScreenEdges == null)
            prevScreenEdges = new int[screenEdges.length];
        for (int k = 0; k < screenEdges.length; ++k)
            prevScreenEdges[k] = screenEdges[k];

        return drawOrder;
    }

    /** For any parent whose on-screen children all have a blend of 1, set its blend to 0 (since they are completely overdrawn).*/
    private void hideParentsOverdrawnByChildren(TileNode node) {
        node.drawnBlend = node.blend;
        if (node.blend > 0 && fullyOverdrawn(node))
            node.drawnBlend = 0;

        for (TileNode child : node.children)
            hideParentsOverdrawnByChildren(child);
    }

    /** @return True if the given tile is completely overdrawn (with blend 1) by children (recursively). */
    private boolean fullyOverdrawn(TileNode node) {
        if (node.children.isEmpty())
            return false;

        int childLayer = node.layer - 1;
        int[] txy = new int[4];
        getTileEdges(screenEdges, childLayer, txy);
        int shift = Constants.TILE_SHIFT_DIFFS[childLayer];
        boolean allChildrenFullyDrawn = true;
        outer:
        for (int y = Math.max(node.ty << shift, txy[1]); y <= Math.min((node.ty + 1 << shift) - 1, txy[3]); ++y) {
            for (int x = Math.max(node.tx << shift, txy[0]); x <= Math.min((node.tx + 1 << shift) - 1, txy[2]); ++x) {
                TileNode child = findChild(node, x, y);
                if (child == null || (child.blend < 1 && !fullyOverdrawn(child))) {
                    allChildrenFullyDrawn = false;
                    break outer;
                }
            }
        }

        return allChildrenFullyDrawn;
    }

    private TileNode findChild(TileNode parent, int x, int y) {
        for (TileNode child : parent.children)
            if (child.tx == x && child.ty == y)
                return child;
        return null;
    }

    /** @return True if node blended completely out, which indicates it can be removed. */
    private boolean updateBlending(TileNode node, TileNode parent, int desiredLayer, float elapsedTime) {
        boolean blendedOut = false;
        if (parent != null) { // exclude root node
            if (!isLoaded(Common.getTilePos(node.layer, node.tx, node.ty))) {
                node.blend = 0; // tiles not loaded always get blend 0
            } else if (node.layer >= desiredLayer) {
                node.blend = Math.min(1, node.blend + elapsedTime*2);
            } else {
                node.blend = Math.max(0, node.blend - elapsedTime*2);
                blendedOut = node.blend == 0;
            }
        }
        for (Iterator<TileNode> it = node.children.iterator(); it.hasNext(); ) {
            TileNode child = it.next();
            if (updateBlending(child, node, desiredLayer, elapsedTime))
                it.remove();
        }
        return blendedOut;
    }

    private void addNodeToMap(TileNode node, Map<Integer, TileNode> tileNodeByPos) {
        tileNodeByPos.put(node.tp(), node);
        for (TileNode child : node.children)
            addNodeToMap(child, tileNodeByPos);
    }

    private void updateTreeWithOnScreenTiles(TileNode node, TileNode parent, Map<Integer, TileNode> newNodeByPos) {
        if (node.layer >= 0) {
            TileNode newParent = newNodeByPos.get(parent.tp()); // this is guaranteed to exist since we would have added it in a previous call otherwise
            TileNode newChild = new TileNode(node.layer, node.tx, node.ty, node.blend);
            if (!newParent.children.contains(newChild)) {
                newParent.children.add(newChild);
                newNodeByPos.put(newChild.tp(), newChild);
            } else {
                for (TileNode child : newParent.children) {
                    if (child.equals(newChild)) {
                        child.blend = node.blend; // carry over blend from previously drawn tree TODO optimize
                        break;
                    }
                }
            }
        }
        for (TileNode child : node.children)
            if (onScreen(Common.getTilePos(child.layer, child.tx, child.ty), screenEdges))
                updateTreeWithOnScreenTiles(child, node, newNodeByPos);
    }

    /** @return Whether any part of the tile is on screen. */
    private static boolean onScreen(int tp, int[] screenEdges) {
        int tx = Common.getTX(tp), ty = Common.getTY(tp);
        int[] txy = new int[4];
        getTileEdges(screenEdges, Common.getLayer(tp), txy);
        return tx >= txy[0] && tx <= txy[2] && ty >= txy[1] && ty <= txy[3];
    }

    private TileNode visit(int tp, int desiredLayer) {
        int layer = Common.getLayer(tp), tx = Common.getTX(tp), ty = Common.getTY(tp);
        // if tile was just added, start blended out if it would have been visible last frame (to prevent "popping"),
        // and start blended in if tile was panned into view
        // (if tile not was not just added, the blend value will be overwritten with the value from the previously drawn tree)
        float initialBlend = prevScreenEdges != null && onScreen(tp, prevScreenEdges) ? 0 : 1;
        TileNode node = new TileNode(layer, tx, ty, initialBlend);
        if (layer > desiredLayer) {
            LinkedHashSet<TileNode> children = new LinkedHashSet<>();
            int[] txy = new int[4];
            getTileEdges(screenEdges, layer - 1, txy);
            int shift = Constants.TILE_SHIFT_DIFFS[layer-1];
            for (int y = Math.max(ty << shift, txy[1]); y <= Math.min((ty + 1 << shift) - 1, txy[3]); ++y)
                for (int x = Math.max(tx << shift, txy[0]); x <= Math.min((tx + 1 << shift) - 1, txy[2]); ++x)
                    children.add(visit(Common.getTilePos(layer-1, x, y), desiredLayer));
            node.children = children;
        }
        return node;
    }

    private List<BlendedTile> getDrawOrderFromTree(TileNode rootNode) {
        List<BlendedTile> drawOrder = new ArrayList<>();
        for (TileNode topNode : rootNode.children)
            addTreeRecursively(drawOrder, topNode);
        return drawOrder;
    }

    private void addTreeRecursively(List<BlendedTile> drawOrder, TileNode node) {
        if (node.drawnBlend > 0)
            drawOrder.add(new BlendedTile(node.tp(), node.drawnBlend));
        for (TileNode child : node.children)
            addTreeRecursively(drawOrder, child);
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
