package com.max.vectormap;

import java.io.DataInputStream;
import java.io.IOException;

public class BitReader {
    private final DataInputStream dis;

    int scratch;
    int bitsLeft;

    BitReader(DataInputStream dis) {
        this.dis = dis;
        scratch = bitsLeft = 0;
    }

    int read(int bits) throws IOException {
        if (bits > bitsLeft) {
            int v = (scratch & ((1 << bitsLeft) - 1)) << (bits - bitsLeft);
            scratch = dis.readInt();
            bitsLeft += 32 - bits;
            return v + (scratch >>> bitsLeft);

        } else  {
            return (scratch >>> (bitsLeft -= bits)) & ((1 << bits) - 1);
        }
    }

}
