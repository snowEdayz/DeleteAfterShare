package com.deleteaftershare;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Central module logger. The modern Xposed interface routes entries to the
 * framework log after the module has been attached.
 */
final class ModuleLog {
    private static final String MODULE_TAG = "DeleteAfterShare";
    private static volatile XposedInterface framework;

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

    static void attach(XposedInterface xposedInterface) {
        framework = xposedInterface;
    }

    private static void write(String level, String message, Throwable throwable) {
        String output = message == null ? "" : message;
        int priority = "DEBUG".equals(level) ? Log.DEBUG : Log.WARN;
        String messageWithLevel = "[" + level + "] " + output;
        XposedInterface xposedInterface = framework;
        if (xposedInterface != null) {
            xposedInterface.log(priority, MODULE_TAG, messageWithLevel, throwable);
        } else {
            Log.println(priority, MODULE_TAG, messageWithLevel);
            if (throwable != null) {
                Log.println(priority, MODULE_TAG, Log.getStackTraceString(throwable));
            }
        }
    }
}
