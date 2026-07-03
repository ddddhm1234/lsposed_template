package com.example.lsposed_template;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

final class NativeBridge {
    private NativeBridge() {
    }

    static void setLogger(FileLogger logger) {
        if (logger == null) {
            return;
        }

        try {
            nativeSetLogger(logger);
        } catch (Throwable t) {
            Log.e(MainHook.TAG, "failed to set native file logger", t);
            XposedBridge.log("[" + MainHook.TAG + "] failed to set native file logger: " + t);
        }
    }

    private static native void nativeSetLogger(FileLogger logger);
}
