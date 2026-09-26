package ps.reso.instaeclipse.Xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import org.luckypray.dexkit.DexKitBridge;

import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.mods.ads.AdBlocker;
import ps.reso.instaeclipse.mods.feed.FeedPhotoZoomHook;
import ps.reso.instaeclipse.mods.location.LocationSpoofHook;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.mods.media.ForceReelQualityHook;
import ps.reso.instaeclipse.mods.media.OriginalUploadQualityHook;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.ads.TrackingLinkDisable;
import ps.reso.instaeclipse.mods.devops.BuildExpiredPopupHook;
import ps.reso.instaeclipse.mods.devops.DevOptionsUnlockHook;
import ps.reso.instaeclipse.mods.ghost.GhostChannelMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMSeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostEphemeralKeepHook;
import ps.reso.instaeclipse.mods.ghost.GhostPermanentViewHook;
import ps.reso.instaeclipse.mods.ghost.ViewOnceBadgeHook;
import ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook;
import ps.reso.instaeclipse.mods.ghost.GhostScreenshotDetectionHook;
import ps.reso.instaeclipse.mods.ghost.GhostStorySeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostTypingIndicatorHook;
import ps.reso.instaeclipse.mods.ghost.GhostViewOnceHook;
import ps.reso.instaeclipse.mods.ghost.ScreenshotPermissionHook;
import ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook;
import ps.reso.instaeclipse.mods.media.PostDownloadContextMenuHook;
import ps.reso.instaeclipse.mods.media.ProfilePicDownloadHook;
import ps.reso.instaeclipse.mods.media.ReelDownloadHook;
import ps.reso.instaeclipse.mods.media.StoryDownloadHook;
import ps.reso.instaeclipse.mods.misc.CommentCopyHook;
import ps.reso.instaeclipse.mods.misc.CaptionCopyContextMenuHook;
import ps.reso.instaeclipse.mods.misc.DisableDoubleTapLikeHook;
import ps.reso.instaeclipse.mods.misc.DisableStoryFlippingHook;
import ps.reso.instaeclipse.mods.misc.DisableVideoAutoPlayHook;
import ps.reso.instaeclipse.mods.misc.StoryMentionHook;
import ps.reso.instaeclipse.mods.network.IGNetworkInterceptor;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.FloatingIosBottomNavHook;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;


@SuppressLint("UnsafeDynamicallyLoadedCode")
public class Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    // List of supported Instagram package names (maintained in CommonUtils)
    private static final List<String> SUPPORTED_PACKAGES = CommonUtils.SUPPORTED_PACKAGES;
    public static DexKitBridge dexKitBridge;
    public static ClassLoader hostClassLoader;
    public static String moduleSourceDir;
    private static String moduleLibDir;

    // for dev usage
    /*
    public static void showToast(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(AndroidAppHelper.currentApplication().getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
    */

    @Override
    public void initZygote(StartupParam startupParam) {
        moduleSourceDir = startupParam.modulePath;

        String abi = Build.SUPPORTED_ABIS[0];
        String abiFolder;
        if (abi.equalsIgnoreCase("arm64-v8a")) abiFolder = "arm64";
        else if (abi.equalsIgnoreCase("armeabi-v7a") || abi.equalsIgnoreCase("armeabi") || abi.equalsIgnoreCase("armv8i"))
            abiFolder = "arm";
        else if (abi.equalsIgnoreCase("x86")) abiFolder = "x86";
        else if (abi.equalsIgnoreCase("x86_64")) abiFolder = "x86_64";
        else abiFolder = abi;

        moduleLibDir = moduleSourceDir.substring(0, moduleSourceDir.lastIndexOf("/")) + "/lib/" + abiFolder;
    }

    /**
     * Initializes DexKit without making it a hard dependency of the whole module.
     *
     * Instagram is delivered as split APKs on many installs. DexKit's ClassLoader
     * mode sees the complete target dex path, while sourceDir-only scanning can miss
     * code that moved into a split. We therefore prefer ClassLoader mode and retain
     * sourceDir as a compatibility fallback for older installs.
     */
    private static synchronized DexKitBridge ensureDexKit(
            Context context,
            XC_LoadPackage.LoadPackageParam lpparam
    ) {
        if (dexKitBridge != null) return dexKitBridge;

        // The heavy installer now runs only after Application.attach() in the main
        // Instagram process, so this Context should normally be non-null. Keep the
        // downstream null checks anyway for defensive compatibility with odd loaders.
        Context safeContext = context;

        Throwable lastLoadError = null;
        boolean nativeLoaded = false;

        // Preferred path documented by DexKit.
        try {
            System.loadLibrary("dexkit");
            nativeLoaded = true;
        } catch (Throwable t) {
            lastLoadError = t;
        }

        // LSPosed may load the module with a class loader whose native search path
        // does not include the module APK. Resolve our installed nativeLibraryDir.
        if (!nativeLoaded) {
            try {
                if (safeContext == null) throw new IllegalStateException("target Context unavailable");
                Context moduleContext = safeContext.createPackageContext(
                        CommonUtils.MY_PACKAGE_NAME,
                        Context.CONTEXT_IGNORE_SECURITY
                );
                String nativeLibraryDir = moduleContext.getApplicationInfo().nativeLibraryDir;
                if (nativeLibraryDir != null && !nativeLibraryDir.isEmpty()) {
                    java.io.File lib = new java.io.File(nativeLibraryDir, "libdexkit.so");
                    if (lib.isFile()) {
                        System.load(lib.getAbsolutePath());
                        nativeLoaded = true;
                    }
                }
            } catch (Throwable t) {
                lastLoadError = t;
            }
        }

        // Legacy path retained for LSPosed/LSPatch setups that expose the module
        // APK's extracted lib directory next to modulePath.
        if (!nativeLoaded && moduleLibDir != null) {
            try {
                java.io.File lib = new java.io.File(moduleLibDir, "libdexkit.so");
                if (lib.isFile()) {
                    System.load(lib.getAbsolutePath());
                    nativeLoaded = true;
                }
            } catch (Throwable t) {
                lastLoadError = t;
            }
        }

        if (!nativeLoaded) {
            ModuleLog.line(
                    "(InstaLy | DexKit): native library unavailable; continuing with non-DexKit hooks",
                    lastLoadError
            );
            return null;
        }

        try {
            ClassLoader targetClassLoader = safeContext != null ? safeContext.getClassLoader() : null;
            if (targetClassLoader == null) targetClassLoader = lpparam.classLoader;
            dexKitBridge = DexKitBridge.create(targetClassLoader, true);
            ModuleLog.line("(InstaLy | DexKit): initialized from attached target ClassLoader (split-APK aware)");
            return dexKitBridge;
        } catch (Throwable classLoaderError) {
            ModuleLog.line(
                    "(InstaLy | DexKit): ClassLoader scan failed; falling back to sourceDir",
                    classLoaderError
            );
        }

        try {
            dexKitBridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
            ModuleLog.line("(InstaLy | DexKit): initialized from sourceDir fallback");
        } catch (Throwable sourceDirError) {
            dexKitBridge = null;
            ModuleLog.line(
                    "(InstaLy | DexKit): initialization failed; non-DexKit hooks will stay active",
                    sourceDirError
            );
        }
        return dexKitBridge;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SUPPORTED_PACKAGES.contains(lpparam.packageName)) return;

        // Never make DexKit/native loading a prerequisite for installing the
        // Application.attach bootstrap. A native loader failure must not make the
        // LSPosed module appear completely dead.
        hostClassLoader = lpparam.classLoader;

        try {
            hookInstagram(lpparam);
        } catch (Throwable t) {
            ModuleLog.line(
                    "(InstaLy): failed to install bootstrap for " + lpparam.packageName,
                    t
            );
        }
    }

    private static boolean isMainInstagramProcess(Context context) {
        if (context == null) return false;
        try {
            String processName = android.app.Application.getProcessName();
            String packageName = context.getPackageName();
            return processName == null || processName.isEmpty() || processName.equals(packageName);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void hookInstagram(XC_LoadPackage.LoadPackageParam lpparam) {

        try {


            XposedHelpers.findAndHookMethod(android.app.Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // Install CommentCopyButtonHook BEFORE Instagram's Application.attach() runs
                    // so we catch any ViewBinding pre-inflation that happens during attach()
                    Context context = (Context) param.args[0];
                    if (!isMainInstagramProcess(context)) return;

                    ClassLoader attachedClassLoader = context.getClassLoader();
                    if (attachedClassLoader != null) hostClassLoader = attachedClassLoader;
                    try {
                        SettingsManager.init(context);
                        SettingsManager.loadAllFlags(context);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Settings): early preference init failed", t);
                    }

                    // Init DexKit cache — checks IG version to decide if saved descriptors are valid.
                    // Must run before any hook that calls DexKitCache.isCacheValid().
                    try {
                        android.content.pm.PackageInfo pi =
                                context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                        long vc = pi.getLongVersionCode();
                        DexKitCache.init(context, String.valueOf(vc));
                    } catch (Throwable e) {
                        ModuleLog.line("(DexKitCache) ❌ init failed: " + e.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {

                    // Setup context and preferences, but do not make preference/logging
                    // failures a prerequisite for installing hooks.
                    Context context = (Context) param.args[0];

                    if (!isMainInstagramProcess(context)) {
                        ModuleLog.line("(InstaLy | Process): skipping auxiliary process "
                                + android.app.Application.getProcessName());
                        return;
                    }

                    ClassLoader attachedClassLoader = context.getClassLoader();
                    if (attachedClassLoader != null) hostClassLoader = attachedClassLoader;
                    try {
                        SettingsManager.init(context);
                        SettingsManager.loadAllFlags(context);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Settings): preference init failed", t);
                    }

                    // In-app log viewer: every ModuleLog.line(...) call across the hook codebase
                    // appends to this buffer, which the companion app can read via IPC.
                    try {
                        Logging.init(context, "instaeclipse_module.log");
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Logging): in-app logger init failed", t);
                    }

                    // DexKit discovery/indexing is intentionally NOT started on the main
                    // Application.attach() path. It is initialized by the low-priority hook
                    // installer below so Instagram can finish startup without waiting for it.

                    try {
                        android.content.pm.PackageInfo pi =
                                context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                        ModuleLog.line("(InstaLy): Instagram " + pi.versionName
                                + " (" + pi.getLongVersionCode() + ") in process "
                                + android.app.Application.getProcessName());
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy): could not read Instagram version", t);
                    }

                    // Pull downloader path from companion app's cache so it's available even
                    // when Instagram was started without ever receiving the sync broadcast.
                    try {
                        XSharedPreferences cp = new XSharedPreferences(CommonUtils.MY_PACKAGE_NAME, "instaeclipse_cache");
                        cp.reload();
                        String path = cp.getString("downloaderCustomPath", "");
                        String uri  = cp.getString("downloaderCustomUri",  "");
                        if (!path.isEmpty()) FeatureFlags.downloaderCustomPath = path;
                        if (!uri.isEmpty())  FeatureFlags.downloaderCustomUri  = uri;
                    } catch (Throwable ignored) {
                    }

                    try {
                        FeatureManager.refreshFeatureStatus(); // Update internal feature states
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Features): status refresh failed", t);
                    }

                    // Activate the LSPosed Sync Bridge to listen to FeaturesFragment updates.
                    // A receiver failure must not prevent the actual hooks from installing.
                    try {
                        registerSyncReceiver(context);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Sync): receiver registration failed", t);
                    }

                    try {
                        UIHookManager.registerConfigImportReceiver(context);
                    } catch (Throwable e) {
                        ModuleLog.line("(InstaLy | ImportReceiver): ❌ " + e.getMessage());
                    }
                    try {
                        UIHookManager.registerSettingsRestoreReceiver(context);
                    } catch (Throwable e) {
                        ModuleLog.line("(InstaLy | RestoreReceiver): ❌ " + e.getMessage());
                    }
                    // The framework Activity fallback is stable and cheap, so install it
                    // immediately. Instagram-specific DexKit lifecycle discovery is deferred.
                    try {
                        UIHookManager.installActivityFallback();
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | UI): bootstrap failed", t);
                    }

                    final ClassLoader featureClassLoader =
                            hostClassLoader != null ? hostClassLoader : lpparam.classLoader;

                    // Network filtering protects privacy/ghost/distraction features and does
                    // not require DexKit, so keep it on the fast path before Instagram starts
                    // issuing its first feed/DM requests.
                    try {
                        new IGNetworkInterceptor().handleInterceptor(lpparam);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Interceptor): ❌ Failed to hook", t);
                    }

                    // DexKit indexing and the large resolver batch used to run synchronously
                    // inside Application.attach(), directly extending Instagram's startup.
                    // Install them on one low-priority worker instead.
                    Context appContext = context.getApplicationContext();
                    final Context hookContext = appContext != null ? appContext : context;
                    Thread hookInstaller = new Thread(() -> {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        } catch (Throwable ignored) {}

                        long startedAt = android.os.SystemClock.uptimeMillis();
                        try {
                            ensureDexKit(hookContext, lpparam);
                            if (dexKitBridge != null) {
                                try {
                                    new UIHookManager().mainActivity(featureClassLoader);
                                } catch (Throwable t) {
                                    ModuleLog.line("(InstaLy | UI): deferred DexKit lifecycle discovery failed", t);
                                }
                            } else {
                                ModuleLog.line("(InstaLy | DexKit): unavailable; installing non-DexKit fallbacks only");
                            }

                    // --- Feature Hooks ---

                    // Developer Options
                    try {
                        new DevOptionsUnlockHook().handleDevOptions(dexKitBridge);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | DevOptions): ❌ Failed to hook", t);
                    }

                    // Ghost Mode
                    try {
                        new GhostDMSeenHook().handleSeenBlock(dexKitBridge); // DM Seen
                        new GhostDMMarkAsReadHook(moduleSourceDir).install(featureClassLoader); // Mark as Read Button
                        new GhostChannelMarkAsReadHook().install(featureClassLoader); // Channel Mark as Read Button
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | GhostSeen): ❌ Failed to hook", t);
                    }

                    try {
                        new GhostTypingIndicatorHook().handleTypingBlock(dexKitBridge); // DM Typing
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | GhostTyping): ❌ Failed to hook", t);
                    }

                    try {
                        new GhostScreenshotDetectionHook().handleScreenshotBlock(dexKitBridge); // Screenshot
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | GhostScreenshot): ❌ Failed to hook", t);
                    }

                    try {
                        new ScreenshotPermissionHook().install(featureClassLoader); // Allow Screenshots
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | ScreenshotPermission): ❌ Failed to hook", t);
                    }

                    try {
                        new GhostViewOnceHook().handleViewOnceBlock(dexKitBridge); // View Once
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | GhostViewOnce): ❌ Failed to hook", t);
                    }

                    try {
                        new GhostStorySeenHook().handleStorySeenBlock(dexKitBridge); // Story Seen
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | GhostStorySeen): ❌ Failed to hook", t);
                    }

                    try {
                        new KeepUnsentMessagesHook().install(dexKitBridge, featureClassLoader); // Keep Unsent
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | KeepUnsent): ❌ Failed to hook", t);
                    }

                    try {
                        new ps.reso.instaeclipse.mods.ghost.UnsentThreadButtonHook().install(featureClassLoader); // per-thread unsent button
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | UnsentBtn): ❌ Failed to hook", t);
                    }

                    try {
                        new ps.reso.instaeclipse.mods.ghost.HideChatsHook().install(dexKitBridge, featureClassLoader); // Hide Specific Chats
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | HideChats): ❌ Failed to hook", t);
                    }

                    if (FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) {
                        try {
                            new ps.reso.instaeclipse.mods.ui.CustomFontHook().install(featureClassLoader);
                        } catch (Throwable t) {
                            ModuleLog.line("(InstaLy | CustomFont): ❌ Failed to hook", t);
                        }
                    }

                    try {
                        ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook metaAi = new ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook();
                        metaAi.install(featureClassLoader);              // composer/search XML layouts
                        metaAi.installReels(dexKitBridge, featureClassLoader); // reels Litho unit (#179)
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | RemoveMetaAI): ❌ Failed to hook", t);
                    }

                    // Disable Repost (feed + reels) — UI/action level; network drop is ineffective
                    try {
                        new ps.reso.instaeclipse.mods.ui.DisableRepostHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | DisableRepost): ❌ Failed to hook", t);
                    }

                    try {
                        new ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook().install(featureClassLoader); // Lock DMs (#182)
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | LockDMs): ❌ Failed to hook", t);
                    }

                    // Hide in-feed widget units (suggested users panels, surveys, carousels, etc.)
                    try {
                        new HideSuggestedFeedItemsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | HideSuggested): ❌ Failed to hook", t);
                    }

                    // Ads Blocker
                    try {
                        new AdBlocker().disableSponsoredContent(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | AdBlocker): ❌ Failed to hook", t);
                    }

                    // tracking link disable
                    try {
                        new TrackingLinkDisable().disableTrackingLinks(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | TrackingLinkDisable): ❌ Failed to hook", t);
                    }

                    // Miscellaneous
                    try {
                        new DisableStoryFlippingHook().handleStoryFlippingDisable(dexKitBridge); // Story Flipping
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | StoryFlipping): ❌ Failed to hook", t);
                    }

                    // Story Mentions
                    try {
                        new StoryMentionHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | StoryMentions): ❌ Failed to hook", t);
                    }

                    // Comment Copy
                    try {
                        new CommentCopyHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | CopyComment): ❌ Failed to hook", t);
                    }

                    // Caption Copy
                    try {
                        new CaptionCopyContextMenuHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | Caption): ❌ Failed to hook", t);
                    }

                    // Disable Double Tap to Like
                    try {
                        new DisableDoubleTapLikeHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | DoubleTapLike): ❌ Failed to hook", t);
                    }

                    // Photo Zoom (long-press)
                    try {
                        new FeedPhotoZoomHook().install(featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | PhotoZoom): ❌ Failed to hook", t);
                    }

                    // Location Spoof
                    try {
                        new LocationSpoofHook().install(featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | SpoofLocation): ❌ Failed to hook", t);
                    }

                    // Custom Theme hooks intercept very hot Resources/TypedArray APIs.
                    // Do not install them at all while the feature is disabled.
                    if (FeatureFlags.customThemeEnabled) {
                        try {
                            new IgThemeHook().install(hostClassLoader);
                        } catch (Throwable t) {
                            ModuleLog.line("(InstaLy | Theme): ❌ Failed to hook", t);
                        }
                    }

                    // Force Reel Quality
                    try {
                        new ForceReelQualityHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | ForceReelQuality): ❌ Failed to hook", t);
                    }

                    // Original / maximum upload quality — uses Instagram's own
                    // high_quality_media_upload preference gate, discovered at runtime.
                    try {
                        new OriginalUploadQualityHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | UploadQuality): ❌ Failed to hook", t);
                    }

                    try {
                        new DisableVideoAutoPlayHook().handleAutoPlayDisable(dexKitBridge); // Video Autoplay
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | AutoPlayDisable): ❌ Failed to hook", t);
                    }

                    // Build Expired Popup
                    try {
                        new BuildExpiredPopupHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | BuildExpired): ❌ Failed to hook", t);
                    }

                    // Media Download (feed)
                    try {
                        new FeedVideoDownloadHook().install(featureClassLoader);
                        FeedVideoDownloadHook.installVideoUrlCaptureHook(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | MediaDownload): ❌ Failed to hook", t);
                    }

                    // Post Download — three-dots menu (replaces floating button + long-press)
                    try {
                        new PostDownloadContextMenuHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | PostDownload): ❌ Failed to hook", t);
                    }

                    // Save Instants (#184) — long-press a received Instant (quicksnap) to save it
                    try {
                        new ps.reso.instaeclipse.mods.media.InstantSaveHook().install(featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | InstantSave): ❌ Failed to hook", t);
                    }

                    // Upload Instants from gallery (#199) — swap gallery bitmap into quicksnap send
                    try {
                        new ps.reso.instaeclipse.mods.media.InstantUploadHook().install(featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | InstantUpload): ❌ Failed to hook", t);
                    }

                    // Keep Ephemeral Messages
                    try {
                        new GhostEphemeralKeepHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | EphemeralHook): ❌ Failed to hook", t);
                    }

                    // Permanent View Mode (view-once / view-twice → permanent)
                    try {
                        new GhostPermanentViewHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | ViewOnceMedia): ❌ Failed to hook", t);
                    }

                    // Restore IG's native view-once/twice corner icon when Permanent View is on
                    try {
                        new ViewOnceBadgeHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | VOBadge): ❌ Failed to hook", t);
                    }

                    // Story Download
                    try {
                        new StoryDownloadHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | StoryDownload): ❌ Failed to hook", t);
                    }

                    // Reel Download
                    try {
                        new ReelDownloadHook().install(dexKitBridge, featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | ReelDownload): ❌ Failed to hook", t);
                    }

                    // Profile Picture Download
                    try {
                        ProfilePicDownloadHook.install();
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | ProfileDownload): ❌ Failed to hook", t);
                    }

                    // Crash guard: drop tasks rejected by already-shut-down executors (carousel/
                    // realtime teardown race on IG 446+/447.0.0.39+) instead of letting AbortPolicy
                    // throw and hard-crash the app.
                    try {
                        new ps.reso.instaeclipse.mods.core.TerminatedExecutorGuard().install(featureClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaLy | ExecGuard): ❌ Failed to hook", t);
                    }

                        } catch (Throwable t) {
                            ModuleLog.line("(InstaLy | Hooks): deferred installer failed", t);
                        } finally {
                            ModuleLog.line("(InstaLy | Performance): deferred hooks ready in "
                                    + (android.os.SystemClock.uptimeMillis() - startedAt) + " ms");
                        }
                    }, "InstaLy-HookInstaller");

                    // Do not make DexKit compete with Instagram's first-frame work. Start as
                    // soon as the main queue becomes idle; the delayed fallback guarantees
                    // installation even on a continuously busy startup.
                    Runnable startHookInstaller = () -> {
                        if (hookInstaller.getState() == Thread.State.NEW) {
                            hookInstaller.start();
                        }
                    };
                    try {
                        android.os.Looper.myQueue().addIdleHandler(() -> {
                            startHookInstaller.run();
                            return false;
                        });
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(startHookInstaller, 1200L);
                    } catch (Throwable t) {
                        startHookInstaller.run();
                    }

                }

            });

        } catch (Throwable t) {
            ModuleLog.line("(InstaLy): failed to hook " + lpparam.packageName, t);
        }
    }

    /**
     * Injects a dynamic receiver into Instagram to listen for settings changes
     * sent from the InstaLy companion app (FeaturesFragment staging system).
     */
    private void registerSyncReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF".equals(action)) {
                    String key = intent.getStringExtra("key");
                    boolean value = intent.getBooleanExtra("value", false);

                    ModuleLog.line("(InstaLy) Sync: Updating " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putBoolean(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    FeatureManager.refreshFeatureStatus();

                    // Expensive framework-wide hooks are lazy: zero trampoline overhead until
                    // the user actually enables them.
                    if ("customThemeEnabled".equals(key) && value) {
                        try { new IgThemeHook().install(hostClassLoader); }
                        catch (Throwable t) { ModuleLog.line("(InstaLy | Theme): lazy install failed", t); }
                    }
                    if (("customFontEnabled".equals(key) || "customEmojiEnabled".equals(key)) && value) {
                        try { new ps.reso.instaeclipse.mods.ui.CustomFontHook().install(hostClassLoader); }
                        catch (Throwable t) { ModuleLog.line("(InstaLy | Font): lazy install failed", t); }
                    }
                    if ("floatingIosBottomNavbar".equals(key)) {
                        try {
                            android.app.Activity activity = UIHookManager.getCurrentActivity();
                            if (activity != null) FloatingIosBottomNavHook.refresh(activity);
                        } catch (Throwable t) {
                            ModuleLog.line("(InstaLy | FloatingNav): live refresh failed", t);
                        }
                    }

                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING".equals(action)) {
                    String key = intent.getStringExtra("key");
                    String value = intent.getStringExtra("value");

                    ModuleLog.line("(InstaLy) Sync: Updating string pref " + key);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putString(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT".equals(action)) {
                    String key = intent.getStringExtra("key");
                    int value = intent.getIntExtra("value", 0);

                    ModuleLog.line("(InstaLy) Sync: Updating int pref " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putInt(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    FeatureManager.refreshFeatureStatus();
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if (CommonUtils.ACTION_REQUEST_LOGS.equals(action)) {
                    try {
                        Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                        reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                        reply.putExtra(CommonUtils.EXTRA_LOG_TEXT, Logging.getSnapshotForIpc());
                        reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                        ctx.sendBroadcast(reply);
                    } catch (Throwable t) {
                        Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                        reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                        reply.putExtra(CommonUtils.EXTRA_LOG_ERROR, String.valueOf(t.getMessage()));
                        reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                        ctx.sendBroadcast(reply);
                    }

                } else if (CommonUtils.ACTION_CLEAR_LOGS.equals(action)) {
                    Logging.clear();

                } else if ("ps.reso.instaeclipse.ACTION_REQUEST_PREFS".equals(action)) {
                    ModuleLog.line("(InstaLy) Sync: Companion app requested current preferences.");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_PREFS");
                    reply.setPackage("ps.reso.instaeclipse");

                    Bundle bundle = new Bundle();
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        if (entry.getValue() instanceof Boolean) {
                            bundle.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        } else if (entry.getValue() instanceof String) {
                            bundle.putString(entry.getKey(), (String) entry.getValue());
                        } else if (entry.getValue() instanceof Integer) {
                            bundle.putInt(entry.getKey(), (Integer) entry.getValue());
                        }
                    }
                    reply.putExtras(bundle);
                    ctx.sendBroadcast(reply);

                } else if ("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG".equals(action)) {
                    ModuleLog.line("(InstaLy) Sync: Companion app requested Dev Config export.");
                    try {
                        java.io.File source = new java.io.File(ctx.getFilesDir(), "mobileconfig/mc_overrides.json");
                        if (!source.exists()) {
                            ModuleLog.line("(InstaLy) Export: mc_overrides.json not found.");
                            Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                            reply.setPackage("ps.reso.instaeclipse");
                            reply.putExtra("error", "mc_overrides.json not found.");
                            ctx.sendBroadcast(reply);
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(source))) {
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                        }
                        Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                        reply.setPackage("ps.reso.instaeclipse");
                        reply.putExtra("json_content", sb.toString().trim());
                        ctx.sendBroadcast(reply);
                        ModuleLog.line("(InstaLy) Export: config reply sent to companion.");
                    } catch (Exception e) {
                        ModuleLog.line("(InstaLy) Export: failed: " + e.getMessage());
                    }

                } else if ("ps.reso.instaeclipse.ACTION_BACKUP_SETTINGS".equals(action)) {
                    ModuleLog.line("(InstaLy) Sync: Companion app requested Settings backup.");
                    try {
                        String json = ps.reso.instaeclipse.utils.backup.SettingsBackupManager.toJson();
                        Intent exportIntent = new Intent();
                        exportIntent.setComponent(new android.content.ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                        exportIntent.putExtra("json_content", json);
                        exportIntent.putExtra("file_name", "instaeclipse_settings.json");
                        exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(exportIntent);
                    } catch (Exception e) {
                        ModuleLog.line("(InstaLy) Failed to create backup: " + e.getMessage());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
        filter.addAction(CommonUtils.ACTION_REQUEST_LOGS);
        filter.addAction(CommonUtils.ACTION_CLEAR_LOGS);
        filter.addAction("ps.reso.instaeclipse.ACTION_REQUEST_PREFS");
        filter.addAction("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");
        filter.addAction("ps.reso.instaeclipse.ACTION_BACKUP_SETTINGS");

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        }
    }
}
