package com.max.vectormap;

public class Constants {
    public static final int BYTES_IN_FLOAT = Float.SIZE/Byte.SIZE;
    public static final int BYTES_IN_SHORT = Short.SIZE/Byte.SIZE;
    public static final int BYTES_IN_INT = Integer.SIZE/Byte.SIZE;

    public static final int NR_SURFACE_TYPES = 10;

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
            0xb9dcff, // water
            0xfad999, // urban
            0xdcddc5, // industrial
            0xfff7a6, // farmland
            0xffffe0, // open_land
            0xffffff, // mountain
            0xc2e6a2, // forest
            0x7f7f7f, // unmapped
            0xbfbfbf, // unclassified
            0x7f7f7f, // unspecified
    };

}
