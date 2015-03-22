package com.max.vectormap;

public class Constants {
    public static final int BYTES_IN_FLOAT = Float.SIZE / Byte.SIZE;
    public static final int BYTES_IN_SHORT = Short.SIZE / Byte.SIZE;
    public static final int BYTES_IN_INT = Integer.SIZE / Byte.SIZE;

    public static final long ONE_SECOND_NANOS = 1000000000L;

    /** 1 = 1 second to blend between layers; 2 = 0.5 seconds, etc. */
    public static final float LAYER_BLEND_SPEED = 3f;

    public static final int NR_SURFACE_TYPES = 10;

    public static final int GLOBAL_OFS_X = 400000;
    public static final int GLOBAL_OFS_Y = 6200000;

    public static final float MIN_ZOOM = 128;
    public static final float MAX_ZOOM = 64 * 65536;

    public static final float[] LAYER_SHIFTS = {300, 900, 3000, 12000};

    public static final int[] TILE_SHIFTS = {12, 14, 16, 17, 18};
    public static final int[] TILE_SIZES = new int[TILE_SHIFTS.length];
    /** Number of times to shift current tile size left to equal next larger tile size. */
    public static final int[] TILE_SHIFT_DIFFS = new int[TILE_SHIFTS.length];
    public static final int NR_LAYERS = TILE_SHIFTS.length;
    public static final int TOP_LAYER = TILE_SHIFTS.length - 1;

    static {
        for (int k = 0; k < NR_LAYERS; ++k)
            TILE_SIZES[k] = 1 << TILE_SHIFTS[k];
        for (int k = 0; k < NR_LAYERS; ++k)
            TILE_SHIFT_DIFFS[k] = k == TOP_LAYER ? 0 : TILE_SHIFTS[k + 1] - TILE_SHIFTS[k];
    }

    public static int[] COLORS_DEBUG_INT = {
            0x3f5fff, // water
            0xf5e1d3, // urban
            0xf0f0f0, // industrial
            0xffff00, // farmland
            0xff0000, // open_land
            0xffffff, // mountain
            0x00ff00, // forest
            0x7f7f7f, // unmapped
            0xbfbfbf, // unclassified
            0x7f7f7f, // unspecified
    };

    public static int[] COLORS_INT = {
            0xb9dcff, // water
            0xf5e1d3, // urban
            0xf0f0f0, // industrial
            0xe2e8dc, // farmland
            0xeceee3, // open_land
            0xffffff, // mountain
            0xd2e5c9, // forest
            0x7f7f7f, // unmapped
            0xbfbfbf, // unclassified
            0x7f7f7f, // unspecified
    };

    public static int[] COLORS_NEW = {
            0xb9ccff, // water (0xb9dcff)
            0xfad999, // urban
            0xdcddc5, // industrial
            0xfff7a6, // farmland
            0xffffe0, // open_land
            0xffffff, // mountain
            0xc2e6a2, // forest
            0xb9ccff, // unmapped (0x7f7f7f)
            0xc2e6a2, // unclassified (0xbfbfbf)
            0x7f7f7f, // unspecified
    };

}
