package com.deleteaftershare;

import android.util.Log;

/**
 * Central module logger. Normal messages use DEBUG; failures use WARNING.
 */
final class ModuleLog {
    private static final String TAG = "DeleteAfterShare";

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
        Log.println(priority, TAG, output);
    }
}
