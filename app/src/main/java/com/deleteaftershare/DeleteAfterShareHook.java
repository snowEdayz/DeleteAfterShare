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
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Modern Xposed entry point for the supplied Oplus Screenshot/Gallery builds.
 *
 * The Screenshot process puts the original URI on the Gallery share Intent.
 * The Gallery process adds the corresponding Gallery path only to the argument
 * used by its existing delete-after-share queue. The actual share Intent and
 * Gallery selection remain unchanged.
 */
public final class DeleteAfterShareHook extends XposedModule {
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
    public void onModuleLoaded(ModuleLoadedParam param) {
        ModuleLog.attach(this);
        ModuleLog.debug("module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (!SCREENSHOT_PACKAGE.equals(packageName)
                && !GALLERY_PACKAGE.equals(packageName)) {
            return;
        }

        if (param.getApplicationInfo() == null
                || param.getApplicationInfo().sourceDir == null) {
            ModuleLog.warning("read target APK path failed");
            return;
        }

        if (SCREENSHOT_PACKAGE.equals(packageName)) {
            installScreenshotHooks(this,
                    param.getClassLoader(), param.getApplicationInfo().sourceDir);
        } else {
            installGalleryHooks(this,
                    param.getClassLoader(), param.getApplicationInfo().sourceDir);
        }
    }

    private static synchronized void installScreenshotHooks(
            final XposedInterface framework,
            final ClassLoader classLoader,
            String apkPath) {
        if (screenshotHooksInstalled) {
            return;
        }

        final Class<?> editorActivity;
        try {
            editorActivity = findClass(
                    "com.oplus.screenshot.editor.activity.EditorActivity", classLoader);
        } catch (Throwable throwable) {
            ModuleLog.warning("locate Screenshot classes failed", throwable);
            return;
        }

        final DexKitResolver.ScreenshotBindings bindings =
                DexKitResolver.resolveScreenshot(apkPath, classLoader);

        // These are Android lifecycle overrides with stable framework names.
        // Vendor methods, including obfuscated ones, must use the DexKit
        // bindings below instead of being passed to a name-based hook API.
        hookMethod(
                framework,
                "EditorActivity.onCreate",
                findMethod(editorActivity, "onCreate", Bundle.class),
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            return chain.proceed();
                        } finally {
                            registerEditorActivityReceiver(chain.getThisObject());
                        }
                    }
                });

        hookMethod(
                framework,
                "EditorActivity.onDestroy",
                findMethod(editorActivity, "onDestroy"),
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        unregisterEditorActivityReceiver(chain.getThisObject());
                        return chain.proceed();
                    }
                });

        hookDexKitMethod(
                framework,
                "Screenshot GalleryStartHelper factory",
                bindings.galleryFactory,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        PendingOrigin pending = peekPendingOrigin();
                        Object send = result;
                        if (pending != null && pending.uri != null && send != null) {
                            SEND_ORIGINS.put(send, pending.uri);
                        }
                        return result;
                    }
                });

        hookDexKitMethod(
                framework,
                "Screenshot SendMenuAction share action",
                bindings.sendAction,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Uri origin = readScreenshotOrigin(chain.getThisObject(), bindings);
                        Object activity = getActionActivity(chain.getThisObject(), bindings);
                        if (activity != null) {
                            registerEditorActivityReceiver(activity);
                            if (origin != null) {
                                rememberEditorActivityOrigin(activity, origin);
                            }
                        }
                        PENDING_ORIGINS.get().push(new PendingOrigin(origin));
                        try {
                            return chain.proceed();
                        } finally {
                            ArrayDeque<PendingOrigin> stack = PENDING_ORIGINS.get();
                            if (!stack.isEmpty()) {
                                stack.pop();
                            }
                            if (stack.isEmpty()) {
                                PENDING_ORIGINS.remove();
                            }
                        }
                    }
                });

        hookDexKitMethod(
                framework,
                "Screenshot GalleryStartHelper.Send intent builder",
                bindings.sendIntent,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Uri origin = SEND_ORIGINS.get(chain.getThisObject());
                        List<Object> args = chain.getArgs();
                        Intent intent = args.size() > 0 && args.get(0) instanceof Intent
                                ? (Intent) args.get(0)
                                : null;
                        if (origin != null && intent != null) {
                            intent.putExtra(EXTRA_ORIGIN_URI, origin.toString());
                        }
                        return chain.proceed();
                    }
                });

        screenshotHooksInstalled = true;
        ModuleLog.debug("Screenshot hooks installed");
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
            ModuleLog.warning("read EditorActivity from SendMenuAction failed", throwable);
            return null;
        }
    }

    private static boolean isSelectedShareTargetLaunch(
            Class<?> screenShotShareActivity,
            XposedInterface.Chain chain) {
        if (chain == null) {
            return false;
        }
        List<Object> args = chain.getArgs();
        Object thisObject = chain.getThisObject();
        if (!(thisObject instanceof Context)
                || !screenShotShareActivity.isInstance(thisObject)
                || args.size() < 2
                || !(args.get(0) instanceof Intent)
                || !(args.get(1) instanceof Integer)
                || ((Integer) args.get(1)).intValue() != -1) {
            return false;
        }

        // The Gallery resolver assigns the selected target component before
        // calling Activity.startActivity(...). This filters out unrelated
        // lifecycle launches from the share page.
        return ((Intent) args.get(0)).getComponent() != null;
    }

    private static XposedInterface.Hooker createShareTargetLaunchHook(
            final Class<?> screenShotShareActivity) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                if (isSelectedShareTargetLaunch(screenShotShareActivity, chain)) {
                    scheduleScreenshotShareTargetNotification((Activity) chain.getThisObject());
                }
                return result;
            }
        };
    }

    private static void scheduleScreenshotShareTargetNotification(final Activity galleryActivity) {
        final Context notificationContext;
        try {
            Context application = galleryActivity.getApplicationContext();
            notificationContext = application != null ? application : galleryActivity;
        } catch (Throwable throwable) {
            ModuleLog.warning(
                    "read Gallery application context for delayed share signal failed", throwable);
            return;
        }

        final int taskId;
        try {
            taskId = galleryActivity.getTaskId();
        } catch (Throwable throwable) {
            ModuleLog.warning("read Gallery share task id for delayed share signal failed", throwable);
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
        ModuleLog.debug("Gallery share target launched; delayed Screenshot notification by "
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
            ModuleLog.debug("Gallery share target launched; notified Screenshot process after delay");
        } catch (Throwable throwable) {
            ModuleLog.warning("notify Screenshot after Gallery share target launch failed", throwable);
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
                            ModuleLog.warning("read EditorActivity task id for share signal failed", throwable);
                            return;
                        }
                    }
                    ModuleLog.debug("share target launched; removing EditorActivity task");
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
                ModuleLog.debug("both images deleted; removing EditorActivity task");
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
            ModuleLog.warning("register EditorActivity completion receiver failed", throwable);
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
            ModuleLog.warning("unregister EditorActivity completion receiver failed", throwable);
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
                    ModuleLog.warning(
                            "read EditorActivity task id before target launch finish failed", throwable);
                }

                Context application = null;
                try {
                    application = editor.getApplicationContext();
                } catch (Throwable throwable) {
                    ModuleLog.warning(
                            "read Screenshot application context before target launch finish failed",
                            throwable);
                }

                try {
                    // The legacy Gallery share Activity is placed on top of
                    // this task. Mark the task excluded before finishing its
                    // Screenshot root, otherwise the launcher can retain a
                    // black Recents card for the now-finished task.
                    excludeAppTaskFromRecents(application, taskId);
                    if (!editor.isFinishing()) {
                        editor.finish();
                        ModuleLog.debug("EditorActivity finish requested after share target launch");
                    }
                } catch (Throwable throwable) {
                    ModuleLog.warning("finish EditorActivity after share target launch failed", throwable);
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
                            ModuleLog.debug("original screenshot deleted; removing EditorActivity task");
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
            ModuleLog.warning("register EditorActivity media deletion observer failed", throwable);
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
            ModuleLog.warning("unregister EditorActivity media deletion observer failed", throwable);
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
                    ModuleLog.warning("read EditorActivity task id failed", throwable);
                }

                Context application = null;
                try {
                    application = editor.getApplicationContext();
                } catch (Throwable throwable) {
                    ModuleLog.warning("read Screenshot application context failed", throwable);
                }

                // Ask ActivityManager to remove the owning task as well as
                // finishing the Activity token. This covers OEM task stacks
                // that keep a finished document task in Recents.
                removeAppTask(application, taskId);
                try {
                    editor.finishAndRemoveTask();
                    ModuleLog.debug("EditorActivity finishAndRemoveTask requested, taskId="
                            + taskId);
                } catch (Throwable throwable) {
                    ModuleLog.warning("finish and remove EditorActivity task failed", throwable);
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
                    ModuleLog.debug("ActivityManager excluded EditorActivity task from Recents, taskId="
                            + taskId);
                    return;
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.warning("exclude EditorActivity task from Recents failed", throwable);
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
                    ModuleLog.debug("ActivityManager removed EditorActivity task, taskId="
                            + taskId);
                    return;
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.warning("remove EditorActivity task from ActivityManager failed", throwable);
        }
    }

    private static synchronized void installGalleryHooks(
            final XposedInterface framework,
            final ClassLoader classLoader,
            String apkPath) {
        if (galleryHooksInstalled) {
            return;
        }

        final Class<?> galleryShareActivity;
        try {
            // Resolve the view model up front so a package with an unexpected
            // share-page layout is rejected before any hooks are installed.
            findClass("com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel", classLoader);
            galleryShareActivity = findClass(
                    "com.oplus.gallery.sharepage.GalleryShareActivity", classLoader);
        } catch (Throwable throwable) {
            ModuleLog.warning("locate Gallery classes failed", throwable);
            return;
        }

        final DexKitResolver.GalleryBindings bindings =
                DexKitResolver.resolveGallery(apkPath, classLoader);

        // These are Android lifecycle overrides with stable framework names.
        // Vendor methods, including obfuscated ones, must use the DexKit
        // bindings below instead of a name-based hook API.
        hookMethod(
                framework,
                "GalleryShareActivity.onCreate",
                findMethod(galleryShareActivity, "onCreate", Bundle.class),
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        updateCurrentGalleryOrigin(getActivityIntent(chain.getThisObject()));
                        return chain.proceed();
                    }
                });

        hookMethod(
                framework,
                "GalleryShareActivity.onResume",
                findMethod(galleryShareActivity, "onResume"),
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        updateCurrentGalleryOrigin(getActivityIntent(chain.getThisObject()));
                        return chain.proceed();
                    }
                });

        try {
            final Class<?> screenShotShareActivity = Class.forName(
                    "com.oplus.gallery.sharepage.ScreenShotShareActivity", false, classLoader);
            hookMethod(
                    framework,
                    "ScreenShotShareActivity.onNewIntent",
                    findMethod(screenShotShareActivity, "onNewIntent", Intent.class),
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            Intent intent = args.size() > 0 && args.get(0) instanceof Intent
                                    ? (Intent) args.get(0)
                                    : null;
                            updateCurrentGalleryOrigin(intent);
                            return chain.proceed();
                        }
                    });
            hookMethod(
                    framework,
                    "Activity.startActivityForResult(Intent,int)",
                    findMethod(Activity.class, "startActivityForResult", Intent.class, int.class),
                    createShareTargetLaunchHook(screenShotShareActivity));
            hookMethod(
                    framework,
                    "Activity.startActivityForResult(Intent,int,Bundle)",
                    findMethod(
                            Activity.class,
                            "startActivityForResult",
                            Intent.class,
                            int.class,
                            Bundle.class),
                    createShareTargetLaunchHook(screenShotShareActivity));
        } catch (Throwable throwable) {
            ModuleLog.warning("hook ScreenShotShareActivity share target launch failed", throwable);
        }

        hookDexKitMethod(
                framework,
                "Gallery ShareInnerViewModel initializer",
                bindings.initModel,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        List<Object> args = chain.getArgs();
                        rememberModelOrigin(chain.getThisObject(),
                                args.size() > 0 ? args.get(0) : null);
                        return chain.proceed();
                    }
                });

        hookDexKitMethod(
                framework,
                "Gallery ShareInnerViewModel delete queue method",
                bindings.enqueueDelete,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return proceedDeleteQueue(chain, bindings);
                    }
                });

        hookDexKitMethod(
                framework,
                "Gallery recycle operation",
                bindings.recycle,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        List<Object> args = chain.getArgs();
                        List<?> items = args.size() > 0 && args.get(0) instanceof List
                                ? (List<?>) args.get(0)
                                : null;
                        boolean success = result instanceof Integer
                                && ((Integer) result).intValue() == 1;
                        handleRecycleResult(items, success, bindings);
                        return result;
                    }
                });

        // Retry the URI-to-path conversion immediately before Gallery flushes
        // its existing queue. The actual completion notification is sent only
        // after Gallery's recycle method returns success.
        hookDexKitMethod(
                framework,
                "Gallery ShareUtils queue flush",
                bindings.flushQueue,
                new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        List<Object> args = chain.getArgs();
                        if (args.size() > 0 && Boolean.TRUE.equals(args.get(0))) {
                            retryPendingOriginalAtQueueFlush(bindings);
                        }
                        return chain.proceed();
                    }
                });

        galleryHooksInstalled = true;
        ModuleLog.debug("Gallery hooks installed");
    }

    private static Object proceedDeleteQueue(
            XposedInterface.Chain chain,
            DexKitResolver.GalleryBindings bindings) throws Throwable {
        List<Object> args = chain.getArgs();
        if (args.size() == 0 || !(args.get(0) instanceof Set)) {
            return chain.proceed();
        }

        // This is the same mode check used by Gallery's original method. It
        // prevents a stale Screenshot URI from affecting a normal Gallery share.
        if (!isGalleryShareDeleteMode(chain.getThisObject(), bindings)) {
            return chain.proceed();
        }

        String originString = getModelOrigin(chain.getThisObject(), bindings);
        if (originString == null) {
            return chain.proceed();
        }

        Uri originUri;
        try {
            originUri = Uri.parse(originString);
        } catch (Throwable throwable) {
            ModuleLog.warning("parse original URI failed", throwable);
            return chain.proceed();
        }

        Set<?> selectedItems = (Set<?>) args.get(0);
        Object modelActivity = getModelActivity(chain.getThisObject());
        armPendingDelete(originUri, modelActivity);
        Intent shareIntent = getViewModelShareIntent(chain.getThisObject(), bindings);
        String mimeType = shareIntent == null ? null : shareIntent.getType();
        Object originPath = resolveGalleryPath(bindings, modelActivity, originUri, mimeType);
        if (originPath == null) {
            ModuleLog.warning("Gallery could not resolve original URI " + originUri);
            return chain.proceed();
        }
        rememberPendingDeletePath(originPath);

        if (containsOriginal(selectedItems, originPath, originUri, bindings)) {
            return chain.proceed();
        }

        // ShareInnerViewModel's method only queues paths and persists that
        // queue. Passing a copy keeps the edited image as the sole shared item.
        LinkedHashSet<Object> queueItems = new LinkedHashSet<Object>();
        queueItems.addAll(selectedItems);
        queueItems.add(originPath);
        Object[] updatedArgs = args.toArray(new Object[0]);
        updatedArgs[0] = queueItems;
        ModuleLog.debug("original added to Gallery delete queue at share time");
        return chain.proceed(updatedArgs);
    }

    private static boolean isGalleryShareDeleteMode(
            Object viewModel, DexKitResolver.GalleryBindings bindings) {
        if (bindings.modelDeleteMode == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(bindings.modelDeleteMode.field.get(viewModel));
        } catch (Throwable throwable) {
            ModuleLog.warning("read Gallery share-delete mode failed", throwable);
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
            ModuleLog.warning("read Screenshot original URI failed", throwable);
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
                    ModuleLog.debug("original added during Gallery queue flush");
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.warning("retry original at Gallery queue flush failed", throwable);
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
            ModuleLog.debug("deletion completion sent to Screenshot");
        } catch (Throwable throwable) {
            ModuleLog.warning("notify Screenshot deletion completion failed", throwable);
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

    private static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    private static Method findMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
                // The framework may still be able to hook an inaccessible
                // method, so do not discard an otherwise exact match.
            }
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void hookMethod(
            XposedInterface framework,
            String label,
            Method method,
            XposedInterface.Hooker hooker) {
        if (method == null) {
            ModuleLog.warning("hook " + label + " skipped: method not found");
            return;
        }
        try {
            framework.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
        } catch (Throwable throwable) {
            ModuleLog.warning("hook " + label + " failed", throwable);
        }
    }

    /**
     * Hooks only a method resolved from the target DEX by DexKit. Do not add
     * obfuscated method names to the direct lifecycle hooks above.
     */
    private static void hookDexKitMethod(
            XposedInterface framework,
            String label,
            DexKitResolver.MethodBinding binding,
            XposedInterface.Hooker hooker) {
        if (binding == null) {
            return;
        }
        try {
            framework.hook(binding.method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
        } catch (Throwable throwable) {
            ModuleLog.warning("hook " + label + " failed", throwable);
        }
    }

    private static Object invoke(
            DexKitResolver.MethodBinding binding, Object receiver, Object... args)
            throws Exception {
        return binding.method.invoke(receiver, args);
    }

    private static final class PendingOrigin {
        private final Uri uri;

        private PendingOrigin(Uri uri) {
            this.uri = uri;
        }
    }
}
