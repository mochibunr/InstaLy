package ps.reso.instaeclipse.mods.ui;

import static org.luckypray.dexkit.query.FindMethod.create;
import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.luckypray.dexkit.result.MethodData;

import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class UIHookManager {

    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;
    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    /**
     * Activities that already have a pending OnGlobalLayoutListener registered.
     * Used only to prevent duplicate listener registrations when the search view
     * is not yet visible at setup time.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sGlobalListenerPending =
            new java.util.WeakHashMap<>();
    /**
     * Tracks whether the GlobalLayoutListener path has completed for an activity,
     * so we don't keep registering new listeners on every resume after search is found.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sSearchWiringDone =
            new java.util.WeakHashMap<>();

    // Resource IDs are constant for a given IG install — cache them statically.
    private static volatile int sSearchTabId = 0;
    private static volatile int sActionBarEndId = 0;
    private static volatile int sInboxButtonId = 0;
    private static volatile int sDirectTabId = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIdsCached(Activity activity) {
        if (sSearchTabId != 0 && sActionBarEndId != 0
                && sInboxButtonId != 0 && sDirectTabId != 0) return;
        String pkg = activity.getPackageName();
        android.content.res.Resources res = activity.getResources();
        if (sSearchTabId == 0)
            sSearchTabId = res.getIdentifier("search_tab", "id", pkg);
        if (sActionBarEndId == 0)
            sActionBarEndId = res.getIdentifier("action_bar_end_action_buttons", "id", pkg);
        if (sInboxButtonId == 0)
            sInboxButtonId = res.getIdentifier("action_bar_inbox_button", "id", pkg);
        if (sDirectTabId == 0)
            sDirectTabId = res.getIdentifier("direct_tab", "id", pkg);
    }

    /**
     * Version-resilient lifecycle fallback.
     *
     * The primary path uses DexKit to resolve InstagramMainActivity lifecycle
     * methods. On a new Instagram release that discovery can temporarily fail
     * before feature-specific signatures are updated. Hooking framework
     * Activity.onResume gives us a stable fallback that still wires the
     * InstaLy menu and view-level features without knowing Instagram's
     * obfuscated method names.
     */
    private static volatile boolean sActivityFallbackInstalled = false;

    public static void installActivityFallback() {
        if (sActivityFallbackInstalled) return;

        synchronized (UIHookManager.class) {
            if (sActivityFallbackInstalled) return;

            try {
                XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity activity = (Activity) param.thisObject;

                        try {
                            if (!ps.reso.instaeclipse.utils.core.CommonUtils.SUPPORTED_PACKAGES
                                    .contains(activity.getPackageName())) {
                                return;
                            }
                            if (!isLikelyInstagramMainActivity(activity)) return;

                            currentActivity = activity;
                            activity.runOnUiThread(() -> {
                                try {
                                    setupHooks(activity);
                                } catch (Throwable t) {
                                    ModuleLog.line("(InstaLy | UI fallback): setup failed", t);
                                }
                            });
                        } catch (Throwable t) {
                            ModuleLog.line("(InstaLy | UI fallback): lifecycle callback failed", t);
                        }
                    }
                });
                sActivityFallbackInstalled = true;
                ModuleLog.line("(InstaLy | UI): installed Activity.onResume fallback");
            } catch (Throwable t) {
                ModuleLog.line("(InstaLy | UI): could not install lifecycle fallback", t);
            }
        }
    }

    private static boolean isLikelyInstagramMainActivity(Activity activity) {
        String className = activity.getClass().getName();
        if (INSTAGRAM_MAIN_ACTIVITY.equals(className)
                || className.endsWith(".InstagramMainActivity")) {
            return true;
        }

        // Resource-name fallback survives method/class obfuscation changes.
        ensureIdsCached(activity);
        try {
            if (sSearchTabId != 0 && activity.findViewById(sSearchTabId) != null) return true;
            return sActionBarEndId != 0 && activity.findViewById(sActionBarEndId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setupHooks(Activity activity) {
        // Ghost emoji visibility must update on every resume (reflects current ghost state).
        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Auto-clear IG cache if it has grown past the configured size (throttled internally).
        ps.reso.instaeclipse.utils.core.CacheAutoClear.maybeClear(activity);

        // Lock DMs: arm the inbox passcode watcher on the main activity (its onCreate is
        // obfuscated, so a direct hook fails — this runs from the module's resolved main hook).
        ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.watchActivity(activity);

        // Remove Meta AI: collapse the "About this reel" Content Deep Dive section (AI summary +
        // "Ask Meta AI…" composer) inside the reel/post action sheet. It is a Litho-rendered
        // section not reachable via the option/builder hooks, so we strip it at the view level.
        if (FeatureFlags.removeMetaAI) {
            ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook.watchActionSheet(activity);
        }

        // Distraction Free: collapse the story tray / Reels tab at the view level (the network drop
        // alone leaves IG's cached stories/reels visible on IG 447.0.0.39+).
        if (FeatureFlags.disableStories || FeatureFlags.disableReels
                || FeatureFlags.disableExplore || FeatureFlags.disableFeed) {
            ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.watchActivity(activity);
        }

        // Cache resource IDs once per IG install (string table lookup is non-trivial).
        ensureIdsCached(activity);

        // Floating iOS bottom navigation is fully lazy. When disabled this is a cheap no-op;
        // when enabled it reuses Instagram's native tabs and owns only their presentation.
        FloatingIosBottomNavHook.refresh(activity);

        // Always re-apply the search long-press listener. Instagram may overwrite it after
        // a config change (e.g. when InstaLy settings are toggled), so we cannot skip
        // this on resume — we just avoid the expensive getIdentifier() call via the cache.
        boolean anySearchFound = false;
        if (sSearchTabId != 0) {
            View v = activity.findViewById(sSearchTabId);
            if (v != null) { processSearchView(activity, v, "search_tab"); anySearchFound = true; }
        }
        if (!anySearchFound && sActionBarEndId != 0) {
            View v = activity.findViewById(sActionBarEndId);
            if (v != null) { processSearchView(activity, v, "action_bar_end_action_buttons"); anySearchFound = true; }
        }

        // Register at most ONE GlobalLayoutListener per activity to retry search wiring
        // when the view isn't inflated yet. Skip if we already found search, if a listener
        // is already pending, or if the listener already completed successfully.
        if (!anySearchFound
                && !Boolean.TRUE.equals(sGlobalListenerPending.get(activity))
                && !Boolean.TRUE.equals(sSearchWiringDone.get(activity))) {
            sGlobalListenerPending.put(activity, true);
            final View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    boolean found = false;
                    if (sSearchTabId != 0) {
                        View lateView = activity.findViewById(sSearchTabId);
                        if (lateView != null) { processSearchView(activity, lateView, "search_tab"); found = true; }
                    }
                    if (!found && sActionBarEndId != 0) {
                        View lateView = activity.findViewById(sActionBarEndId);
                        if (lateView != null) { processSearchView(activity, lateView, "action_bar_end_action_buttons"); found = true; }
                    }
                    if (found) {
                        decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        sGlobalListenerPending.remove(activity);
                        sSearchWiringDone.put(activity, true);
                    }
                }
            });
        }

    }

    public void mainActivity(ClassLoader classLoader) {
        // Always install the stable framework fallback before attempting
        // Instagram-specific DexKit lifecycle discovery.
        installActivityFallback();

        if (Module.dexKitBridge == null) {
            ModuleLog.line("(InstaLy | UI): DexKit bridge unavailable; fallback remains active");
            return;
        }

        // Activity.onResume fallback above already owns main-activity lifecycle setup.
        // Do not install a second DexKit-discovered onCreate/onResume pair: it duplicated
        // setupHooks() work on every resume and caused avoidable UI jank.

        // Hook getBottomSheetNavigator - Instagram Main
        BottomSheetHookUtil.hookBottomSheetNavigator(Module.dexKitBridge);

        // Hook View.performLongClick — inbox long-press override.
        // setOnLongClickListener is unreliable when Instagram has a parent-level touch
        // interceptor or a custom view that overrides long-press dispatch. Hooking
        // performLongClick() fires BEFORE any listener/interceptor chain and lets us
        // fully own the event by returning true via setResult.
        // This only fires when the user actually long-presses something — not a hot path.
        XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (sInboxButtonId == 0 && sDirectTabId == 0) return;
                View view = (View) param.thisObject;
                int id = view.getId();
                if (id != sInboxButtonId && id != sDirectTabId) return;
                Activity activity = currentActivity;
                if (activity == null) return;
                GhostModeUtils.toggleSelectedGhostOptions(activity);
                VibrationUtil.vibrate(activity);
                param.setResult(true); // consume — skip Instagram's handler entirely
            }
        });

        // Hook onResume - Model
        XposedHelpers.findAndHookMethod("com.instagram.modal.ModalActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity);
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }
        });
    }

    private static void applySearchHook(Activity activity, View v) {
        v.setOnLongClickListener(view -> {
            DialogUtils.showEclipseOptionsDialog(activity);
            VibrationUtil.vibrate(activity);
            return true;
        });
    }

    private static void processSearchView(Activity activity, View view, String id) {
        if (id.equals("action_bar_end_action_buttons") && view instanceof ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                CharSequence description = child.getContentDescription();
                if (description != null && description.toString().toLowerCase().contains("search")) {
                    applySearchHook(activity, child);
                }
            }
        } else {
            applySearchHook(activity, view);
        }
    }

    /** Registers a broadcast receiver in the Instagram process to handle config imports. */
    public static void registerConfigImportReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                if (json != null && !json.isEmpty()) {
                    ConfigManager.importConfigFromJson(ctx, json);
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"),
                    android.content.Context.RECEIVER_EXPORTED);
        } else {
            androidx.core.content.ContextCompat.registerReceiver(context,
                    receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"),
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
        }
    }

    /** Registers a receiver in the Instagram process to restore settings from a backup JSON. */
    public static void registerSettingsRestoreReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                if (json == null || json.isEmpty()) return;
                new Thread(() -> {
                    try {
                        ps.reso.instaeclipse.utils.backup.SettingsBackupManager.fromJson(json);
                        SettingsManager.saveAllFlags();
                        ps.reso.instaeclipse.utils.feature.FeatureManager.refreshFeatureStatus();
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(),
                                "✅ " + I18n.t(ctx, R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show());
                    } catch (Throwable e) {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(),
                                "❌ " + I18n.t(ctx, R.string.ig_toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        };
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver,
                        new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"),
                        android.content.Context.RECEIVER_EXPORTED);
            } else {
                androidx.core.content.ContextCompat.registerReceiver(context,
                        receiver,
                        new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"),
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
            }
            } catch (Throwable e) {
            ModuleLog.line("(InstaLy | RestoreReceiver): ❌ " + e.getMessage());
        }
    }

}
