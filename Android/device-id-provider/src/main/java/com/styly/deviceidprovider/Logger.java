package com.styly.deviceidprovider;

import android.util.Log;

public final class Logger {
    static final String TAG = "DeviceIdProvider";
    private Logger() {}
    public static void d(String msg) { Log.d(TAG, msg); }
    public static void e(String msg, Throwable t) { Log.e(TAG, msg, t); }
}
