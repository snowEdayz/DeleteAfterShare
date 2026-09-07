package com.deleteaftershare;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

/**
 * Resolves vendor members from the supplied APK's actual DEX definitions.
 *
 * No obfuscated member name is used as a hook target. Stable Android lifecycle
 * overrides are hooked directly by the entry point, but every vendor method
 * and field is resolved from the target DEX. A result is accepted only when
 * its class, complete parameter list, return type, and any additional
 * relationship constraints identify exactly one DEX method or field.
 */
final class DexKitResolver {
    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable throwable) {
            ModuleLog.warning("load DexKit native library failed", throwable);
        }
    }

    private DexKitResolver() {
    }

    static final class MethodBinding {
        final Method method;
        final String descriptor;

        MethodBinding(Method method, String descriptor) {
            this.method = method;
            this.descriptor = descriptor;
        }
    }

    static final class FieldBinding {
        final Field field;
        final String descriptor;

        FieldBinding(Field field, String descriptor) {
            this.field = field;
            this.descriptor = descriptor;
        }
    }

    static final class ScreenshotBindings {
        final MethodBinding galleryFactory;
        final MethodBinding sendAction;
        final MethodBinding sendIntent;
        final MethodBinding actionActivity;
        final MethodBinding actionInfo;
        final MethodBinding imageInfo;
        final MethodBinding originUri;

        ScreenshotBindings(
                MethodBinding galleryFactory,
                MethodBinding sendAction,
                MethodBinding sendIntent,
                MethodBinding actionActivity,
                MethodBinding actionInfo,
                MethodBinding imageInfo,
                MethodBinding originUri) {
            this.galleryFactory = galleryFactory;
            this.sendAction = sendAction;
            this.sendIntent = sendIntent;
            this.actionActivity = actionActivity;
            this.actionInfo = actionInfo;
            this.imageInfo = imageInfo;
            this.originUri = originUri;
        }
    }

    static final class GalleryBindings {
        final MethodBinding initModel;
        final MethodBinding enqueueDelete;
        final MethodBinding recycle;
        final MethodBinding flushQueue;
        final MethodBinding dataManagerFromUri;
        final MethodBinding dataManagerToUri;
        final MethodBinding localMediaItem;
        final MethodBinding localPathFromFile;
        final MethodBinding activityMediaLookup;
        final MethodBinding mediaSyncFactory;
        final MethodBinding mediaSync;
        final FieldBinding modelDeleteMode;
        final FieldBinding modelShareIntent;
        final FieldBinding shareQueue;
        final FieldBinding appContext;
        final FieldBinding mediaObjectPath;

        GalleryBindings(
                MethodBinding initModel,
                MethodBinding enqueueDelete,
                MethodBinding recycle,
                MethodBinding flushQueue,
                MethodBinding dataManagerFromUri,
                MethodBinding dataManagerToUri,
                MethodBinding localMediaItem,
                MethodBinding localPathFromFile,
                MethodBinding activityMediaLookup,
                MethodBinding mediaSyncFactory,
                MethodBinding mediaSync,
                FieldBinding modelDeleteMode,
                FieldBinding modelShareIntent,
                FieldBinding shareQueue,
                FieldBinding appContext,
                FieldBinding mediaObjectPath) {
            this.initModel = initModel;
            this.enqueueDelete = enqueueDelete;
            this.recycle = recycle;
            this.flushQueue = flushQueue;
            this.dataManagerFromUri = dataManagerFromUri;
            this.dataManagerToUri = dataManagerToUri;
            this.localMediaItem = localMediaItem;
            this.localPathFromFile = localPathFromFile;
            this.activityMediaLookup = activityMediaLookup;
            this.mediaSyncFactory = mediaSyncFactory;
            this.mediaSync = mediaSync;
            this.modelDeleteMode = modelDeleteMode;
            this.modelShareIntent = modelShareIntent;
            this.shareQueue = shareQueue;
            this.appContext = appContext;
            this.mediaObjectPath = mediaObjectPath;
        }
    }

    static ScreenshotBindings resolveScreenshot(String apkPath, ClassLoader classLoader) {
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(apkPath);

            MethodBinding galleryFactory = findMethod(
                    bridge,
                    classLoader,
                    "Screenshot GalleryStartHelper factory",
                    signature(
                            "com.oplus.screenshot.global.utils.GalleryStartHelper",
                            "com.oplus.screenshot.global.utils.GalleryStartHelper$Send",
                            "android.content.Context"));

            MethodMatcher sendActionMatcher = signature(
                    "com.oplus.screenshot.editor.menu.action.SendMenuAction",
                    "void",
                    "android.net.Uri");
            if (galleryFactory != null) {
                sendActionMatcher.addInvoke(galleryFactory.descriptor);
            }
            MethodBinding sendAction = findMethod(
                    bridge,
                    classLoader,
                    "Screenshot SendMenuAction share action",
                    sendActionMatcher);

            MethodBinding sendIntent = findMethod(
                    bridge,
                    classLoader,
                    "Screenshot GalleryStartHelper.Send intent builder",
                    signature(
                            "com.oplus.screenshot.global.utils.GalleryStartHelper$Send",
                            "void",
                            "android.content.Intent",
                            "android.net.Uri")
                            .usingStrings(
                                    Arrays.asList(
                                            "ExtraStartFromScreenshot",
                                            "isInternalSdcard"),
                                    StringMatchType.Equals));

            MethodBinding actionActivity = findMethod(
                    bridge,
                    classLoader,
                    "Screenshot SaveMenuAction activity getter",
                    signature(
                            "com.oplus.screenshot.editor.menu.action.SaveMenuAction",
                            "android.app.Activity"));
            MethodBinding actionInfo = findMethod(
                    bridge,
                    classLoader,
                    "Screenshot SaveMenuAction info getter",
                    signature(
                            "com.oplus.screenshot.editor.menu.action.SaveMenuAction",
                            "com.oplus.screenshot.editor.data.b"));
            MethodBinding imageInfo = findMethod(
                    bridge,
                    classLoader,
                    "Screenshot BaseEditorInfo image info getter",
                    signature(
                            "com.oplus.screenshot.editor.data.BaseEditorInfo",
                            "com.oplus.screenshot.editor.data.e"));

            MethodBinding noSaveToFile = null;
            if (actionInfo != null && imageInfo != null) {
                MethodMatcher noSaveToFileMatcher = signature(
                        "com.oplus.screenshot.editor.menu.action.SendMenuAction",
                        "void");
                noSaveToFileMatcher.addInvoke(actionInfo.descriptor);
                noSaveToFileMatcher.addInvoke(imageInfo.descriptor);
                noSaveToFile = findMethod(
                        bridge,
                        classLoader,
                        "Screenshot SendMenuAction original URI caller",
                        noSaveToFileMatcher);
            }

            MethodBinding originUri = null;
            if (noSaveToFile != null) {
                MethodMatcher originUriMatcher = signature(
                        "com.oplus.screenshot.editor.data.e",
                        "android.net.Uri");
                originUriMatcher.addCaller(noSaveToFile.descriptor);
                originUri = findMethod(
                        bridge,
                        classLoader,
                        "Screenshot image info original URI getter",
                        originUriMatcher);
            }

            return new ScreenshotBindings(
                    galleryFactory,
                    sendAction,
                    sendIntent,
                    actionActivity,
                    actionInfo,
                    imageInfo,
                    originUri);
        } catch (Throwable throwable) {
            ModuleLog.warning("resolve Screenshot members with DexKit failed", throwable);
            return new ScreenshotBindings(null, null, null, null, null, null, null);
        } finally {
            close(bridge);
        }
    }

    static GalleryBindings resolveGallery(String apkPath, ClassLoader classLoader) {
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(apkPath);

            // The new Gallery build migrated the path/data model classes while
            // keeping the share-page classes stable. Select the type graph
            // from the APK loaded in this process instead of assuming that the
            // old obfuscated names are still present.
            // q7b exists in both APKs but represents different classes. The
            // old build's q7b is an unrelated Lambda, so use the migrated
            // path class, which is unique to the new Gallery model.
            boolean newGalleryModel = hasClass(
                    classLoader, "com.oplus.aiunit.vision.dst");
            String pathClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.dst"
                    : "com.oplus.aiunit.vision.p3h";
            String dataManagerClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.q7b"
                    : "com.oplus.aiunit.vision.f86";
            String localMediaHelperClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.h3n"
                    : "com.oplus.aiunit.vision.ukd";
            String mediaItemClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.vjo"
                    : "com.oplus.aiunit.vision.tge";
            String localMediaItemClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.u4n"
                    : "com.oplus.aiunit.vision.old";
            String recycleClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.hp00"
                    : "com.oplus.aiunit.vision.q0l";
            String appContextClass = newGalleryModel
                    ? "com.oplus.aiunit.vision.tja"
                    : "com.oplus.aiunit.vision.e29";
            String appContextType = newGalleryModel
                    ? "com.coloros.gallery3d.app.App"
                    : "android.content.Context";
            ModuleLog.debug("Gallery model bindings: "
                    + (newGalleryModel ? "new" : "legacy"));

            MethodBinding initModel = findMethod(
                    bridge,
                    classLoader,
                    "Gallery ShareInnerViewModel initializer",
                    signature(
                            "com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel",
                            "void",
                            "com.oplus.gallery.basebiz.uikit.activity.BaseActivity"));
            MethodBinding enqueueDelete = findMethod(
                    bridge,
                    classLoader,
                    "Gallery ShareInnerViewModel delete queue method",
                    signature(
                            "com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel",
                            "void",
                            "java.util.Set"));

            FieldBinding modelDeleteMode = null;
            if (enqueueDelete != null) {
                FieldMatcher matcher = new FieldMatcher()
                        .declaredClass(
                                "com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel")
                        .type("boolean")
                        .addReadMethod(enqueueDelete.descriptor);
                modelDeleteMode = findField(
                        bridge,
                        classLoader,
                        "Gallery ShareInnerViewModel delete mode field",
                        matcher);
            }

            FieldBinding modelShareIntent = findField(
                    bridge,
                    classLoader,
                    "Gallery ShareInnerViewModel share Intent field",
                    new FieldMatcher()
                            .declaredClass(
                                    "com.oplus.gallery.sharepage.viewmodel.ShareInnerViewModel")
                            .type("android.content.Intent"));

            MethodBinding recycle = findMethod(
                    bridge,
                    classLoader,
                    "Gallery recycle operation",
                    signature(
                            recycleClass,
                            "int",
                            "java.util.List",
                            "boolean"));
            MethodBinding flushQueue = findMethod(
                    bridge,
                    classLoader,
                    "Gallery ShareUtils queue flush",
                    signature(
                            "com.oplus.gallery.business_lib.util.ShareUtils",
                            "void",
                            "boolean"));
            FieldBinding shareQueue = findField(
                    bridge,
                    classLoader,
                    "Gallery ShareUtils delete queue field",
                    new FieldMatcher()
                            .declaredClass("com.oplus.gallery.business_lib.util.ShareUtils")
                            .type("java.util.ArrayList")
                            .modifiers(Modifier.STATIC));

            MethodBinding dataManagerFromUri = findMethod(
                    bridge,
                    classLoader,
                    "Gallery DataManager URI to path",
                    signature(
                            dataManagerClass,
                            pathClass,
                            "android.net.Uri",
                            "java.lang.String"));
            MethodBinding dataManagerToUri = findMethod(
                    bridge,
                    classLoader,
                    "Gallery DataManager path to URI",
                    signature(
                            dataManagerClass,
                            "android.net.Uri",
                            pathClass));
            MethodBinding localMediaItem = findMethod(
                    bridge,
                    classLoader,
                    "Gallery LocalSource URI lookup",
                    signature(
                            localMediaHelperClass,
                            localMediaItemClass,
                            "android.net.Uri"));
            MethodBinding localPathFromFile = findMethod(
                    bridge,
                    classLoader,
                    "Gallery LocalSource file path lookup",
                    signature(
                            localMediaHelperClass,
                            pathClass,
                            "java.lang.String"));
            MethodBinding activityMediaLookup = findMethod(
                    bridge,
                    classLoader,
                    "GalleryShareActivity media ID lookup",
                    signature(
                            "com.oplus.gallery.sharepage.GalleryShareActivity",
                            mediaItemClass,
                            "long",
                            "android.net.Uri")
                            .usingStrings(
                                    Collections.singletonList("queryByMediaId, mediaID="),
                                    StringMatchType.Equals));
            MethodBinding mediaSyncFactory = findMethod(
                    bridge,
                    classLoader,
                    "Gallery media DB sync factory",
                    signature(
                            "com.oplus.aiunit.vision.xj1",
                            "com.oplus.gallery.business_lib.api.IMediaDBSyncDM"));
            MethodBinding mediaSync = findMethod(
                    bridge,
                    classLoader,
                    "Gallery media DB sync method",
                    signature(
                            "com.oplus.gallery.business_lib.api.IMediaDBSyncDM",
                            "void",
                            "long[]"));

            FieldBinding appContext = findField(
                    bridge,
                    classLoader,
                    "Gallery application context field",
                    new FieldMatcher()
                            .declaredClass(appContextClass)
                            .type(appContextType)
                            .modifiers(Modifier.STATIC));
            FieldBinding mediaObjectPath = findField(
                    bridge,
                    classLoader,
                    "Gallery MediaObject path field",
                    new FieldMatcher()
                            .declaredClass(
                                    "com.oplus.gallery.business_lib.model.data.base.MediaObject")
                            .type(pathClass));

            return new GalleryBindings(
                    initModel,
                    enqueueDelete,
                    recycle,
                    flushQueue,
                    dataManagerFromUri,
                    dataManagerToUri,
                    localMediaItem,
                    localPathFromFile,
                    activityMediaLookup,
                    mediaSyncFactory,
                    mediaSync,
                    modelDeleteMode,
                    modelShareIntent,
                    shareQueue,
                    appContext,
                    mediaObjectPath);
        } catch (Throwable throwable) {
            ModuleLog.warning("resolve Gallery members with DexKit failed", throwable);
            return new GalleryBindings(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        } finally {
            close(bridge);
        }
    }

    private static MethodMatcher signature(
            String declaredClass, String returnType, String... parameterTypes) {
        MethodMatcher matcher = new MethodMatcher()
                .declaredClass(declaredClass)
                .returnType(returnType);
        if (parameterTypes.length == 0) {
            matcher.paramTypes();
        } else {
            matcher.paramTypes(parameterTypes);
        }
        return matcher;
    }

    private static boolean hasClass(ClassLoader classLoader, String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static MethodBinding findMethod(
            DexKitBridge bridge,
            ClassLoader classLoader,
            String label,
            MethodMatcher matcher) {
        try {
            List<MethodData> matches = bridge.findMethod(new FindMethod().matcher(matcher));
            if (matches == null || matches.size() != 1) {
                logCandidates(label, matches == null ? 0 : matches.size(), matches);
                return null;
            }
            MethodData data = matches.get(0);
            Method method = data.getMethodInstance(classLoader);
            if (method == null) {
                ModuleLog.warning(label + " returned no reflection method");
                return null;
            }
            method.setAccessible(true);
            ModuleLog.debug("DexKit resolved " + label + " -> " + data.getDescriptor());
            return new MethodBinding(method, data.getDescriptor());
        } catch (Throwable throwable) {
            ModuleLog.warning(label + " failed", throwable);
            return null;
        }
    }

    private static FieldBinding findField(
            DexKitBridge bridge,
            ClassLoader classLoader,
            String label,
            FieldMatcher matcher) {
        try {
            List<FieldData> matches = bridge.findField(new FindField().matcher(matcher));
            if (matches == null || matches.size() != 1) {
                logCandidates(label, matches == null ? 0 : matches.size(), matches);
                return null;
            }
            FieldData data = matches.get(0);
            Field field = data.getFieldInstance(classLoader);
            if (field == null) {
                ModuleLog.warning(label + " returned no reflection field");
                return null;
            }
            field.setAccessible(true);
            ModuleLog.debug("DexKit resolved " + label + " -> " + data.getDescriptor());
            return new FieldBinding(field, data.getDescriptor());
        } catch (Throwable throwable) {
            ModuleLog.warning(label + " failed", throwable);
            return null;
        }
    }

    private static void logCandidates(String label, int count, List<?> matches) {
        StringBuilder message = new StringBuilder();
        message.append("DexKit skipped ")
                .append(label)
                .append("; expected exactly one result, got ")
                .append(count);
        if (matches != null && !matches.isEmpty()) {
            message.append(" [");
            for (int i = 0; i < matches.size(); i++) {
                if (i > 0) {
                    message.append(", ");
                }
                Object match = matches.get(i);
                if (match instanceof MethodData) {
                    message.append(((MethodData) match).getDescriptor());
                } else if (match instanceof FieldData) {
                    message.append(((FieldData) match).getDescriptor());
                } else {
                    message.append(String.valueOf(match));
                }
            }
            message.append(']');
        }
        ModuleLog.warning(message.toString());
    }

    private static void close(DexKitBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.close();
        } catch (Throwable throwable) {
            ModuleLog.warning("close DexKit bridge failed", throwable);
        }
    }
}
