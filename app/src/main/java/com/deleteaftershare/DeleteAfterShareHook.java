package com.deleteaftershare;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
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
    private static final String SCREENSHOT_PACKAGE = "com.oplus.screenshot";
    private static final String GALLERY_PACKAGE = "com.coloros.gallery3d";
    private static final long SHARE_TARGET_NOTIFY_DELAY_MS = 1000L;

    private static final String EXTRA_ORIGIN_URI =
            "com.deleteaftershare.extra.ORIGIN_URI";
    private static final String ACTION_DELETE_COMPLETE =
            "com.deleteaftershare.action.DELETE_COMPLETE";
    private static final String ACTION_SHARE_TARGET_LAUNCHED =
            "com.deleteaftershare.action.SHARE_TARGET_LAUNCHED";
    private static final String EXTRA_SHARE_TASK_ID =
            "com.deleteaftershare.extra.SHARE_TASK_ID";
    private static final String EXTRA_DELETE_ORIGIN =
            "com.deleteaftershare.extra.DELETE_ORIGIN";

    private static final Map<Object, Uri> SEND_ORIGINS =
            Collections.synchronizedMap(new WeakHashMap<Object, Uri>());
    private static final Map<Object, BroadcastReceiver> EDITOR_ACTIVITY_RECEIVERS =
            Collections.synchronizedMap(new WeakHashMap<Object, BroadcastReceiver>());
    private static final Map<Object, ContentObserver> EDITOR_ACTIVITY_OBSERVERS =
            Collections.synchronizedMap(new WeakHashMap<Object, ContentObserver>());
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

    private static boolean screenshotHooksInstalled;
    private static boolean galleryHooksInstalled;

    private static final ThreadLocal<ArrayDeque<PendingOrigin>> PENDING_ORIGINS =
            new ThreadLocal<ArrayDeque<PendingOrigin>>() {
                @Override
                protected ArrayDeque<PendingOrigin> initialValue() {
                    return new ArrayDeque<PendingOrigin>();
                }
            };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SCREENSHOT_PACKAGE.equals(lpparam.packageName)
                && !GALLERY_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        if (lpparam.appInfo == null || lpparam.appInfo.sourceDir == null) {
            logFailure("read target APK path", null);
            return;
        }

        if (SCREENSHOT_PACKAGE.equals(lpparam.packageName)) {
            installScreenshotHooks(lpparam.classLoader, lpparam.appInfo.sourceDir);
        } else {
            installGalleryHooks(lpparam.classLoader, lpparam.appInfo.sourceDir);
        }
    }

    private static synchronized void installScreenshotHooks(
            final ClassLoader classLoader, String apkPath) {
        if (screenshotHooksInstalled) {
            return;
        }

        final Class<?> editorActivity;
        try {
            editorActivity = XposedHelpers.findClass(
                    "com.oplus.screenshot.editor.activity.EditorActivity", classLoader);
        } catch (Throwable throwable) {
            logFailure("locate Screenshot classes", throwable);
            return;
        }

        final DexKitResolver.ScreenshotBindings bindings =
                DexKitResolver.resolveScreenshot(apkPath, classLoader);

        // These are Android lifecycle overrides with stable framework names.
        // Vendor methods, including obfuscated ones, must use the DexKit
        // bindings below instead of being passed to findAndHookMethod.
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

        hookDexKitMethod(
                "Screenshot GalleryStartHelper factory",
                bindings.galleryFactory,
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

        hookDexKitMethod(
                "Screenshot SendMenuAction share action",
                bindings.sendAction,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Uri origin = readScreenshotOrigin(param.thisObject, bindings);
                        Object activity = getActionActivity(param.thisObject, bindings);
                        if (activity != null) {
                            registerEditorActivityReceiver(activity);
                            if (origin != null) {
                                rememberEditorActivityOrigin(activity, origin);
                            }
                        }
                        PENDING_ORIGINS.get().push(new PendingOrigin(origin));
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

        hookDexKitMethod(
                "Screenshot GalleryStartHelper.Send intent builder",
                bindings.sendIntent,
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

        screenshotHooksInstalled = true;
        ModuleLog.log("Screenshot hooks installed");
    }

    private static Object getActionActivity(
            Object action, DexKitResolver.ScreenshotBindings bindings) {
        try {
            if (bindings.actionActivity == null) {
                return null;
            }
            Object value = invoke(bindings.actionActivity, action);
            return value instanceof Activity ? value : null;
        } catch (Throwable throwable) {
            logFailure("read EditorActivity from SendMenuAction", throwable);
            return null;
        }
    }

    private static boolean isSelectedShareTargetLaunch(
            Class<?> screenShotShareActivity,
            XC_MethodHook.MethodHookParam param) {
        if (param == null || param.getThrowable() != null
                || !(param.thisObject instanceof Context)
                || !screenShotShareActivity.isInstance(param.thisObject)
                || param.args.length < 2
                || !(param.args[0] instanceof Intent)
                || !(param.args[1] instanceof Integer)
                || ((Integer) param.args[1]).intValue() != -1) {
            return false;
        }

        // The Gallery resolver assigns the selected target component before
        // calling Activity.startActivity(...). This filters out unrelated
        // lifecycle launches from the share page.
        return ((Intent) param.args[0]).getComponent() != null;
    }

    private static XC_MethodHook createShareTargetLaunchHook(
            final Class<?> screenShotShareActivity) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isSelectedShareTargetLaunch(screenShotShareActivity, param)) {
                    scheduleScreenshotShareTargetNotification((Activity) param.thisObject);
                }
            }
        };
    }

    private static void scheduleScreenshotShareTargetNotification(final Activity galleryActivity) {
        final Context notificationContext;
        try {
            Context application = galleryActivity.getApplicationContext();
            notificationContext = application != null ? application : galleryActivity;
        } catch (Throwable throwable) {
            logFailure("read Gallery application context for delayed share signal", throwable);
            return;
        }

        final int taskId;
        try {
            taskId = galleryActivity.getTaskId();
        } catch (Throwable throwable) {
            logFailure("read Gallery share task id for delayed share signal", throwable);
            return;
        }

        Intent source = galleryActivity.getIntent();
        final String origin = source == null
                ? null : source.getStringExtra(EXTRA_ORIGIN_URI);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                notifyScreenshotShareTargetLaunched(notificationContext, taskId, origin);
            }
        }, SHARE_TARGET_NOTIFY_DELAY_MS);
        ModuleLog.log("Gallery share target launched; delayed Screenshot notification by "
                + SHARE_TARGET_NOTIFY_DELAY_MS + "ms");
    }

    private static void notifyScreenshotShareTargetLaunched(
            Context galleryContext, int taskId, String origin) {
        try {
            Intent signal = new Intent(ACTION_SHARE_TARGET_LAUNCHED);
            signal.setPackage(SCREENSHOT_PACKAGE);
            signal.putExtra(EXTRA_SHARE_TASK_ID, taskId);
            if (origin != null) {
                signal.putExtra(EXTRA_ORIGIN_URI, origin);
            }
            galleryContext.sendBroadcast(signal);
            ModuleLog.log("Gallery share target launched; notified Screenshot process after delay");
        } catch (Throwable throwable) {
            logFailure("notify Screenshot after Gallery share target launch", throwable);
        }
    }

    private static void rememberEditorActivityOrigin(Object activity, Uri origin) {
        if (activity == null || origin == null) {
            return;
        }
        EDITOR_ACTIVITY_ORIGINS.put(activity, origin.toString());
        if (activity instanceof Activity) {
            registerEditorActivityDeletionObserver((Activity) activity, origin);
        }
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
                if (!(target instanceof Activity) || intent == null) {
                    return;
                }

                if (ACTION_SHARE_TARGET_LAUNCHED.equals(intent.getAction())) {
                    String shareOrigin = intent.getStringExtra(EXTRA_ORIGIN_URI);
                    String expectedOrigin = EDITOR_ACTIVITY_ORIGINS.get(target);
                    if (shareOrigin != null && expectedOrigin != null
                            && !shareOrigin.equals(expectedOrigin)) {
                        return;
                    }
                    int shareTaskId = intent.getIntExtra(EXTRA_SHARE_TASK_ID, -1);
                    if (shareOrigin == null && shareTaskId >= 0) {
                        try {
                            if (((Activity) target).getTaskId() != shareTaskId) {
                                return;
                            }
                        } catch (Throwable throwable) {
                            logFailure("read EditorActivity task id for share signal", throwable);
                            return;
                        }
                    }
                    ModuleLog.log("share target launched; removing EditorActivity task");
                    finishEditorActivityAfterTargetLaunch((Activity) target);
                    return;
                }

                if (!ACTION_DELETE_COMPLETE.equals(intent.getAction())) {
                    return;
                }

                String deletedOrigin = intent.getStringExtra(EXTRA_DELETE_ORIGIN);
                String expectedOrigin = EDITOR_ACTIVITY_ORIGINS.get(target);
                if (deletedOrigin == null || expectedOrigin == null
                        || !deletedOrigin.equals(expectedOrigin)) {
                    return;
                }

                Activity editor = (Activity) target;
                ModuleLog.log("both images deleted; removing EditorActivity task");
                removeEditorActivityTask(editor);
            }
        };

        synchronized (EDITOR_ACTIVITY_RECEIVERS) {
            if (EDITOR_ACTIVITY_RECEIVERS.containsKey(activity)) {
                return;
            }
            EDITOR_ACTIVITY_RECEIVERS.put(activity, receiver);
        }

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_DELETE_COMPLETE);
            filter.addAction(ACTION_SHARE_TARGET_LAUNCHED);
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
        unregisterEditorActivityDeletionObserver(activity);
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

    private static void finishEditorActivityAfterTargetLaunch(final Activity editor) {
        if (editor == null) {
            return;
        }

        Runnable finish = new Runnable() {
            @Override
            public void run() {
                int taskId = -1;
                try {
                    taskId = editor.getTaskId();
                } catch (Throwable throwable) {
                    logFailure("read EditorActivity task id before target launch finish", throwable);
                }

                Context application = null;
                try {
                    application = editor.getApplicationContext();
                } catch (Throwable throwable) {
                    logFailure("read Screenshot application context before target launch finish", throwable);
                }

                try {
                    // The legacy Gallery share Activity is placed on top of
                    // this task. Mark the task excluded before finishing its
                    // Screenshot root, otherwise the launcher can retain a
                    // black Recents card for the now-finished task.
                    excludeAppTaskFromRecents(application, taskId);
                    if (!editor.isFinishing()) {
                        editor.finish();
                        ModuleLog.log("EditorActivity finish requested after share target launch");
                    }
                } catch (Throwable throwable) {
                    logFailure("finish EditorActivity after share target launch", throwable);
                }

                final Context retryContext = application;
                final int retryTaskId = taskId;
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        excludeAppTaskFromRecents(retryContext, retryTaskId);
                    }
                }, 200L);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish.run();
        } else {
            editor.runOnUiThread(finish);
        }
    }

    private static void registerEditorActivityDeletionObserver(
            final Activity activity, final Uri origin) {
        if (activity == null || origin == null || !isMediaUri(origin)) {
            return;
        }

        ContentObserver previous = EDITOR_ACTIVITY_OBSERVERS.remove(activity);
        if (previous != null) {
            try {
                activity.getContentResolver().unregisterContentObserver(previous);
            } catch (Throwable ignored) {
                // The previous observer may already be detached with the
                // Activity's content resolver.
            }
        }

        final WeakReference<Activity> activityReference =
                new WeakReference<Activity>(activity);
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri changedUri) {
                final Activity target = activityReference.get();
                if (target == null) {
                    return;
                }

                // MediaProvider can deliver an update before the recycle
                // transaction is fully visible. Recheck after it settles.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isMediaStoreRowMissing(target, origin)) {
                            ModuleLog.log("original screenshot deleted; removing EditorActivity task");
                            removeEditorActivityTask(target);
                        }
                    }
                }, 150L);
            }
        };

        EDITOR_ACTIVITY_OBSERVERS.put(activity, observer);
        try {
            ContentResolver resolver = activity.getContentResolver();
            resolver.registerContentObserver(origin, true, observer);
        } catch (Throwable throwable) {
            EDITOR_ACTIVITY_OBSERVERS.remove(activity);
            logFailure("register EditorActivity media deletion observer", throwable);
        }
    }

    private static void unregisterEditorActivityDeletionObserver(Object activity) {
        ContentObserver observer = EDITOR_ACTIVITY_OBSERVERS.remove(activity);
        if (observer == null || !(activity instanceof Activity)) {
            return;
        }
        try {
            ((Activity) activity).getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable throwable) {
            logFailure("unregister EditorActivity media deletion observer", throwable);
        }
    }

    private static boolean isMediaStoreRowMissing(Activity activity, Uri origin) {
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(
                    origin, new String[]{"_id"}, null, null, null);
            return cursor != null && !cursor.moveToFirst();
        } catch (Throwable ignored) {
            // A transient provider or permission failure is not proof of
            // deletion; wait for the Gallery completion path instead.
            return false;
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

    private static void removeEditorActivityTask(final Activity editor) {
        if (editor == null) {
            return;
        }

        Runnable removal = new Runnable() {
            @Override
            public void run() {
                int taskId = -1;
                try {
                    taskId = editor.getTaskId();
                } catch (Throwable throwable) {
                    logFailure("read EditorActivity task id", throwable);
                }

                Context application = null;
                try {
                    application = editor.getApplicationContext();
                } catch (Throwable throwable) {
                    logFailure("read Screenshot application context", throwable);
                }

                // Ask ActivityManager to remove the owning task as well as
                // finishing the Activity token. This covers OEM task stacks
                // that keep a finished document task in Recents.
                removeAppTask(application, taskId);
                try {
                    editor.finishAndRemoveTask();
                    ModuleLog.log("EditorActivity finishAndRemoveTask requested, taskId="
                            + taskId);
                } catch (Throwable throwable) {
                    logFailure("finish and remove EditorActivity task", throwable);
                }

                // The first ActivityManager request can race the task-stack
                // update. Retry once after the finish request has propagated.
                final Context retryContext = application;
                final int retryTaskId = taskId;
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        removeAppTask(retryContext, retryTaskId);
                    }
                }, 200L);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            removal.run();
        } else {
            editor.runOnUiThread(removal);
        }
    }

    private static void excludeAppTaskFromRecents(Context context, int taskId) {
        if (context == null || taskId < 0) {
            return;
        }
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return;
            }
            List<ActivityManager.AppTask> tasks = activityManager.getAppTasks();
            if (tasks == null) {
                return;
            }
            for (ActivityManager.AppTask task : tasks) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.id == taskId) {
                    task.setExcludeFromRecents(true);
                    ModuleLog.log("ActivityManager excluded EditorActivity task from Recents, taskId="
                            + taskId);
                    return;
                }
            }
        } catch (Throwable throwable) {
            logFailure("exclude EditorActivity task from Recents", throwable);
        }
    }

    private static void removeAppTask(Context context, int taskId) {
        if (context == null || taskId < 0) {
            return;
        }
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return;
            }
            List<ActivityManager.AppTask> tasks = activityManager.getAppTasks();
            if (tasks == null) {
                return;
            }
            for (ActivityManager.AppTask task : tasks) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.id == taskId) {
                    task.finishAndRemoveTask();
                    ModuleLog.log("ActivityManager removed EditorActivity task, taskId="
                            + taskId);
                    return;
                }
            }
        } catch (Throwable throwable) {
            logFailure("remove EditorActivity task from ActivityManager", throwable);
        }
    }

    private static synchronized void installGalleryHooks(
            final ClassLoader classLoader, String apkPath) {
        if (galleryHooksInstalled) {
            return;
        }

        final Class<?> viewModel;
        final Class<?> galleryShareActivity;
        try {
            viewModel = XposedHelpers.findClass(
                    "com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel", classLoader);
            galleryShareActivity = XposedHelpers.findClass(
                    "com.oplus.gallery.sharepage.GalleryShareActivity", classLoader);
        } catch (Throwable throwable) {
            logFailure("locate Gallery classes", throwable);
            return;
        }

        final DexKitResolver.GalleryBindings bindings =
                DexKitResolver.resolveGallery(apkPath, classLoader);

        // These are Android lifecycle overrides with stable framework names.
        // Vendor methods, including obfuscated ones, must use the DexKit
        // bindings below instead of being passed to findAndHookMethod.
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

        try {
            final Class<?> screenShotShareActivity = Class.forName(
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
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "startActivityForResult",
                    Intent.class,
                    int.class,
                    createShareTargetLaunchHook(screenShotShareActivity));
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "startActivityForResult",
                    Intent.class,
                    int.class,
                    Bundle.class,
                    createShareTargetLaunchHook(screenShotShareActivity));
        } catch (Throwable throwable) {
            logFailure("hook ScreenShotShareActivity share target launch", throwable);
        }

        hookDexKitMethod(
                "Gallery ShareInnerViewModel initializer",
                bindings.initModel,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        rememberModelOrigin(param.thisObject,
                                param.args.length > 0 ? param.args[0] : null);
                    }
                });

        hookDexKitMethod(
                "Gallery ShareInnerViewModel delete queue method",
                bindings.enqueueDelete,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        augmentDeleteQueueArgument(param, bindings);
                    }
                });

        hookDexKitMethod(
                "Gallery recycle operation",
                bindings.recycle,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        List<?> items = param.args.length > 0 && param.args[0] instanceof List
                                ? (List<?>) param.args[0]
                                : null;
                        boolean success = param.getResult() instanceof Integer
                                && ((Integer) param.getResult()).intValue() == 1;
                        handleRecycleResult(items, success, bindings);
                    }
                });

        // Retry the URI-to-path conversion immediately before Gallery flushes
        // its existing queue. The actual completion notification is sent only
        // after Gallery's recycle method returns success.
        hookDexKitMethod(
                "Gallery ShareUtils queue flush",
                bindings.flushQueue,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            retryPendingOriginalAtQueueFlush(bindings);
                        }
                    }
                });

        galleryHooksInstalled = true;
        ModuleLog.log("Gallery hooks installed");
    }

    private static void augmentDeleteQueueArgument(
            XC_MethodHook.MethodHookParam param,
            DexKitResolver.GalleryBindings bindings) {
        if (param.args.length == 0 || !(param.args[0] instanceof Set)) {
            return;
        }

        // This is the same mode check used by Gallery's original method. It
        // prevents a stale Screenshot URI from affecting a normal Gallery share.
        if (!isGalleryShareDeleteMode(param.thisObject, bindings)) {
            return;
        }

        String originString = getModelOrigin(param.thisObject, bindings);
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
        Intent shareIntent = getViewModelShareIntent(param.thisObject, bindings);
        String mimeType = shareIntent == null ? null : shareIntent.getType();
        Object originPath = resolveGalleryPath(bindings, modelActivity, originUri, mimeType);
        if (originPath == null) {
            ModuleLog.warning("Gallery could not resolve original URI " + originUri);
            return;
        }
        rememberPendingDeletePath(originPath);

        if (containsOriginal(selectedItems, originPath, originUri, bindings)) {
            return;
        }

        // ShareInnerViewModel's method only queues paths and persists that
        // queue. Passing a copy keeps the edited image as the sole shared item.
        LinkedHashSet<Object> queueItems = new LinkedHashSet<Object>();
        queueItems.addAll(selectedItems);
        queueItems.add(originPath);
        param.args[0] = queueItems;
        ModuleLog.log("original added to Gallery delete queue at share time");
    }

    private static boolean isGalleryShareDeleteMode(
            Object viewModel, DexKitResolver.GalleryBindings bindings) {
        if (bindings.modelDeleteMode == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(bindings.modelDeleteMode.field.get(viewModel));
        } catch (Throwable throwable) {
            logFailure("read Gallery share-delete mode", throwable);
            return false;
        }
    }

    private static Uri readScreenshotOrigin(
            Object action, DexKitResolver.ScreenshotBindings bindings) {
        try {
            if (bindings.actionInfo == null
                    || bindings.imageInfo == null
                    || bindings.originUri == null) {
                return null;
            }
            Object info = invoke(bindings.actionInfo, action);
            if (info == null) {
                return null;
            }
            Object imageInfo = invoke(bindings.imageInfo, info);
            if (imageInfo == null) {
                return null;
            }
            Object origin = invoke(bindings.originUri, imageInfo);
            return origin instanceof Uri ? (Uri) origin : null;
        } catch (Throwable throwable) {
            logFailure("read Screenshot original URI", throwable);
            return null;
        }
    }

    private static Object resolveGalleryPath(
            DexKitResolver.GalleryBindings bindings,
            Object activity,
            Uri originUri,
            String mimeType) {
        Object path = resolvePathFromDataManager(bindings, originUri, mimeType);
        if (isLocalItemPath(path)) {
            return path;
        }

        // Generic URI paths are shareable but are not converted into delete SQL
        // by Gallery's recycle helper. Only accept a real local item path.
        if (!isMediaUri(originUri)) {
            return null;
        }

        path = resolvePathFromLocalMediaItem(bindings, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        path = resolvePathFromShareActivity(bindings, activity, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        // Ask Gallery's own data source to refresh the row before retrying.
        requestMediaSync(bindings, originUri);
        path = resolvePathFromDataManager(bindings, originUri, null);
        if (isLocalItemPath(path)) {
            return path;
        }

        path = resolvePathFromLocalMediaItem(bindings, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        path = resolvePathFromShareActivity(bindings, activity, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        // Last fallback for builds where the local DB exposes the file path
        // before it exposes the MediaStore URI through DataManager.
        path = resolvePathFromMediaStore(bindings, activity, originUri);
        if (isLocalItemPath(path)) {
            return path;
        }

        ModuleLog.warning("original URI did not resolve to a local Gallery item: " + originUri);
        return null;
    }

    private static Object resolvePathFromDataManager(
            DexKitResolver.GalleryBindings bindings, Uri originUri, String mimeType) {
        if (bindings.dataManagerFromUri == null) {
            return null;
        }
        try {
            Object path = invoke(bindings.dataManagerFromUri, null, originUri, mimeType);
            if (path == null && mimeType != null) {
                path = invoke(bindings.dataManagerFromUri, null, originUri, (String) null);
            }
            return path;
        } catch (Throwable throwable) {
            ModuleLog.warning("resolve original URI in Gallery DataManager failed", throwable);
            return null;
        }
    }

    private static Object resolvePathFromLocalMediaItem(
            DexKitResolver.GalleryBindings bindings, Uri originUri) {
        if (bindings.localMediaItem == null) {
            return null;
        }
        try {
            Object mediaItem = invoke(bindings.localMediaItem, null, originUri);
            return getMediaObjectPath(mediaItem, bindings);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolvePathFromShareActivity(
            DexKitResolver.GalleryBindings bindings, Object activity, Uri originUri) {
        if (activity == null || bindings.activityMediaLookup == null) {
            return null;
        }

        try {
            long mediaId = ContentUris.parseId(originUri);
            Object mediaItem = invoke(
                    bindings.activityMediaLookup,
                    activity,
                    Long.valueOf(mediaId),
                    originUri);
            return getMediaObjectPath(mediaItem, bindings);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolvePathFromMediaStore(
            DexKitResolver.GalleryBindings bindings, Object activity, Uri originUri) {
        if (!(activity instanceof Context) || bindings.localPathFromFile == null) {
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
            return invoke(bindings.localPathFromFile, null, filePath);
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

    private static void requestMediaSync(
            DexKitResolver.GalleryBindings bindings, Uri originUri) {
        if (bindings.mediaSyncFactory == null || bindings.mediaSync == null) {
            return;
        }
        try {
            long mediaId = ContentUris.parseId(originUri);
            Object syncManager = invoke(bindings.mediaSyncFactory, null);
            if (syncManager != null) {
                invoke(bindings.mediaSync, syncManager, (Object) new long[]{mediaId});
            }
        } catch (Throwable throwable) {
            ModuleLog.warning("request Gallery media DB refresh failed", throwable);
        }
    }

    private static Object getMediaObjectPath(
            Object mediaItem, DexKitResolver.GalleryBindings bindings) {
        if (mediaItem == null || bindings.mediaObjectPath == null) {
            return null;
        }
        try {
            return bindings.mediaObjectPath.field.get(mediaItem);
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

    private static void retryPendingOriginalAtQueueFlush(
            DexKitResolver.GalleryBindings bindings) {
        Uri originUri;
        Object activity;
        synchronized (PENDING_DELETE_LOCK) {
            originUri = pendingDeleteOrigin;
            activity = pendingDeleteActivity == null ? null : pendingDeleteActivity.get();
        }
        if (originUri == null || bindings.shareQueue == null) {
            return;
        }

        Object originPath = resolveGalleryPath(bindings, activity, originUri, null);
        if (!isLocalItemPath(originPath)) {
            ModuleLog.warning("original still has no local Gallery path at queue flush: "
                    + originUri);
            return;
        }
        rememberPendingDeletePath(originPath);

        try {
            Object queueObject = bindings.shareQueue.field.get(null);
            if (!(queueObject instanceof List)) {
                return;
            }
            List<?> queue = (List<?>) queueObject;
            synchronized (queue) {
                if (!queue.contains(originPath)) {
                    @SuppressWarnings("unchecked")
                    List<Object> mutableQueue = (List<Object>) queueObject;
                    mutableQueue.add(originPath);
                    ModuleLog.log("original added during Gallery queue flush");
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
            List<?> items,
            boolean success,
            DexKitResolver.GalleryBindings bindings) {
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
                ModuleLog.warning("recycle did not confirm both screenshot images");
                return;
            }
        }

        // This runs after the same q0l recycle method Gallery uses for the
        // edited image returns success, so EditorActivity is removed only after
        // both paths have gone through that operation.
        notifyScreenshotDeletionComplete(originUri, bindings);
    }

    private static boolean containsQueueItem(List<?> items, Object expected) {
        try {
            if (items.contains(expected)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the string comparison for unusual list types.
        }

        String expectedValue = String.valueOf(expected);
        for (Object item : items) {
            if (item != null && expectedValue.equals(String.valueOf(item))) {
                return true;
            }
        }
        return false;
    }

    private static void notifyScreenshotDeletionComplete(
            Uri originUri, DexKitResolver.GalleryBindings bindings) {
        try {
            Context application = null;
            if (bindings.appContext != null) {
                Object contextValue = bindings.appContext.field.get(null);
                application = contextValue instanceof Context
                        ? ((Context) contextValue).getApplicationContext()
                        : null;
                if (application == null && contextValue instanceof Context) {
                    application = (Context) contextValue;
                }
            }
            if (application == null || originUri == null) {
                ModuleLog.warning("cannot notify Screenshot: Gallery application context unavailable");
                return;
            }
            Intent intent = new Intent(ACTION_DELETE_COMPLETE);
            intent.setPackage(SCREENSHOT_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(EXTRA_DELETE_ORIGIN, originUri.toString());
            application.sendBroadcast(intent);
            ModuleLog.log("deletion completion sent to Screenshot");
        } catch (Throwable throwable) {
            logFailure("notify Screenshot deletion completion", throwable);
        }
    }

    private static boolean containsOriginal(
            Set<?> selectedItems,
            Object originPath,
            Uri originUri,
            DexKitResolver.GalleryBindings bindings) {
        try {
            if (selectedItems.contains(originPath)) {
                return true;
            }
        } catch (Throwable ignored) {
            // The URI comparison below handles unusual Set implementations.
        }

        if (bindings.dataManagerToUri == null) {
            return false;
        }
        try {
            for (Object selectedItem : selectedItems) {
                if (selectedItem == null) {
                    continue;
                }
                Object selectedUri = invoke(bindings.dataManagerToUri, null, selectedItem);
                if (originUri.equals(selectedUri)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A failed duplicate check must not prevent the normal queue path.
        }
        return false;
    }

    private static void rememberModelOrigin(
            Object viewModel,
            Object activity) {
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

    private static String getModelOrigin(
            Object viewModel, DexKitResolver.GalleryBindings bindings) {
        String origin = GALLERY_MODEL_ORIGINS.get(viewModel);
        if (origin != null) {
            return origin;
        }

        Intent shareIntent = getViewModelShareIntent(viewModel, bindings);
        origin = readOriginExtra(shareIntent);
        if (origin != null) {
            GALLERY_MODEL_ORIGINS.put(viewModel, origin);
            return origin;
        }
        return getCurrentGalleryOrigin();
    }

    private static Intent getViewModelShareIntent(
            Object viewModel, DexKitResolver.GalleryBindings bindings) {
        if (viewModel == null || bindings.modelShareIntent == null) {
            return null;
        }
        try {
            Object value = bindings.modelShareIntent.field.get(viewModel);
            return value instanceof Intent ? (Intent) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Intent getActivityIntent(Object activity) {
        if (!(activity instanceof Activity)) {
            return null;
        }
        try {
            return ((Activity) activity).getIntent();
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

    /**
     * Hooks only a method resolved from the target DEX by DexKit. Do not add
     * obfuscated method names to the direct XposedHelpers hooks above.
     */
    private static void hookDexKitMethod(
            String label,
            DexKitResolver.MethodBinding binding,
            XC_MethodHook hook) {
        if (binding == null) {
            return;
        }
        try {
            XposedBridge.hookMethod(binding.method, hook);
        } catch (Throwable throwable) {
            logFailure("hook " + label, throwable);
        }
    }

    private static Object invoke(
            DexKitResolver.MethodBinding binding, Object receiver, Object... args)
            throws Exception {
        return binding.method.invoke(receiver, args);
    }

    private static void logFailure(String operation, Throwable throwable) {
        if (throwable == null) {
            ModuleLog.warning(operation + " failed");
        } else {
            ModuleLog.warning(operation + " failed", throwable);
        }
    }

    private static final class PendingOrigin {
        private final Uri uri;

        private PendingOrigin(Uri uri) {
            this.uri = uri;
        }
    }
}
