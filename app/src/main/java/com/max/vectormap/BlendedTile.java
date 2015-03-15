package com.max.vectormap;

public class BlendedTile {
    public int tilePos;
    public float blend;

    public BlendedTile(int tilePos, float blend) {
        this.tilePos = tilePos;
        this.blend = blend;
    }

    @Override public String toString() {
        return String.format("%s:%.2f", Common.getTilePosStr(tilePos).replaceAll("\\s", ""), blend);
    }
}
