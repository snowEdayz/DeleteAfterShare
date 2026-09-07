package com.deleteaftershare;

import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/**
 * Central module logger. Messages are routed through the Xposed Bridge tag so
 * LSPosed includes them in its module log while retaining their log priority.
 */
final class ModuleLog {
    private static final String MODULE_TAG = "DeleteAfterShare";
    private static final String FALLBACK_BRIDGE_TAG = "LSPosed-Bridge";
    private static final String BRIDGE_TAG = resolveBridgeTag();

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
        Log.println(priority, BRIDGE_TAG, MODULE_TAG + ": " + output);
    }

    private static String resolveBridgeTag() {
        try {
            Object value = XposedBridge.class.getField("TAG").get(null);
            if (value instanceof String && !((String) value).isEmpty()) {
                return (String) value;
            }
        } catch (Throwable ignored) {
            // Use the current LSPosed tag when the legacy bridge does not
            // expose its tag field.
        }
        return FALLBACK_BRIDGE_TAG;
    }
}
