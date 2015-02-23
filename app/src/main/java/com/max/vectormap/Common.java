package com.max.vectormap;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class Common {
    public static String readInputStream(InputStream is) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder text = new StringBuilder();
            String nextLine;
            while ((nextLine = br.readLine()) != null) {
                text.append(nextLine);
                text.append('\n');
            }
            return text.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Error reading resource", e);
        }
    }
}
