package com.max.vectormap;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Environment;
import android.util.Log;

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

    /* @return If external storage is available to read (does not check for write permission) */
    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    /**
     * Logs available system memory, which according to the below thread decides how much you can
     * load into the GPU through OpenGL.
     * http://stackoverflow.com/questions/16147224/proper-memory-management-in-opengl-on-android-devices
     */
    public static void logAvailableMemory(Activity activity) {
        ActivityManager activityManager = (ActivityManager) activity.getSystemService(Activity.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mInfo = new ActivityManager.MemoryInfo ();
        activityManager.getMemoryInfo(mInfo);
        Log.v("Memory", "MB memory available: " + mInfo.availMem / 1024 / 1024);
    }

    /** 0 -> 1, 1 -> 1, 2 -> 2, 3 -> 2, 4 -> 3, 5 -> 3, etc. Note: returns 1 for k=0 since 1 bit is needed to encode 0. */
    public static final int log2(int k) {
        return k == 0 ? 1 : (32 - Integer.numberOfLeadingZeros(k));
    }

    public static int getLayerForScaleFactor(float scaleFactor) {
        for (int k = Constants.LAYER_SHIFTS.length - 1; k >= 0; --k)
            if (scaleFactor > Constants.LAYER_SHIFTS[k])
                return Constants.LAYER_SHIFTS.length - 1 - k;
        return Constants.LAYER_SHIFTS.length;
    }

    public static float[] rgb(int rgb) {
        return new float[] {(rgb>>16) / 255f, (rgb>>8&0xff) / 255f, (rgb&0xff) / 255f, 0};
    }

}
