package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.Checkable;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Instagram-native floating bottom navigation.
 *
 * Architecture intentionally follows the stable part of WaEnhancer's FloatingBottomBar design:
 * keep the target application's real navigation hierarchy and move/style only presentation.
 *
 * Instagram is NOT laid out like WhatsApp. Public/decompiled Instagram layouts show that tab_bar
 * is already a bottom-gravity child of layout_container_main_wrapper, next to:
 *   - layout_container_main / swipeable_tab_view_pager, whose bottom margin reserves tabBarHeight
 *   - tab_bar_shadow, a separate sibling
 *
 * Therefore InstaLy never reparents tab_bar. It clears only Instagram's real reserved content
 * margins, hides the real shadow, and inserts glass as a sibling behind the native tab bar.
 *
 * Liquid-glass refraction math is adapted from Kyant0/AndroidLiquidGlass (Backdrop), Copyright
 * 2025 Kyant, Apache License 2.0. See THIRD_PARTY_NOTICES.md.
 */
public final class FloatingIosBottomNavHook {

    private static final String FEATURE_KEY = "FloatingIosBottomNav";
    private static final long CAPTURE_INTERVAL_MS = 40L;
    private static final long SELECTION_INTERVAL_MS = 70L;
    private static final int MAX_APPLY_ATTEMPTS = 8;
    private static final int SIDE_MARGIN_DP = 12;
    private static final int BOTTOM_MARGIN_DP = 16;

    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Integer> RETRIES = new WeakHashMap<>();

    private static boolean lifecycleHooksInstalled;
    private static int sTabBarId;

    private FloatingIosBottomNavHook() {}

    public static void refresh(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (FeatureFlags.floatingIosBottomNavbar) {
                resolveTabBarId(activity);
                ensureLifecycleHooksInstalled();
                ensureApplied(activity, 0);
            } else {
                restore(activity);
            }
        });
    }

    private static void resolveTabBarId(Activity activity) {
        if (sTabBarId != 0) return;
        sTabBarId = resourceId(activity, "tab_bar");
    }

    private static synchronized void ensureLifecycleHooksInstalled() {
        if (lifecycleHooksInstalled) return;
        lifecycleHooksInstalled = true;

        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.floatingIosBottomNavbar || sTabBarId == 0) return;
                            View view = (View) param.thisObject;
                            if (view.getId() != sTabBarId || !(view instanceof ViewGroup)) return;

                            Activity activity = findActivity(view.getContext());
                            if (activity == null || activity.isFinishing()) return;
                            view.post(() -> ensureApplied(activity, 0));
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    View.class,
                    "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.floatingIosBottomNavbar || sTabBarId == 0) return;
                            View view = (View) param.thisObject;
                            if (view.getId() != sTabBarId) return;

                            Activity activity = findActivity(view.getContext());
                            if (activity == null || activity.isFinishing()) return;
                            View decor = activity.getWindow() != null
                                    ? activity.getWindow().getDecorView()
                                    : null;
                            if (decor != null) {
                                decor.postDelayed(() -> ensureApplied(activity, 0), 90L);
                            }
                        }
                    }
            );

            // Do not replace Instagram's OnClickListener/OnTouchListener. Observe the framework's
            // real performClick() after it runs, then move only our visual liquid selector.
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.floatingIosBottomNavbar || sTabBarId == 0) return;

                            View clicked = (View) param.thisObject;
                            View directTab = findDirectTabChild(clicked);
                            if (directTab == null || !(directTab.getParent() instanceof ViewGroup)) {
                                return;
                            }

                            ViewGroup bar = (ViewGroup) directTab.getParent();
                            if (bar.getId() != sTabBarId) return;

                            Activity activity = findActivity(clicked.getContext());
                            if (activity == null || activity.isFinishing()) return;

                            State state = STATES.get(activity);
                            if (state == null || state.bar != bar || !state.isValid(activity)) {
                                clicked.post(() -> ensureApplied(activity, 0));
                                return;
                            }

                            state.layer.onNativeTabClicked(directTab);
                        }
                    }
            );
        } catch (Throwable t) {
            lifecycleHooksInstalled = false;
            ModuleLog.line("(InstaLy | FloatingNav): lifecycle hook install failed", t);
        }
    }

    private static void ensureApplied(Activity activity, int attempt) {
        if (!FeatureFlags.floatingIosBottomNavbar || activity.isFinishing()) return;

        State existing = STATES.get(activity);
        if (existing != null) {
            if (existing.isValid(activity)) {
                existing.enforcePresentation();
                existing.layer.requestCapture();
                existing.layer.syncSelection(false);
                return;
            }
            removeState(activity, existing, true);
        }

        int tabBarId = resourceId(activity, "tab_bar");
        int wrapperId = resourceId(activity, "layout_container_main_wrapper");
        int panelId = resourceId(activity, "layout_container_main_panel");
        int shadowId = resourceId(activity, "tab_bar_shadow");

        View rawBar = tabBarId != 0 ? activity.findViewById(tabBarId) : null;
        if (!(rawBar instanceof ViewGroup)
                || rawBar.getParent() == null
                || rawBar.getWidth() <= 0
                || rawBar.getHeight() <= 0) {
            retry(activity, attempt, "exact #tab_bar is not ready");
            return;
        }

        ViewGroup bar = (ViewGroup) rawBar;
        if (!(bar.getParent() instanceof FrameLayout)) {
            retry(activity, attempt,
                    "#tab_bar parent is not FrameLayout: " + describeParent(bar));
            return;
        }

        FrameLayout host = (FrameLayout) bar.getParent();
        int hostId = host.getId();
        boolean knownInstagramHost = hostId == wrapperId || hostId == panelId;
        if (!knownInstagramHost) {
            retry(activity, attempt,
                    "#tab_bar parent is unexpected: " + describeView(activity, host));
            return;
        }

        if (!(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            retry(activity, attempt, "#tab_bar does not use FrameLayout.LayoutParams");
            return;
        }

        List<MarginSnapshot> reservedContent = findReservedContentViews(activity);
        if (reservedContent.isEmpty()) {
            retry(activity, attempt,
                    "Instagram content hosts (#layout_container_main / #swipeable_tab_view_pager) not found");
            return;
        }

        final FrameLayout.LayoutParams originalBarLayoutParams =
                new FrameLayout.LayoutParams((FrameLayout.LayoutParams) bar.getLayoutParams());
        final Drawable originalBarBackground = bar.getBackground();
        final float originalBarElevation = bar.getElevation();
        final float originalBarTranslationZ = bar.getTranslationZ();
        final boolean originalClipChildren = host.getClipChildren();
        final boolean originalClipToPadding = host.getClipToPadding();

        View shadow = shadowId != 0 ? activity.findViewById(shadowId) : null;
        int originalShadowVisibility = shadow != null ? shadow.getVisibility() : View.VISIBLE;

        // This is the actual strip reservation in Instagram: the content hosts end exactly where
        // tab_bar begins. Expand them to the bottom of their native wrapper while leaving tab_bar
        // itself owned by Instagram.
        StringBuilder marginLog = new StringBuilder();
        for (MarginSnapshot snapshot : reservedContent) {
            if (marginLog.length() > 0) marginLog.append(", ");
            marginLog.append(snapshot.name)
                    .append(":")
                    .append(snapshot.originalBottomMargin)
                    .append("->0");
            snapshot.applyZeroBottomMargin();
        }

        if (shadow != null) shadow.setVisibility(View.GONE);

        host.setClipChildren(false);
        host.setClipToPadding(false);

        int sideMargin = dp(activity, SIDE_MARGIN_DP);
        int bottomMargin = dp(activity, BOTTOM_MARGIN_DP);

        // Instagram's wrapper lives inside a fitsSystemWindows root. Do NOT add the system nav
        // inset again; doing so double-counts the gesture/navigation region on this layout.
        FrameLayout.LayoutParams floatingBarLp =
                new FrameLayout.LayoutParams(originalBarLayoutParams);
        floatingBarLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        floatingBarLp.gravity = Gravity.BOTTOM;
        floatingBarLp.leftMargin = sideMargin;
        floatingBarLp.rightMargin = sideMargin;
        floatingBarLp.bottomMargin = bottomMargin;
        bar.setLayoutParams(floatingBarLp);
        bar.setBackground(null);

        int barIndex = host.indexOfChild(bar);
        if (barIndex < 0) {
            restoreReservedContent(reservedContent);
            if (shadow != null) shadow.setVisibility(originalShadowVisibility);
            retry(activity, attempt, "#tab_bar index unavailable");
            return;
        }

        GlassLayer layer = new GlassLayer(activity, host, bar, shadow);
        FrameLayout.LayoutParams layerLp = new FrameLayout.LayoutParams(floatingBarLp);
        layerLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layerLp.height = bar.getHeight();
        host.addView(layer, barIndex, layerLp);

        View dragHandle = new View(activity);
        dragHandle.setBackgroundColor(Color.TRANSPARENT);
        dragHandle.setClickable(true);
        dragHandle.setFocusable(false);
        dragHandle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dragHandle.setElevation(Math.max(bar.getElevation(), dp(activity, 4)) + dp(activity, 4));
        host.addView(dragHandle, new FrameLayout.LayoutParams(1, 1));
        dragHandle.bringToFront();

        layer.attachDragHandle(dragHandle);

        State state = new State(
                activity,
                bar,
                host,
                layer,
                dragHandle,
                originalBarLayoutParams,
                originalBarBackground,
                originalBarElevation,
                originalBarTranslationZ,
                originalClipChildren,
                originalClipToPadding,
                shadow,
                originalShadowVisibility,
                reservedContent,
                sideMargin,
                bottomMargin
        );
        layer.setPresentationEnforcer(state::enforcePresentation);
        STATES.put(activity, state);
        RETRIES.remove(activity);

        host.requestLayout();
        for (MarginSnapshot snapshot : reservedContent) {
            snapshot.view.requestLayout();
        }

        FeatureStatusTracker.setHooked(FEATURE_KEY);
        ModuleLog.line("(InstaLy | FloatingNav): native in-place applied"
                + " bar=" + describeView(activity, bar)
                + " parent=" + describeView(activity, host)
                + " reserved=[" + marginLog + "]"
                + " shadow=" + (shadow != null ? "hidden" : "missing")
                + " sideMargin=" + sideMargin + "px"
                + " bottomMargin=" + bottomMargin + "px"
                + " barReparented=false");

        layer.post(layer::requestCapture);
    }

    private static void retry(Activity activity, int attempt, String reason) {
        if (attempt >= MAX_APPLY_ATTEMPTS) {
            RETRIES.remove(activity);
            ModuleLog.line("(InstaLy | FloatingNav): exact Instagram layout unavailable (" + reason + ")");
            FeatureStatusTracker.setBroken(FEATURE_KEY);
            return;
        }

        Integer scheduled = RETRIES.get(activity);
        if (scheduled != null && scheduled >= attempt + 1) return;
        RETRIES.put(activity, attempt + 1);

        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) return;
        decor.postDelayed(() -> {
            RETRIES.remove(activity);
            ensureApplied(activity, attempt + 1);
        }, 100L + attempt * 80L);
    }

    public static void restore(Activity activity) {
        if (activity == null) return;
        State state = STATES.remove(activity);
        RETRIES.remove(activity);
        if (state == null) return;
        removeState(activity, state, true);
        ModuleLog.line("(InstaLy | FloatingNav): restored native Instagram layout");
    }

    private static void removeState(Activity activity, State state, boolean restoreNativeLayout) {
        try {
            state.layer.dispose();

            if (state.dragHandle.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.dragHandle.getParent()).removeView(state.dragHandle);
            }
            if (state.layer.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.layer.getParent()).removeView(state.layer);
            }

            if (restoreNativeLayout) {
                state.bar.setLayoutParams(state.originalBarLayoutParams);
                state.bar.setBackground(state.originalBarBackground);
                state.bar.setElevation(state.originalBarElevation);
                state.bar.setTranslationZ(state.originalBarTranslationZ);

                restoreReservedContent(state.reservedContent);

                if (state.shadow != null) {
                    state.shadow.setVisibility(state.originalShadowVisibility);
                }

                state.host.setClipChildren(state.originalClipChildren);
                state.host.setClipToPadding(state.originalClipToPadding);
                state.host.requestLayout();
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaLy | FloatingNav): cleanup failed", t);
        } finally {
            STATES.remove(activity);
        }
    }

    private static List<MarginSnapshot> findReservedContentViews(Activity activity) {
        List<MarginSnapshot> result = new ArrayList<>();

        // These are exact Instagram resource IDs visible in the current main-activity hierarchy.
        // Both can reserve tabBarHeight depending on the active navigation experiment.
        String[] names = {
                "layout_container_main",
                "swipeable_tab_view_pager"
        };

        for (String name : names) {
            int id = resourceId(activity, name);
            if (id == 0) continue;
            View view = activity.findViewById(id);
            if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                continue;
            }

            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            result.add(new MarginSnapshot(view, name, lp.bottomMargin));
        }

        return result;
    }

    private static void restoreReservedContent(List<MarginSnapshot> snapshots) {
        for (MarginSnapshot snapshot : snapshots) {
            snapshot.restore();
        }
    }

    @SuppressWarnings("DiscouragedApi")
    private static int resourceId(Context context, String name) {
        try {
            return context.getResources().getIdentifier(name, "id", context.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Activity findActivity(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return null;
    }

    private static String describeParent(View view) {
        Object parent = view != null ? view.getParent() : null;
        if (parent instanceof View) {
            return parent.getClass().getName();
        }
        return String.valueOf(parent);
    }

    private static String describeView(Context context, View view) {
        if (view == null) return "null";
        StringBuilder out = new StringBuilder(view.getClass().getName());
        try {
            int id = view.getId();
            if (id != View.NO_ID && id != 0) {
                out.append("#").append(context.getResources().getResourceEntryName(id));
            }
        } catch (Throwable ignored) {}
        out.append("(").append(view.getWidth()).append("x").append(view.getHeight()).append(")");
        return out.toString();
    }

    private static int dp(Context context, float value) {
        return Math.max(1, Math.round(
                value * context.getResources().getDisplayMetrics().density
        ));
    }

    private static boolean isLightTheme(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_YES;
    }

    private static boolean hasSelectedState(View view) {
        if (view == null) return false;
        if (view.isSelected() || view.isActivated()) return true;
        if (view instanceof Checkable && ((Checkable) view).isChecked()) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasSelectedState(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static List<View> visibleNativeTabs(ViewGroup bar) {
        List<View> tabs = new ArrayList<>();
        if (bar == null) return tabs;

        // Instagram's actual #tab_bar is a horizontal LinearLayout whose direct children are
        // feed_tab / clips_tab / direct_tab / search_tab / profile_tab. Preserve that contract
        // rather than recursively guessing a nested group.
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE
                    && child.getWidth() > 0
                    && child.getHeight() > 0) {
                tabs.add(child);
            }
        }
        return tabs;
    }

    private static View findDirectTabChild(View clicked) {
        View current = clicked;
        int depth = 0;
        while (current != null && depth < 8) {
            Object parent = current.getParent();
            if (parent instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) parent;
                if (group.getId() == sTabBarId) {
                    return current;
                }
                current = group;
            } else {
                return null;
            }
            depth++;
        }
        return null;
    }

    private static int selectedNativeTabIndex(ViewGroup bar) {
        List<View> tabs = visibleNativeTabs(bar);
        for (int i = 0; i < tabs.size(); i++) {
            if (hasSelectedState(tabs.get(i))) return i;
        }
        return -1;
    }

    private static final class MarginSnapshot {
        final View view;
        final String name;
        final int originalBottomMargin;

        MarginSnapshot(View view, String name, int originalBottomMargin) {
            this.view = view;
            this.name = name;
            this.originalBottomMargin = originalBottomMargin;
        }

        void applyZeroBottomMargin() {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            lp.bottomMargin = 0;
            view.setLayoutParams(lp);
        }

        boolean ensureZeroBottomMargin() {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return false;
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (lp.bottomMargin == 0) return false;
            lp.bottomMargin = 0;
            view.setLayoutParams(lp);
            view.requestLayout();
            return true;
        }

        void restore() {
            if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            lp.bottomMargin = originalBottomMargin;
            view.setLayoutParams(lp);
            view.requestLayout();
        }
    }

    private static final class State {
        final Activity activity;
        final ViewGroup bar;
        final FrameLayout host;
        final GlassLayer layer;
        final View dragHandle;
        final FrameLayout.LayoutParams originalBarLayoutParams;
        final Drawable originalBarBackground;
        final float originalBarElevation;
        final float originalBarTranslationZ;
        final boolean originalClipChildren;
        final boolean originalClipToPadding;
        final View shadow;
        final int originalShadowVisibility;
        final List<MarginSnapshot> reservedContent;
        final int sideMargin;
        final int bottomMargin;

        private boolean rebindScheduled;
        private long lastRepairLogAt;

        State(
                Activity activity,
                ViewGroup bar,
                FrameLayout host,
                GlassLayer layer,
                View dragHandle,
                FrameLayout.LayoutParams originalBarLayoutParams,
                Drawable originalBarBackground,
                float originalBarElevation,
                float originalBarTranslationZ,
                boolean originalClipChildren,
                boolean originalClipToPadding,
                View shadow,
                int originalShadowVisibility,
                List<MarginSnapshot> reservedContent,
                int sideMargin,
                int bottomMargin
        ) {
            this.activity = activity;
            this.bar = bar;
            this.host = host;
            this.layer = layer;
            this.dragHandle = dragHandle;
            this.originalBarLayoutParams = originalBarLayoutParams;
            this.originalBarBackground = originalBarBackground;
            this.originalBarElevation = originalBarElevation;
            this.originalBarTranslationZ = originalBarTranslationZ;
            this.originalClipChildren = originalClipChildren;
            this.originalClipToPadding = originalClipToPadding;
            this.shadow = shadow;
            this.originalShadowVisibility = originalShadowVisibility;
            this.reservedContent = reservedContent;
            this.sideMargin = sideMargin;
            this.bottomMargin = bottomMargin;
        }

        boolean isValid(Activity owner) {
            if (!bar.isAttachedToWindow()
                    || bar.getParent() != host
                    || layer.getParent() != host
                    || dragHandle.getParent() != host) {
                return false;
            }

            View current = sTabBarId != 0 ? owner.findViewById(sTabBarId) : null;
            return current == bar;
        }

        void enforcePresentation() {
            if (!FeatureFlags.floatingIosBottomNavbar || activity.isFinishing()) return;

            View current = sTabBarId != 0 ? activity.findViewById(sTabBarId) : null;
            if (current != bar) {
                scheduleRebind();
                return;
            }

            boolean repaired = false;

            if (bar.getParent() != host) {
                scheduleRebind();
                return;
            }

            if (bar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) bar.getLayoutParams();

                if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.gravity != Gravity.BOTTOM
                        || lp.leftMargin != sideMargin
                        || lp.rightMargin != sideMargin
                        || lp.bottomMargin != bottomMargin) {
                    FrameLayout.LayoutParams fixed = new FrameLayout.LayoutParams(lp);
                    fixed.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    fixed.gravity = Gravity.BOTTOM;
                    fixed.leftMargin = sideMargin;
                    fixed.rightMargin = sideMargin;
                    fixed.bottomMargin = bottomMargin;
                    bar.setLayoutParams(fixed);
                    repaired = true;
                }
            } else {
                scheduleRebind();
                return;
            }

            if (bar.getBackground() != null) {
                bar.setBackground(null);
                repaired = true;
            }

            if (shadow != null && shadow.getVisibility() != View.GONE) {
                shadow.setVisibility(View.GONE);
                repaired = true;
            }

            if (host.getClipChildren()) {
                host.setClipChildren(false);
                repaired = true;
            }
            if (host.getClipToPadding()) {
                host.setClipToPadding(false);
                repaired = true;
            }

            // Instagram can replace the pager/content host while navigating. Track exact
            // replacement instances before re-zeroing margins, so a freshly inflated screen
            // cannot bring the stock bottom reservation back.
            refreshReservedContentViews();
            for (MarginSnapshot snapshot : reservedContent) {
                repaired |= snapshot.ensureZeroBottomMargin();
            }

            if (layer.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) layer.getLayoutParams();
                int desiredHeight = Math.max(1, bar.getHeight());
                if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.height != desiredHeight
                        || lp.gravity != Gravity.BOTTOM
                        || lp.leftMargin != sideMargin
                        || lp.rightMargin != sideMargin
                        || lp.bottomMargin != bottomMargin) {
                    FrameLayout.LayoutParams fixed = new FrameLayout.LayoutParams(lp);
                    fixed.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    fixed.height = desiredHeight;
                    fixed.gravity = Gravity.BOTTOM;
                    fixed.leftMargin = sideMargin;
                    fixed.rightMargin = sideMargin;
                    fixed.bottomMargin = bottomMargin;
                    layer.setLayoutParams(fixed);
                    repaired = true;
                }
            }

            if (repaired) {
                host.requestLayout();
                long now = SystemClock.uptimeMillis();
                if (now - lastRepairLogAt > 750L) {
                    lastRepairLogAt = now;
                    ModuleLog.line(
                            "(InstaLy | FloatingNav): repaired presentation after Instagram UI refresh"
                    );
                }
            }
        }

        private void refreshReservedContentViews() {
            List<MarginSnapshot> current = findReservedContentViews(activity);
            for (MarginSnapshot candidate : current) {
                boolean known = false;
                for (MarginSnapshot existing : reservedContent) {
                    if (existing.view == candidate.view) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    reservedContent.add(candidate);
                    ModuleLog.line(
                            "(InstaLy | FloatingNav): tracking replacement "
                                    + candidate.name
                                    + " bottomMargin="
                                    + candidate.originalBottomMargin
                    );
                }
            }
        }

        private void scheduleRebind() {
            if (rebindScheduled) return;
            rebindScheduled = true;
            View decor = activity.getWindow() != null
                    ? activity.getWindow().getDecorView()
                    : null;
            if (decor == null) {
                rebindScheduled = false;
                return;
            }
            decor.post(() -> {
                rebindScheduled = false;
                ensureApplied(activity, 0);
            });
        }
    }

    private static final class GlassLayer extends FrameLayout {
        private final FrameLayout captureRoot;
        private final ViewGroup nativeBar;
        private final View nativeShadow;

        private final BackdropView backdropView;
        private final View surfaceTint;
        private final BackdropView selectionLens;
        private final GradientDrawable lensSurfaceDrawable;

        private final ViewTreeObserver.OnPreDrawListener preDrawListener;

        private Runnable presentationEnforcer;
        private View dragHandle;
        private Bitmap snapshot;
        private long lastCaptureAt;
        private long lastSelectionAt;
        private boolean listenerAttached;

        private int visualSelectedTabIndex = -1;
        private boolean draggingLens;
        private float dragStartRawX;
        private float dragStartLensX;
        private float dragDownRawX;
        private long lensSettleUntil;
        private VelocityTracker velocityTracker;

        GlassLayer(Context context, FrameLayout captureRoot, ViewGroup nativeBar, View nativeShadow) {
            super(context);
            this.captureRoot = captureRoot;
            this.nativeBar = nativeBar;
            this.nativeShadow = nativeShadow;

            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            setClickable(false);
            setFocusable(false);

            float radius = dp(context, 30);

            backdropView = new BackdropView(context, false);
            backdropView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(
                            0, 0, view.getWidth(), view.getHeight(),
                            Math.min(radius, view.getHeight() / 2f)
                    );
                }
            });
            backdropView.setClipToOutline(true);
            addView(backdropView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            surfaceTint = new View(context);
            GradientDrawable surface = new GradientDrawable();
            surface.setShape(GradientDrawable.RECTANGLE);
            surface.setCornerRadius(radius);
            surface.setColor(isLightTheme(context) ? 0x42F8F8F8 : 0x42121212);
            surfaceTint.setBackground(surface);
            addView(surfaceTint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            selectionLens = new BackdropView(context, true);
            selectionLens.setVisibility(View.INVISIBLE);
            selectionLens.setAlpha(0f);
            selectionLens.setElevation(0f);
            selectionLens.setTranslationZ(0f);
            selectionLens.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(
                            0, 0, view.getWidth(), view.getHeight(),
                            view.getHeight() / 2f
                    );
                }
            });
            selectionLens.setClipToOutline(true);

            lensSurfaceDrawable = new GradientDrawable();
            lensSurfaceDrawable.setCornerRadius(dp(context, 32));
            lensSurfaceDrawable.setColor(
                    isLightTheme(context) ? 0x1C000000 : 0x34FFFFFF
            );
            lensSurfaceDrawable.setStroke(
                    dp(context, 1),
                    isLightTheme(context) ? 0x3D000000 : 0x70FFFFFF
            );
            selectionLens.setForeground(lensSurfaceDrawable);
            addView(selectionLens, new FrameLayout.LayoutParams(1, 1));

            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.TRANSPARENT);
            border.setCornerRadius(radius);
            border.setStroke(
                    dp(context, 1),
                    isLightTheme(context) ? 0x30000000 : 0x55FFFFFF
            );
            setForeground(border);

            preDrawListener = () -> {
                if (presentationEnforcer != null) {
                    presentationEnforcer.run();
                }

                boolean nativeVisible = nativeBar.getVisibility() == View.VISIBLE
                        && nativeBar.getAlpha() > 0.01f;
                int wantedVisibility = nativeVisible ? View.VISIBLE : View.INVISIBLE;
                if (getVisibility() != wantedVisibility) {
                    setVisibility(wantedVisibility);
                }
                if (dragHandle != null && dragHandle.getVisibility() != wantedVisibility) {
                    dragHandle.setVisibility(wantedVisibility);
                }

                long now = SystemClock.uptimeMillis();
                if (nativeVisible
                        && now - lastCaptureAt >= CAPTURE_INTERVAL_MS) {
                    lastCaptureAt = now;
                    captureBackdrop();
                }

                if (nativeVisible
                        && !draggingLens
                        && now - lastSelectionAt >= SELECTION_INTERVAL_MS) {
                    lastSelectionAt = now;
                    syncSelection(true);
                }
                return true;
            };
        }

        void setPresentationEnforcer(Runnable enforcer) {
            this.presentationEnforcer = enforcer;
        }

        void attachDragHandle(View handle) {
            this.dragHandle = handle;
            handle.setOnTouchListener((v, event) -> handleLensTouch(event));
            post(() -> {
                syncSelection(false);
                updateDragHandleFromLens();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attachListener();
        }

        @Override
        protected void onDetachedFromWindow() {
            detachListener();
            super.onDetachedFromWindow();
        }

        void dispose() {
            detachListener();
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            if (snapshot != null) {
                snapshot.recycle();
                snapshot = null;
            }
            backdropView.setSnapshot(null, 0);
            selectionLens.setSnapshot(null, 0);
        }

        void requestCapture() {
            post(() -> {
                captureBackdrop();
                syncSelection(false);
            });
        }

        void onNativeTabClicked(View directTab) {
            List<View> tabs = visibleNativeTabs(nativeBar);
            int index = tabs.indexOf(directTab);
            if (index < 0) return;

            boolean changed = visualSelectedTabIndex != index;
            visualSelectedTabIndex = index;
            moveLensToTab(directTab, true);

            if (changed) {
                ModuleLog.line("(InstaLy | FloatingNav): native tab -> " + index);
            }
        }

        void syncSelection(boolean animate) {
            List<View> tabs = visibleNativeTabs(nativeBar);
            if (tabs.size() < 3) return;

            int selected = selectedNativeTabIndex(nativeBar);
            if (selected >= 0 && selected < tabs.size()) {
                if (visualSelectedTabIndex != selected) {
                    visualSelectedTabIndex = selected;
                    moveLensToTab(tabs.get(selected), animate);
                } else if (selectionLens.getVisibility() != View.VISIBLE) {
                    moveLensToTab(tabs.get(selected), false);
                } else {
                    updateDragHandleFromLens();
                }
                return;
            }

            // Instagram can transiently clear selected flags while it swaps fragments. Never hide
            // or reset the lens in that interval. Keep the last native index until selection
            // becomes authoritative again.
            if (visualSelectedTabIndex >= 0 && visualSelectedTabIndex < tabs.size()) {
                if (selectionLens.getVisibility() != View.VISIBLE) {
                    moveLensToTab(tabs.get(visualSelectedTabIndex), false);
                }
                return;
            }

            // First frame only: choose Home if Instagram has not marked a selection yet.
            visualSelectedTabIndex = 0;
            moveLensToTab(tabs.get(0), false);
        }

        private void attachListener() {
            if (listenerAttached) return;
            ViewTreeObserver observer = captureRoot.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.addOnPreDrawListener(preDrawListener);
                listenerAttached = true;
            }
        }

        private void detachListener() {
            if (!listenerAttached) return;
            try {
                ViewTreeObserver observer = captureRoot.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(preDrawListener);
                }
            } catch (Throwable ignored) {}
            listenerAttached = false;
        }

        private void captureBackdrop() {
            int width = getWidth();
            int height = getHeight();
            if (width <= 1 || height <= 1 || !isAttachedToWindow()) return;

            try {
                if (snapshot == null
                        || snapshot.getWidth() != width
                        || snapshot.getHeight() != height) {
                    if (snapshot != null) snapshot.recycle();
                    snapshot = Bitmap.createBitmap(
                            width, height, Bitmap.Config.ARGB_8888
                    );
                } else {
                    snapshot.eraseColor(Color.TRANSPARENT);
                }

                Canvas canvas = new Canvas(snapshot);
                int[] layerLocation = new int[2];
                getLocationInWindow(layerLocation);

                Drawable rootBackground = captureRoot.getBackground();
                if (rootBackground != null) {
                    int[] rootLocation = new int[2];
                    captureRoot.getLocationInWindow(rootLocation);
                    int save = canvas.save();
                    canvas.translate(
                            rootLocation[0] - layerLocation[0],
                            rootLocation[1] - layerLocation[1]
                    );
                    rootBackground.draw(canvas);
                    canvas.restoreToCount(save);
                } else {
                    canvas.drawColor(
                            isLightTheme(getContext()) ? Color.WHITE : Color.BLACK
                    );
                }

                int[] childLocation = new int[2];
                for (int i = 0; i < captureRoot.getChildCount(); i++) {
                    View child = captureRoot.getChildAt(i);
                    if (child == this
                            || child == nativeBar
                            || child == dragHandle
                            || child == nativeShadow
                            || child.getVisibility() != View.VISIBLE
                            || child.getAlpha() <= 0f) {
                        continue;
                    }

                    child.getLocationInWindow(childLocation);
                    int save = canvas.save();
                    canvas.translate(
                            childLocation[0] - layerLocation[0],
                            childLocation[1] - layerLocation[1]
                    );
                    child.draw(canvas);
                    canvas.restoreToCount(save);
                }

                backdropView.setSnapshot(snapshot, 0);
                selectionLens.setSnapshot(
                        snapshot,
                        Math.round(selectionLens.getX())
                );
            } catch (Throwable t) {
                ModuleLog.line("(InstaLy | FloatingNav): backdrop capture failed", t);
            }
        }

        private void moveLensToTab(View tab, boolean animate) {
            if (tab == null || tab.getWidth() <= 0 || tab.getHeight() <= 0) return;

            int[] tabLocation = new int[2];
            int[] layerLocation = new int[2];
            tab.getLocationInWindow(tabLocation);
            getLocationInWindow(layerLocation);

            int tabLeft = tabLocation[0] - layerLocation[0];
            int tabTop = tabLocation[1] - layerLocation[1];

            int horizontalInset = Math.min(dp(getContext(), 6), tab.getWidth() / 8);
            int verticalInset = dp(getContext(), 4);
            int lensWidth = Math.max(
                    dp(getContext(), 48),
                    tab.getWidth() - horizontalInset * 2
            );
            int lensHeight = Math.max(
                    dp(getContext(), 42),
                    tab.getHeight() - verticalInset * 2
            );

            float targetX = tabLeft + horizontalInset;
            int targetTop = Math.max(0, tabTop + verticalInset);

            FrameLayout.LayoutParams lensLp =
                    (FrameLayout.LayoutParams) selectionLens.getLayoutParams();
            lensLp.width = lensWidth;
            lensLp.height = lensHeight;
            lensLp.leftMargin = 0;
            lensLp.topMargin = targetTop;
            selectionLens.setLayoutParams(lensLp);
            selectionLens.setSnapshot(snapshot, Math.round(targetX));
            selectionLens.setVisibility(View.VISIBLE);
            selectionLens.setAlpha(1f);

            selectionLens.animate().cancel();

            long now = SystemClock.uptimeMillis();
            if (animate && selectionLens.getWidth() > 0) {
                lensSettleUntil = now + 320L;
                selectionLens.animate()
                        .x(targetX)
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240L)
                        .setInterpolator(new OvershootInterpolator(0.42f))
                        .withEndAction(() -> {
                            lensSettleUntil = 0L;
                            selectionLens.setInteractionActive(false);
                            updateLensSurface(false);
                            updateDragHandleFromLens();
                            postDelayed(this::captureBackdrop, 32L);
                        })
                        .start();
            } else {
                selectionLens.setX(targetX);
                selectionLens.setScaleX(1f);
                selectionLens.setScaleY(1f);
                lensSettleUntil = 0L;
            }

            updateDragHandleFromLens();
        }

        private void updateDragHandleFromLens() {
            if (dragHandle == null
                    || selectionLens.getVisibility() != View.VISIBLE
                    || selectionLens.getWidth() <= 0
                    || selectionLens.getHeight() <= 0) {
                return;
            }

            int[] layerLocation = new int[2];
            int[] hostLocation = new int[2];
            getLocationInWindow(layerLocation);
            captureRoot.getLocationInWindow(hostLocation);

            FrameLayout.LayoutParams lp =
                    dragHandle.getLayoutParams() instanceof FrameLayout.LayoutParams
                            ? (FrameLayout.LayoutParams) dragHandle.getLayoutParams()
                            : new FrameLayout.LayoutParams(1, 1);
            lp.width = selectionLens.getWidth();
            lp.height = selectionLens.getHeight();
            lp.gravity = Gravity.NO_GRAVITY;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            dragHandle.setLayoutParams(lp);

            dragHandle.setX(
                    layerLocation[0] - hostLocation[0] + selectionLens.getX()
            );
            dragHandle.setY(
                    layerLocation[1] - hostLocation[1] + selectionLens.getY()
            );
            dragHandle.setVisibility(View.VISIBLE);
            dragHandle.bringToFront();
        }

        private boolean handleLensTouch(MotionEvent event) {
            if (selectionLens.getVisibility() != View.VISIBLE) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    draggingLens = true;
                    lensSettleUntil = 0L;
                    selectionLens.animate().cancel();

                    dragStartRawX = event.getRawX();
                    dragDownRawX = event.getRawX();
                    dragStartLensX = selectionLens.getX();

                    if (velocityTracker != null) velocityTracker.recycle();
                    velocityTracker = VelocityTracker.obtain();
                    velocityTracker.addMovement(event);

                    selectionLens.setInteractionActive(true);
                    updateLensSurface(true);
                    selectionLens.animate()
                            .scaleX(1.16f)
                            .scaleY(1.16f)
                            .setDuration(100L)
                            .start();
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (!draggingLens) return false;
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                    }

                    float rawTarget = dragStartLensX
                            + (event.getRawX() - dragStartRawX);
                    float maxX = Math.max(
                            0f,
                            getWidth() - selectionLens.getWidth()
                    );
                    float targetX = Math.max(0f, Math.min(maxX, rawTarget));

                    selectionLens.setX(targetX);
                    selectionLens.setSnapshot(snapshot, Math.round(targetX));

                    float vx = velocityTracker != null
                            ? velocityTracker.getXVelocity()
                            : 0f;
                    float stretch = Math.min(
                            0.08f,
                            Math.abs(vx) / 14000f
                    );
                    selectionLens.setScaleX(1.16f * (1f + stretch));
                    selectionLens.setScaleY(
                            1.16f * (1f - stretch * 0.30f)
                    );
                    updateDragHandleFromLens();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!draggingLens) return false;
                    draggingLens = false;

                    float vx = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.addMovement(event);
                        velocityTracker.computeCurrentVelocity(1000);
                        vx = velocityTracker.getXVelocity();
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }

                    List<View> tabs = visibleNativeTabs(nativeBar);
                    if (tabs.isEmpty()) {
                        selectionLens.setInteractionActive(false);
                        updateLensSurface(false);
                        selectionLens.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(160L)
                                .start();
                        return true;
                    }

                    boolean wasTap = Math.abs(event.getRawX() - dragDownRawX)
                            < dp(getContext(), 8);

                    float projectedCenter = selectionLens.getX()
                            + selectionLens.getWidth() / 2f
                            + (wasTap ? 0f : vx * 0.075f);

                    int targetIndex = nearestTabIndex(tabs, projectedCenter);
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                            && visualSelectedTabIndex >= 0
                            && visualSelectedTabIndex < tabs.size()) {
                        targetIndex = visualSelectedTabIndex;
                    }

                    targetIndex = Math.max(
                            0,
                            Math.min(tabs.size() - 1, targetIndex)
                    );
                    visualSelectedTabIndex = targetIndex;
                    View target = tabs.get(targetIndex);

                    moveLensToTab(target, true);

                    if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                        try {
                            target.performClick();
                        } catch (Throwable t) {
                            ModuleLog.line(
                                    "(InstaLy | FloatingNav): native tab click failed",
                                    t
                            );
                        }
                    }
                    return true;
                }

                default:
                    return draggingLens;
            }
        }

        private int nearestTabIndex(List<View> tabs, float centerX) {
            int best = 0;
            float bestDistance = Float.MAX_VALUE;
            int[] layerLocation = new int[2];
            getLocationInWindow(layerLocation);

            for (int i = 0; i < tabs.size(); i++) {
                View tab = tabs.get(i);
                int[] tabLocation = new int[2];
                tab.getLocationInWindow(tabLocation);
                float tabCenter = tabLocation[0] - layerLocation[0]
                        + tab.getWidth() / 2f;
                float distance = Math.abs(tabCenter - centerX);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            return best;
        }

        private void updateLensSurface(boolean pressed) {
            int fill = pressed
                    ? 0x08000000
                    : (isLightTheme(getContext())
                    ? 0x1C000000
                    : 0x34FFFFFF);
            int stroke = pressed
                    ? (isLightTheme(getContext())
                    ? 0x40000000
                    : 0x66FFFFFF)
                    : (isLightTheme(getContext())
                    ? 0x3D000000
                    : 0x70FFFFFF);

            lensSurfaceDrawable.setColor(fill);
            lensSurfaceDrawable.setStroke(dp(getContext(), 1), stroke);
            selectionLens.invalidate();
        }
    }

    private static final class BackdropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final boolean selectionLens;
        private Bitmap snapshot;
        private int sampleOffsetX;
        private int configuredWidth = -1;
        private int configuredHeight = -1;
        private boolean interactionActive;

        BackdropView(Context context, boolean selectionLens) {
            super(context);
            this.selectionLens = selectionLens;
            setWillNotDraw(false);
        }

        void setSnapshot(Bitmap bitmap, int sampleOffsetX) {
            this.snapshot = bitmap;
            this.sampleOffsetX = sampleOffsetX;
            invalidate();
        }

        void setInteractionActive(boolean active) {
            if (interactionActive == active) return;
            interactionActive = active;
            configuredWidth = -1;
            configuredHeight = -1;
            if (getWidth() > 0 && getHeight() > 0) {
                configureEffect(getWidth(), getHeight());
            }
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            configureEffect(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (snapshot == null || snapshot.isRecycled()) return;
            canvas.drawBitmap(snapshot, -sampleOffsetX, 0f, paint);
        }

        private void configureEffect(int width, int height) {
            if (width <= 0 || height <= 0) return;
            if (width == configuredWidth && height == configuredHeight) return;
            configuredWidth = width;
            configuredHeight = height;

            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    Api33Effects.applyLiquidGlass(
                            this, width, height, selectionLens, interactionActive);
                } else if (Build.VERSION.SDK_INT >= 31) {
                    Api31Effects.applyBlurAndVibrancy(this, dp(getContext(), 8));
                }
            } catch (Throwable t) {
                ModuleLog.line("(InstaLy | FloatingNav): RenderEffect fallback", t);
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        Api31Effects.applyBlurAndVibrancy(this, dp(getContext(), 8));
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static final class Api31Effects {
        static void applyBlurAndVibrancy(View view, float blurRadius) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(1.5f);
            RenderEffect vibrancy = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(matrix));
            RenderEffect blur = RenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    vibrancy,
                    Shader.TileMode.CLAMP
            );
            view.setRenderEffect(blur);
        }
    }

    private static final class Api33Effects {

        // Adapted from AndroidLiquidGlass Backdrop's rounded-rectangle refraction shaders.
        private static final String REFRACTION_SHADER = """
                uniform shader content;
                uniform float2 size;
                uniform float radius;
                uniform float refractionHeight;
                uniform float refractionAmount;

                float sdRoundedRect(float2 coord, float2 halfSize, float r) {
                    float2 cornerCoord = abs(coord) - (halfSize - float2(r));
                    float outside = length(max(cornerCoord, 0.0)) - r;
                    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                    return outside + inside;
                }

                float2 gradSdRoundedRect(float2 coord, float2 halfSize, float r) {
                    float2 cornerCoord = abs(coord) - (halfSize - float2(r));
                    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                        return sign(coord) * normalize(max(cornerCoord, 0.0001));
                    }
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }

                float circleMap(float x) {
                    x = clamp(x, 0.0, 1.0);
                    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
                }

                float2 safeCoord(float2 p) {
                    return clamp(p, float2(0.5), size - float2(0.5));
                }

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centered = coord - halfSize;
                    float sd = sdRoundedRect(centered, halfSize, radius);
                    if (-sd >= refractionHeight) {
                        return content.eval(safeCoord(coord));
                    }
                    sd = min(sd, 0.0);
                    float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
                    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius));
                    return content.eval(safeCoord(coord + d * grad));
                }
                """;

        private static final String DISPERSION_SHADER = """
                uniform shader content;
                uniform float2 size;
                uniform float radius;
                uniform float refractionHeight;
                uniform float refractionAmount;
                uniform float chromaticAberration;

                float sdRoundedRect(float2 coord, float2 halfSize, float r) {
                    float2 cornerCoord = abs(coord) - (halfSize - float2(r));
                    float outside = length(max(cornerCoord, 0.0)) - r;
                    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                    return outside + inside;
                }

                float2 gradSdRoundedRect(float2 coord, float2 halfSize, float r) {
                    float2 cornerCoord = abs(coord) - (halfSize - float2(r));
                    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                        return sign(coord) * normalize(max(cornerCoord, 0.0001));
                    }
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }

                float circleMap(float x) {
                    x = clamp(x, 0.0, 1.0);
                    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
                }

                float2 safeCoord(float2 p) {
                    return clamp(p, float2(0.5), size - float2(0.5));
                }

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centered = coord - halfSize;
                    float sd = sdRoundedRect(centered, halfSize, radius);
                    if (-sd >= refractionHeight) {
                        return content.eval(safeCoord(coord));
                    }
                    sd = min(sd, 0.0);
                    float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
                    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius));
                    float2 refracted = coord + d * grad;
                    float intensity = chromaticAberration
                            * ((centered.x * centered.y) / max(1.0, halfSize.x * halfSize.y));
                    float2 dispersed = d * grad * intensity;

                    half4 color = half4(0.0);
                    half4 red = content.eval(safeCoord(refracted + dispersed));
                    color.r += red.r / 3.5; color.a += red.a / 7.0;
                    half4 orange = content.eval(safeCoord(refracted + dispersed * 0.6667));
                    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
                    half4 yellow = content.eval(safeCoord(refracted + dispersed * 0.3333));
                    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
                    half4 green = content.eval(safeCoord(refracted));
                    color.g += green.g / 3.5; color.a += green.a / 7.0;
                    half4 cyan = content.eval(safeCoord(refracted - dispersed * 0.3333));
                    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
                    half4 blue = content.eval(safeCoord(refracted - dispersed * 0.6667));
                    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
                    half4 purple = content.eval(safeCoord(refracted - dispersed));
                    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
                    return color;
                }
                """;

        static void applyLiquidGlass(
                View view,
                int width,
                int height,
                boolean selectionLens,
                boolean interactionActive
        ) {
            float density = view.getResources().getDisplayMetrics().density;
            float blurRadius = 8f * density;
            float radius = selectionLens ? height / 2f : Math.min(30f * density, height / 2f);

            // Kyant's selected capsule has no strong lens at rest. Refraction/chromatic
            // aberration ramps in while pressed/dragging.
            if (selectionLens && !interactionActive) {
                Api31Effects.applyBlurAndVibrancy(view, blurRadius);
                return;
            }

            float refractionHeight = (selectionLens ? 10f : 24f) * density;
            float refractionAmount = (selectionLens ? 14f : 24f) * density;

            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(1.5f);
            RenderEffect vibrancy = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(matrix));
            RenderEffect blur = RenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    vibrancy,
                    Shader.TileMode.CLAMP
            );

            RuntimeShader runtime = new RuntimeShader(selectionLens ? DISPERSION_SHADER : REFRACTION_SHADER);
            runtime.setFloatUniform("size", (float) width, (float) height);
            runtime.setFloatUniform("radius", radius);
            runtime.setFloatUniform("refractionHeight", refractionHeight);
            runtime.setFloatUniform("refractionAmount", refractionAmount);
            if (selectionLens) {
                runtime.setFloatUniform("chromaticAberration", 1f);
            }

            RenderEffect lens = RenderEffect.createRuntimeShaderEffect(runtime, "content");
            view.setRenderEffect(RenderEffect.createChainEffect(lens, blur));
        }
    }
}
