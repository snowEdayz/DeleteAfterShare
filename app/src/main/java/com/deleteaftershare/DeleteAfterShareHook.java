package com.deleteaftershare;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final String ACTION_DELETE_COMPLETE =
            "com.deleteaftershare.action.DELETE_COMPLETE";
    private static final String EXTRA_DELETE_ORIGIN =
            "com.deleteaftershare.extra.DELETE_ORIGIN";

    private static final Map<Object, Uri> SEND_ORIGINS =
            Collections.synchronizedMap(new WeakHashMap<Object, Uri>());
    private static final Map<Object, BroadcastReceiver> EDITOR_ACTIVITY_RECEIVERS =
            Collections.synchronizedMap(new WeakHashMap<Object, BroadcastReceiver>());
    private static final Map<Object, String> EDITOR_ACTIVITY_ORIGINS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Map<Object, String> GALLERY_MODEL_ORIGINS =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());
    private static final Map<Object, WeakReference<Object>> GALLERY_MODEL_ACTIVITIES =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Object>>());
    private static final Object CURRENT_GALLERY_ORIGIN_LOCK = new Object();
    private static String currentGalleryOrigin;
    private static final Object PENDING_DELETE_LOCK = new Object();
    private static Uri pendingDeleteOrigin;
    private static WeakReference<Object> pendingDeleteActivity;
    private static Object pendingDeletePath;

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
        final Class<?> editorActivity;

        try {
            sendMenuAction = XposedHelpers.findClass(
                    "com.oplus.screenshot.editor.menu.action.SendMenuAction", classLoader);
            galleryStartHelper = XposedHelpers.findClass(
                    "com.oplus.screenshot.global.utils.GalleryStartHelper", classLoader);
            gallerySend = XposedHelpers.findClass(
                    "com.oplus.screenshot.global.utils.GalleryStartHelper$Send", classLoader);
            editorActivity = XposedHelpers.findClass(
                    "com.oplus.screenshot.editor.activity.EditorActivity", classLoader);
        } catch (Throwable throwable) {
            logFailure("locate Screenshot classes", throwable);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    editorActivity,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            registerEditorActivityReceiver(param.thisObject);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook EditorActivity.onCreate", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    editorActivity,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            unregisterEditorActivityReceiver(param.thisObject);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook EditorActivity.onDestroy", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    galleryStartHelper,
                    "b",
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
            logFailure("hook GalleryStartHelper.b", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    sendMenuAction,
                    "y",
                    Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Uri origin = readScreenshotOrigin(param.thisObject);
                            Object activity = getActionActivity(param.thisObject);
                            if (origin != null && activity != null) {
                                rememberEditorActivityOrigin(activity, origin);
                                registerEditorActivityReceiver(activity);
                            }
                            PENDING_ORIGINS.get().push(
                                    new PendingOrigin(origin));
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
            logFailure("hook SendMenuAction.y", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    gallerySend,
                    "r",
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
            logFailure("hook GalleryStartHelper.Send.r", throwable);
        }

        XposedBridge.log(TAG + ": Screenshot hooks installed");
    }

    private static Object getActionActivity(Object action) {
        try {
            // SaveMenuAction.getContext is the DEX member "m".
            Object value = XposedHelpers.callMethod(action, "m");
            return value instanceof Activity ? value : null;
        } catch (Throwable throwable) {
            logFailure("read EditorActivity from SendMenuAction", throwable);
            return null;
        }
    }

    private static void rememberEditorActivityOrigin(Object activity, Uri origin) {
        if (activity == null || origin == null) {
            return;
        }
        EDITOR_ACTIVITY_ORIGINS.put(activity, origin.toString());
    }

    private static void registerEditorActivityReceiver(final Object activity) {
        if (!(activity instanceof Context)) {
            return;
        }

        synchronized (EDITOR_ACTIVITY_RECEIVERS) {
            if (EDITOR_ACTIVITY_RECEIVERS.containsKey(activity)) {
                return;
            }
        }

        final WeakReference<Object> activityReference = new WeakReference<Object>(activity);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Object target = activityReference.get();
                if (!(target instanceof Activity) || intent == null
                        || !ACTION_DELETE_COMPLETE.equals(intent.getAction())) {
                    return;
                }

                String deletedOrigin = intent.getStringExtra(EXTRA_DELETE_ORIGIN);
                String expectedOrigin = EDITOR_ACTIVITY_ORIGINS.get(target);
                if (deletedOrigin == null || expectedOrigin == null
                        || !deletedOrigin.equals(expectedOrigin)) {
                    return;
                }

                Activity editor = (Activity) target;
                if (!editor.isFinishing()) {
                    XposedBridge.log(TAG + ": both images deleted; removing EditorActivity task");
                    editor.finishAndRemoveTask();
                }
            }
        };

        synchronized (EDITOR_ACTIVITY_RECEIVERS) {
            if (EDITOR_ACTIVITY_RECEIVERS.containsKey(activity)) {
                return;
            }
            EDITOR_ACTIVITY_RECEIVERS.put(activity, receiver);
        }

        try {
            IntentFilter filter = new IntentFilter(ACTION_DELETE_COMPLETE);
            Context context = (Context) activity;
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable throwable) {
            EDITOR_ACTIVITY_RECEIVERS.remove(activity);
            logFailure("register EditorActivity completion receiver", throwable);
        }
    }

    private static void unregisterEditorActivityReceiver(Object activity) {
        EDITOR_ACTIVITY_ORIGINS.remove(activity);
        BroadcastReceiver receiver = EDITOR_ACTIVITY_RECEIVERS.remove(activity);
        if (receiver == null || !(activity instanceof Context)) {
            return;
        }
        try {
            ((Context) activity).unregisterReceiver(receiver);
        } catch (Throwable throwable) {
            logFailure("unregister EditorActivity completion receiver", throwable);
        }
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
                    "a0",
                    baseActivity,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rememberModelOrigin(param.thisObject,
                                    param.args.length > 0 ? param.args[0] : null);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook ShareInnerViewModel.a0", throwable);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    viewModel,
                    "e0",
                    Set.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            augmentDeleteQueueArgument(param, classLoader);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook ShareInnerViewModel.e0", throwable);
        }

        try {
            Class<?> recycleHelper = XposedHelpers.findClass(
                    "com.oplus.aiunit.vision.q0l", classLoader);
            XposedHelpers.findAndHookMethod(
                    recycleHelper,
                    "b",
                    List.class,
                    Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            List<?> items = param.args.length > 0 && param.args[0] instanceof List
                                    ? (List<?>) param.args[0]
                                    : null;
                            boolean success = param.getResult() instanceof Integer
                                    && ((Integer) param.getResult()).intValue() == 1;
                            handleRecycleResult(items, success, classLoader);
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook RecycleHelper.b", throwable);
        }

        // If LocalSource is still catching up when ShareInnerViewModel.e0 runs, retry the
        // conversion at the exact point Gallery flushes its existing queue.
        try {
            Class<?> shareUtils = XposedHelpers.findClass(
                    "com.oplus.gallery.business_lib.util.ShareUtils", classLoader);
            XposedHelpers.findAndHookMethod(
                    shareUtils,
                    "c",
                    Boolean.TYPE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0
                                    && Boolean.TRUE.equals(param.args[0])) {
                                retryPendingOriginalAtQueueFlush(classLoader);
                            }
                        }
                    });
        } catch (Throwable throwable) {
            logFailure("hook ShareUtils.c", throwable);
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
        Object modelActivity = getModelActivity(param.thisObject);
        armPendingDelete(originUri, modelActivity);
        Intent shareIntent = getViewModelShareIntent(param.thisObject);
        String mimeType = shareIntent == null ? null : shareIntent.getType();
        Object originPath = resolveGalleryPath(
                classLoader, modelActivity, originUri, mimeType);
        if (originPath == null) {
            XposedBridge.log(TAG + ": Gallery could not resolve original URI " + originUri);
            return;
        }
        rememberPendingDeletePath(originPath);

        if (containsOriginal(selectedItems, originPath, originUri, classLoader)) {
            return;
        }

        // ShareInnerViewModel.e0 only queues paths and persists the queue. Passing a copy is
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
            return XposedHelpers.getBooleanField(viewModel, "J0");
        } catch (Throwable ignored) {
            // Keep the hook usable if a vendor patch changes only this field.
            return true;
        }
    }

    private static Uri readScreenshotOrigin(Object action) {
        try {
            // SaveMenuAction.getInfo is JADX's source-level name; the DEX
            // member is the Kotlin metadata name "o".
            Object info = XposedHelpers.callMethod(action, "o");
            if (info == null) {
                return null;
            }
            // BaseEditorInfo.getImageInfo is the DEX member "o".
            Object imageInfo = XposedHelpers.callMethod(info, "o");
            if (imageInfo == null) {
                return null;
            }
            // ImageInfo.getOriginUri is the DEX member "f".
            Object origin = XposedHelpers.callMethod(imageInfo, "f");
            return origin instanceof Uri ? (Uri) origin : null;
        } catch (Throwable throwable) {
            logFailure("read Screenshot original URI", throwable);
            return null;
        }
    }

    private static Object resolveGalleryPath(
            ClassLoader classLoader, Object activity, Uri originUri, String mimeType) {
        Object path = resolvePathFromDataManager(classLoader, originUri, mimeType);
        if (isLocalItemPath(path)) {
            return path;
        }

        // f86 falls through to the generic /uri source when LocalSource has
        // not loaded the MediaStore row yet. That path is shareable, but the
        // Gallery recycle helper deliberately does not turn it into delete SQL.
        // Only accept a real local item path here.
        if (!isMediaUri(originUri)) {
            return null;
        }

        path = resolvePathFromLocalMediaItem(classLoader, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        path = resolvePathFromShareActivity(activity, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        // Keep the same Gallery data source and ask it to refresh the row
        // before trying the conversion again. This is needed when the
        // original image was saved before Gallery's local DB caught up.
        requestMediaSync(classLoader, originUri);
        path = resolvePathFromDataManager(classLoader, originUri, null);
        if (isLocalItemPath(path)) {
            return path;
        }

        path = resolvePathFromLocalMediaItem(classLoader, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        path = resolvePathFromShareActivity(activity, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        // Last fallback for builds where the local DB has the file path but
        // has not exposed the MediaStore URI through DataManager yet.
        path = resolvePathFromMediaStore(classLoader, activity, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        XposedBridge.log(TAG + ": original URI did not resolve to a local Gallery item: "
                + originUri);
        return null;
    }

    private static Object resolvePathFromDataManager(
            ClassLoader classLoader, Uri originUri, String mimeType) {
        try {
            Class<?> dataManager = Class.forName(
                    "com.oplus.aiunit.vision.f86", false, classLoader);
            Method fromUri = dataManager.getDeclaredMethod("c", Uri.class, String.class);
            fromUri.setAccessible(true);
            Object path = fromUri.invoke(null, originUri, mimeType);
            if (path == null && mimeType != null) {
                path = fromUri.invoke(null, originUri, (String) null);
            }
            return path;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": resolve original URI in Gallery DataManager failed: "
                    + throwable);
            return null;
        }
    }

    private static Object resolvePathFromLocalMediaItem(ClassLoader classLoader, Uri originUri) {
        try {
            Class<?> localMediaHelper = Class.forName(
                    "com.oplus.aiunit.vision.ukd", false, classLoader);
            Method preload = localMediaHelper.getDeclaredMethod("o", Uri.class);
            preload.setAccessible(true);
            Object mediaItem = preload.invoke(null, originUri);
            return getMediaObjectPath(mediaItem);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolvePathFromShareActivity(Object activity, Uri originUri) {
        if (activity == null) {
            return null;
        }

        try {
            long mediaId = ContentUris.parseId(originUri);
            Method queryByMediaId = findMethod(
                    activity.getClass(), "S0", Long.TYPE, Uri.class);
            if (queryByMediaId == null) {
                return null;
            }
            queryByMediaId.setAccessible(true);
            Object mediaItem = queryByMediaId.invoke(activity, Long.valueOf(mediaId), originUri);
            return getMediaObjectPath(mediaItem);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolvePathFromMediaStore(
            ClassLoader classLoader, Object activity, Uri originUri) {
        if (!(activity instanceof Context)) {
            return null;
        }

        Cursor cursor = null;
        try {
            cursor = ((Context) activity).getContentResolver().query(
                    originUri, new String[]{"_data"}, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int dataIndex = cursor.getColumnIndex("_data");
            if (dataIndex < 0) {
                return null;
            }
            String filePath = cursor.getString(dataIndex);
            if (filePath == null || filePath.length() == 0) {
                return null;
            }

            Class<?> localMediaHelper = Class.forName(
                    "com.oplus.aiunit.vision.ukd", false, classLoader);
            Method pathFromFile = localMediaHelper.getDeclaredMethod(
                    "k", String.class);
            pathFromFile.setAccessible(true);
            return pathFromFile.invoke(null, filePath);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // Ignore a vendor cursor close failure.
                }
            }
        }
    }

    private static void requestMediaSync(ClassLoader classLoader, Uri originUri) {
        try {
            long mediaId = ContentUris.parseId(originUri);
            Class<?> mediaSync = Class.forName(
                    "com.oplus.aiunit.vision.xj1", false, classLoader);
            Object syncManager = XposedHelpers.callStaticMethod(mediaSync, "i");
            if (syncManager != null) {
                XposedHelpers.callMethod(
                        // IMediaDBSyncDM.mo28749l is the DEX member "l".
                        syncManager, "l", (Object) new long[]{mediaId});
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": request Gallery media DB refresh failed: " + throwable);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object getMediaObjectPath(Object mediaItem) {
        if (mediaItem == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(mediaItem, "b");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isMediaUri(Uri uri) {
        return uri != null
                && "content".equals(uri.getScheme())
                && "media".equals(uri.getAuthority());
    }

    private static boolean isLocalItemPath(Object path) {
        if (path == null) {
            return false;
        }
        String value = String.valueOf(path);
        return value.startsWith("/local/item/image/")
                || value.startsWith("/local/item/video/");
    }

    private static void armPendingDelete(Uri originUri, Object activity) {
        synchronized (PENDING_DELETE_LOCK) {
            pendingDeleteOrigin = originUri;
            pendingDeleteActivity = activity == null
                    ? null
                    : new WeakReference<Object>(activity);
            pendingDeletePath = null;
        }
    }

    private static void rememberPendingDeletePath(Object originPath) {
        if (!isLocalItemPath(originPath)) {
            return;
        }
        synchronized (PENDING_DELETE_LOCK) {
            if (pendingDeleteOrigin != null) {
                pendingDeletePath = originPath;
            }
        }
    }

    private static void retryPendingOriginalAtQueueFlush(ClassLoader classLoader) {
        Uri originUri;
        Object activity;
        synchronized (PENDING_DELETE_LOCK) {
            originUri = pendingDeleteOrigin;
            activity = pendingDeleteActivity == null ? null : pendingDeleteActivity.get();
        }
        if (originUri == null) {
            return;
        }

        Object originPath = resolveGalleryPath(classLoader, activity, originUri, null);
        if (!isLocalItemPath(originPath)) {
            XposedBridge.log(TAG + ": original still has no local Gallery path at queue flush: "
                    + originUri);
            return;
        }
        rememberPendingDeletePath(originPath);

        try {
            Class<?> shareUtils = Class.forName(
                    "com.oplus.gallery.business_lib.util.ShareUtils", false, classLoader);
            Object queueObject = XposedHelpers.getStaticObjectField(shareUtils, "b");
            if (!(queueObject instanceof java.util.List)) {
                return;
            }
            java.util.List<?> queue = (java.util.List<?>) queueObject;
            synchronized (queue) {
                if (!queue.contains(originPath)) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> mutableQueue =
                            (java.util.List<Object>) queueObject;
                    mutableQueue.add(originPath);
                    XposedBridge.log(TAG + ": original added during Gallery queue flush");
                }
            }
        } catch (Throwable throwable) {
            logFailure("retry original at Gallery queue flush", throwable);
        }
    }

    private static void clearPendingDelete() {
        synchronized (PENDING_DELETE_LOCK) {
            pendingDeleteOrigin = null;
            pendingDeleteActivity = null;
            pendingDeletePath = null;
        }
    }

    private static void handleRecycleResult(
            List<?> items, boolean success, ClassLoader classLoader) {
        if (items == null) {
            return;
        }

        Uri originUri;
        synchronized (PENDING_DELETE_LOCK) {
            if (pendingDeleteOrigin == null || pendingDeletePath == null
                    || !containsQueueItem(items, pendingDeletePath)) {
                return;
            }

            originUri = pendingDeleteOrigin;
            boolean bothImagesWereQueued = items.size() > 1;
            clearPendingDelete();
            if (!success || !bothImagesWereQueued) {
                XposedBridge.log(TAG + ": recycle did not confirm both screenshot images");
                return;
            }
        }

        notifyScreenshotDeletionComplete(originUri, classLoader);
    }

    private static boolean containsQueueItem(List<?> items, Object expected) {
        try {
            if (items.contains(expected)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the string comparison for vendor list implementations.
        }

        String expectedValue = String.valueOf(expected);
        for (Object item : items) {
            if (item != null && expectedValue.equals(String.valueOf(item))) {
                return true;
            }
        }
        return false;
    }

    private static void notifyScreenshotDeletionComplete(Uri originUri, ClassLoader classLoader) {
        try {
            Class<?> contextHolder = Class.forName(
                    "com.oplus.aiunit.vision.e29", false, classLoader);
            // Gallery's ShareUtils reads its application context from e29.a.
            Object contextValue = XposedHelpers.getStaticObjectField(contextHolder, "a");
            Context application = contextValue instanceof Context
                    ? ((Context) contextValue).getApplicationContext()
                    : null;
            if (application == null && contextValue instanceof Context) {
                application = (Context) contextValue;
            }
            if (application == null || originUri == null) {
                return;
            }
            Intent intent = new Intent(ACTION_DELETE_COMPLETE);
            intent.setPackage(SCREENSHOT_PACKAGE);
            intent.putExtra(EXTRA_DELETE_ORIGIN, originUri.toString());
            application.sendBroadcast(intent);
            XposedBridge.log(TAG + ": deletion completion sent to Screenshot");
        } catch (Throwable throwable) {
            logFailure("notify Screenshot deletion completion", throwable);
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
            Method toUri = dataManager.getDeclaredMethod("d", pathClass);
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
            GALLERY_MODEL_ACTIVITIES.remove(viewModel);
        }
        if (origin != null && activity != null) {
            GALLERY_MODEL_ACTIVITIES.put(viewModel, new WeakReference<Object>(activity));
        }
    }

    private static Object getModelActivity(Object viewModel) {
        WeakReference<Object> reference = GALLERY_MODEL_ACTIVITIES.get(viewModel);
        return reference == null ? null : reference.get();
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
            Object value = XposedHelpers.getObjectField(viewModel, "U");
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
