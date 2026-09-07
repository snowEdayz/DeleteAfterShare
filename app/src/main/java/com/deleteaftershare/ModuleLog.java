package com.deleteaftershare;

import android.util.Log;

/**
 * Central module logger. The fixed Bridge tag is required for LSPosed to put
 * these entries in Module Log instead of Detail Log.
 */
final class ModuleLog {
    private static final String MODULE_TAG = "DeleteAfterShare";
    private static final String LSPOSED_MODULE_LOG_TAG = "LSPosed-Bridge";

    private ModuleLog() {
    }

    static void debug(String message) {
        write(Log.DEBUG, message, null);
    }

    static void warning(String message) {
        write(Log.WARN, message, null);
    }

    static void warning(String message, Throwable throwable) {
        write(Log.WARN, message, throwable);
    }

    private static void write(int priority, String message, Throwable throwable) {
        String output = message == null ? "" : message;
        if (throwable != null) {
            output += "\n" + Log.getStackTraceString(throwable);
        }
        Log.println(priority, LSPOSED_MODULE_LOG_TAG, MODULE_TAG + ": " + output);
    }
}
