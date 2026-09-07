package com.deleteaftershare;

import de.robv.android.xposed.XposedBridge;

/**
 * Central module logger. XposedBridge routes these entries to LSPosed Module
 * Log. The level marker is kept in the message because the Legacy API exposes
 * only string and Throwable logging methods.
 */
final class ModuleLog {
    private static final String MODULE_TAG = "DeleteAfterShare";

    private ModuleLog() {
    }

    static void debug(String message) {
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
        XposedBridge.log(MODULE_TAG + " [" + level + "] " + output);
        if (throwable != null) {
            XposedBridge.log(throwable);
        }
    }
}
