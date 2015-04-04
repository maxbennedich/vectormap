package com.max.vectormap;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class TextLayer {
    public List<TextString> strings = new ArrayList<>();

    public TextLayer(Context context) {
        String textData = Common.readInputStream(context.getResources().openRawResource(R.raw.text_layer));
        String[] rows = textData.split("\\n");
        for (String row : rows) {
            String[] cols = row.split("\\|");
            strings.add(new TextString(cols[0], Integer.valueOf(cols[1]), Integer.valueOf(cols[2]),
                    Integer.valueOf(cols[3]), Integer.valueOf(cols[4]),
                    Integer.valueOf(cols[5]), Integer.valueOf(cols[6])));
        }
    }
}
