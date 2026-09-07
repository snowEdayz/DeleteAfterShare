package com.deleteaftershare;

import de.robv.android.xposed.XposedBridge;

/**
 * Central module logger. Normal messages use DEBUG; failures use WARNING.
 */
final class ModuleLog {
    private static final String TAG = "DeleteAfterShare";

    private ModuleLog() {
    }

    static void log(String message) {
        write("DEBUG", message, null);
    }

    static void warning(String message) {
        write("WARN", message, null);
    }

    static void warning(String message, Throwable throwable) {
        write("WARN", message, throwable);
    }

    private static void write(String level, String message, Throwable throwable) {
        String output = message == null ? "" : message;
        XposedBridge.log(TAG + " [" + level + "] " + output);
        if (throwable != null) {
            XposedBridge.log(throwable);
        }
    }
}
