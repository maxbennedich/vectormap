package com.max.vectormap;

import android.app.Activity;
import android.app.ActivityManager;
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
}
