package com.max.vectormap;

public class TextString {
    int x, y;
    String text;
    int category;
    int offset;
    int spacing;
    int angle;

    public TextString(String text, int x, int y, int category, int offset, int spacing, int angle) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.category = category;
        this.offset = offset;
        this.spacing = spacing;
        this.angle = angle;
    }
}
