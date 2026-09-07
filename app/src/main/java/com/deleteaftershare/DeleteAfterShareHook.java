package com.deleteaftershare;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed Legacy entry point for the supplied Oplus Screenshot/Gallery builds.
 *
 * The Screenshot process puts the original URI on the Gallery share Intent.
 * The Gallery process adds the corresponding Gallery path only to the argument
 * used by its existing delete-after-share queue. The actual share Intent and
 * Gallery selection remain unchanged.
 */
public final class DeleteAfterShareHook implements IXposedHookLoadPackage {
    private static final String TAG = "DeleteAfterShare";
    private static final String SCREENSHOT_PACKAGE = "com.oplus.screenshot";
    private static final String GALLERY_PACKAGE = "com.coloros.gallery3d";

    /** String extra keeps the two target class loaders independent. */
    private static final String EXTRA_ORIGIN_URI =
            "com.deleteaftershare.extra.ORIGIN_URI";

    private static final Map<Object, Uri> SEND_ORIGINS =
            Collections.synchronizedMap(new WeakHashMap<Object, Uri>());
    private static final Map<Object, String> GALLERY_MODEL_ORIGINS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Object CURRENT_GALLERY_ORIGIN_LOCK = new Object();
    private static String currentGalleryOrigin;

    private static final ThreadLocal<ArrayDeque<PendingOrigin>> PENDING_ORIGINS =
            new ThreadLocal<ArrayDeque<PendingOrigin>>() {
                @Override
                protected ArrayDeque<PendingOrigin> initialValue() {
                    return new ArrayDeque<PendingOrigin>();
                }
            };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SCREENSHOT_PACKAGE.equals(lpparam.packageName)) {
            installScreenshotHooks(lpparam.classLoader);
        } else if (GALLERY_PACKAGE.equals(lpparam.packageName)) {
            installGalleryHooks(lpparam.classLoader);
        }
    }

    private static void installScreenshotHooks(final ClassLoader classLoader) {
        final Class<?> sendMenuAction;
        final Class<?> galleryStartHelper;
        final Class<?> gallerySend;

        try {
            sendMenuAction = XposedHelpers.findClass(
                    "com.oplus.screenshot.editor.menu.action.SendMenuAction", classLoader);
            galleryStartHelper = XposedHelpers.findClass(
                    "com.oplus.screenshot.global.utils.GalleryStartHelper", classLoader);
            gallerySend = XposedHelpers.findClass(
                    "com.oplus.screenshot.global.utils.GalleryStartHelper$Send", classLoader);
        } catch (Throwable throwable) {
            logFailure("locate Screenshot classes", throwable);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    galleryStartHelper,
                    "m12111b",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            PendingOrigin pending = peekPendingOrigin();
                            Object send = param.getResult();
                            if (pending != null && pending.uri != null && send != null) {
                                SEND_ORIGINS.put(send, pending.uri);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook GalleryStartHelper.m12111b", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    sendMenuAction,
                    "m11354y",
                    Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            PENDING_ORIGINS.get().push(
                                    new PendingOrigin(readScreenshotOrigin(param.thisObject)));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ArrayDeque<PendingOrigin> stack = PENDING_ORIGINS.get();
                            if (!stack.isEmpty()) {
                                stack.pop();
                            }
                            if (stack.isEmpty()) {
                                PENDING_ORIGINS.remove();
                            }
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook SendMenuAction.m11354y", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    gallerySend,
                    "m12129r",
                    Intent.class,
                    Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Uri origin = SEND_ORIGINS.get(param.thisObject);
                            Intent intent = param.args.length > 0 && param.args[0] instanceof Intent
                                    ? (Intent) param.args[0]
                                    : null;
                            if (origin != null && intent != null) {
                                intent.putExtra(EXTRA_ORIGIN_URI, origin.toString());
                            }
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook GalleryStartHelper.Send.m12129r", throwable);
        }

        XposedBridge.log(TAG + ": Screenshot hooks installed");
    }

    private static void installGalleryHooks(final ClassLoader classLoader) {
        final Class<?> viewModel;
        final Class<?> baseActivity;
        final Class<?> galleryShareActivity;

        try {
            viewModel = XposedHelpers.findClass(
                    "com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel", classLoader);
            baseActivity = XposedHelpers.findClass(
                    "com.oplus.gallery.basebiz.uikit.activity.BaseActivity", classLoader);
            galleryShareActivity = XposedHelpers.findClass(
                    "com.oplus.gallery.sharepage.GalleryShareActivity", classLoader);
        } catch (Throwable throwable) {
            logFailure("locate Gallery classes", throwable);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    galleryShareActivity,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            updateCurrentGalleryOrigin(getActivityIntent(param.thisObject));
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook GalleryShareActivity.onCreate", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    galleryShareActivity,
                    "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            updateCurrentGalleryOrigin(getActivityIntent(param.thisObject));
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook GalleryShareActivity.onResume", throwable);
        }

        // The Android 35 screenshot route uses ScreenShotShareActivity and can
        // deliver a new share through onNewIntent before recreating the page.
        try {
            Class<?> screenShotShareActivity = Class.forName(
                    "com.oplus.gallery.sharepage.ScreenShotShareActivity", false, classLoader);
            XposedHelpers.findAndHookMethod(
                    screenShotShareActivity,
                    "onNewIntent",
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Intent intent = param.args.length > 0 && param.args[0] instanceof Intent
                                    ? (Intent) param.args[0]
                                    : null;
                            updateCurrentGalleryOrigin(intent);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook ScreenShotShareActivity.onNewIntent", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    viewModel,
                    "m33824a0",
                    baseActivity,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rememberModelOrigin(param.thisObject,
                                    param.args.length > 0 ? param.args[0] : null);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook ShareInnerViewModel.m33824a0", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    viewModel,
                    "m33828e0",
                    Set.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            augmentDeleteQueueArgument(param, classLoader);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook ShareInnerViewModel.m33828e0", throwable);
        }

        XposedBridge.log(TAG + ": Gallery hooks installed");
    }

    private static void augmentDeleteQueueArgument(
            XC_MethodHook.MethodHookParam param, ClassLoader classLoader) {
        if (param.args.length == 0 || !(param.args[0] instanceof Set)) {
            return;
        }

        // This is the same mode check used by the original method. It also
        // prevents a stale process-wide fallback URI from affecting a normal
        // Gallery share opened by another activity.
        if (!isGalleryShareDeleteMode(param.thisObject)) {
            return;
        }

        String originString = getModelOrigin(param.thisObject);
        if (originString == null) {
            return;
        }

        Uri originUri;
        try {
            originUri = Uri.parse(originString);
        } catch (Throwable throwable) {
            logFailure("parse original URI", throwable);
            return;
        }

        Set<?> selectedItems = (Set<?>) param.args[0];
        Intent shareIntent = getViewModelShareIntent(param.thisObject);
        String mimeType = shareIntent == null ? null : shareIntent.getType();
        Object originPath = resolveGalleryPath(classLoader, originUri, mimeType);
        if (originPath == null) {
            XposedBridge.log(TAG + ": Gallery could not resolve original URI " + originUri);
            return;
        }

        if (containsOriginal(selectedItems, originPath, originUri, classLoader)) {
            return;
        }

        // m33828e0 only queues paths and persists the queue. Passing a copy is
        // essential: the caller's real selection must still share the edited
        // image only.
        LinkedHashSet<Object> queueItems = new LinkedHashSet<Object>();
        queueItems.addAll(selectedItems);
        queueItems.add(originPath);
        param.args[0] = queueItems;
        XposedBridge.log(TAG + ": original added to Gallery delete queue at share time");
    }

    private static boolean isGalleryShareDeleteMode(Object viewModel) {
        try {
            return XposedHelpers.getBooleanField(viewModel, "f73508J0");
        } catch (Throwable ignored) {
            // Keep the hook usable if a vendor patch changes only this field.
            return true;
        }
    }

    private static Uri readScreenshotOrigin(Object action) {
        try {
            Object info = XposedHelpers.callMethod(action, "getInfo");
            if (info == null) {
                return null;
            }
            Object imageInfo = XposedHelpers.callMethod(info, "getImageInfo");
            if (imageInfo == null) {
                return null;
            }
            Object origin = XposedHelpers.callMethod(imageInfo, "getOriginUri");
            return origin instanceof Uri ? (Uri) origin : null;
        } catch (Throwable throwable) {
            logFailure("read Screenshot original URI", throwable);
            return null;
        }
    }

    private static Object resolveGalleryPath(
            ClassLoader classLoader, Uri originUri, String mimeType) {
        try {
            Class<?> dataManager = Class.forName(
                    "com.oplus.aiunit.vision.f86", false, classLoader);
            Method fromUri = dataManager.getDeclaredMethod("m11493c", Uri.class, String.class);
            fromUri.setAccessible(true);
            Object path = fromUri.invoke(null, originUri, mimeType);
            if (path == null && mimeType != null) {
                path = fromUri.invoke(null, originUri, (String) null);
            }
            return path;
        } catch (Throwable throwable) {
            logFailure("resolve original URI in Gallery DataManager", throwable);
            return null;
        }
    }

    private static boolean containsOriginal(
            Set<?> selectedItems, Object originPath, Uri originUri, ClassLoader classLoader) {
        try {
            if (selectedItems.contains(originPath)) {
                return true;
            }
        } catch (Throwable ignored) {
            // The URI comparison below is the fallback for unusual Set types.
        }

        try {
            Class<?> dataManager = Class.forName(
                    "com.oplus.aiunit.vision.f86", false, classLoader);
            Class<?> pathClass = Class.forName(
                    "com.oplus.aiunit.vision.p3h", false, classLoader);
            Method toUri = dataManager.getDeclaredMethod("m11494d", pathClass);
            toUri.setAccessible(true);
            for (Object selectedItem : selectedItems) {
                if (selectedItem == null) {
                    continue;
                }
                Object selectedUri = toUri.invoke(null, selectedItem);
                if (originUri.equals(selectedUri)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A failed duplicate check must not prevent the normal queue path.
        }
        return false;
    }

    private static void rememberModelOrigin(Object viewModel, Object activity) {
        if (viewModel == null) {
            return;
        }
        String origin = readOriginExtra(getActivityIntent(activity));
        if (origin == null) {
            origin = getCurrentGalleryOrigin();
        }
        if (origin != null) {
            GALLERY_MODEL_ORIGINS.put(viewModel, origin);
        } else {
            GALLERY_MODEL_ORIGINS.remove(viewModel);
        }
    }

    private static String getModelOrigin(Object viewModel) {
        String origin = GALLERY_MODEL_ORIGINS.get(viewModel);
        if (origin != null) {
            return origin;
        }

        Intent shareIntent = getViewModelShareIntent(viewModel);
        origin = readOriginExtra(shareIntent);
        if (origin != null) {
            GALLERY_MODEL_ORIGINS.put(viewModel, origin);
            return origin;
        }
        return getCurrentGalleryOrigin();
    }

    private static Intent getViewModelShareIntent(Object viewModel) {
        if (viewModel == null) {
            return null;
        }
        try {
            Object value = XposedHelpers.getObjectField(viewModel, "f73518U");
            return value instanceof Intent ? (Intent) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Intent getActivityIntent(Object activity) {
        if (activity == null) {
            return null;
        }
        try {
            Object value = XposedHelpers.callMethod(activity, "getIntent");
            return value instanceof Intent ? (Intent) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void updateCurrentGalleryOrigin(Intent intent) {
        synchronized (CURRENT_GALLERY_ORIGIN_LOCK) {
            currentGalleryOrigin = readOriginExtra(intent);
        }
    }

    private static String getCurrentGalleryOrigin() {
        synchronized (CURRENT_GALLERY_ORIGIN_LOCK) {
            return currentGalleryOrigin;
        }
    }

    private static String readOriginExtra(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_ORIGIN_URI)) {
            return null;
        }
        try {
            String value = intent.getStringExtra(EXTRA_ORIGIN_URI);
            return value == null || value.length() == 0 ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static PendingOrigin peekPendingOrigin() {
        ArrayDeque<PendingOrigin> stack = PENDING_ORIGINS.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    private static void logFailure(String operation, Throwable throwable) {
        XposedBridge.log(TAG + ": " + operation + " failed: " + throwable);
    }

    private static final class PendingOrigin {
        private final Uri uri;

        private PendingOrigin(Uri uri) {
            this.uri = uri;
        }
    }
}
