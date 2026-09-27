package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.Checkable;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

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
    private static final long CAPTURE_INTERVAL_MS = 16L;
    private static final long SELECTION_INTERVAL_MS = 50L;
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

        FrameLayout selectorHost = layer.getSelectorHost();
        FrameLayout.LayoutParams selectorHostLp = new FrameLayout.LayoutParams(layerLp);
        selectorHost.setElevation(
                Math.max(bar.getElevation(), dp(activity, 4)) + dp(activity, 2)
        );
        host.addView(selectorHost, selectorHostLp);
        selectorHost.bringToFront();

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
                    || !layer.isSelectorOverlayAttached()
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

            layer.syncSelectorOverlayLayout(
                    sideMargin,
                    bottomMargin,
                    Math.max(1, bar.getHeight())
            );

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
        private final KyantChromeView bodyChrome;
        private final FrameLayout selectorHost;
        private final BackdropView selectionLens;
        private final KyantChromeView selectorChrome;

        private final ViewTreeObserver.OnPreDrawListener preDrawListener;

        private Runnable presentationEnforcer;
        private View dragHandle;
        private Bitmap snapshot;
        private long lastCaptureAt;
        private long lastSelectionAt;
        private boolean listenerAttached;

        private static final float KYANT_PRESSED_SCALE = 78f / 56f;

        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        // DampedDragAnimation port: animate a continuous TAB VALUE, not pixel X.
        private final FloatValueHolder tabValueHolder = new FloatValueHolder(0f);
        private final FloatValueHolder velocityValueHolder = new FloatValueHolder(0f);
        private final FloatValueHolder pressProgressValue = new FloatValueHolder(0f);
        private final FloatValueHolder pressScaleXValue = new FloatValueHolder(1f);
        private final FloatValueHolder pressScaleYValue = new FloatValueHolder(1f);
        private final FloatValueHolder highlightProgressValue = new FloatValueHolder(0f);
        private final SpringAnimation tabValueSpring;
        private final SpringAnimation velocitySpring;
        private final SpringAnimation pressProgressSpring;
        private final SpringAnimation pressScaleXSpring;
        private final SpringAnimation pressScaleYSpring;
        private final SpringAnimation highlightProgressSpring;

        private int visualSelectedTabIndex = -1;
        private boolean draggingLens;
        private boolean releaseScaleWhenSettled;
        private float dragLastRawX;
        private float dragTargetValue;
        private float tabValue;
        private float smoothedVelocity;
        private float pressProgress;
        private float pressScaleX = 1f;
        private float pressScaleY = 1f;
        private float highlightProgress;
        private final float nativeBarBaseScaleX;
        private final float nativeBarBaseScaleY;

        // Native-View analogue of Kyant's LayerBackdrop GraphicsLayer.
        private Object liveBackdropNode;
        private Object tabsGlassBackdropNode;
        private Object tabsBackdropNode;
        private Object tabsGlassEffectState;
        private boolean liveBackdropAvailable;
        private boolean loggedLiveBackdrop;
        private boolean loggedCombinedBackdrop;
        private long lastLiveLayerFailureLogAt;

        private boolean pixelCopyInFlight;
        private long lastPixelCopyAt;
        private long lastPixelCopyFailureLogAt;
        private SurfaceView pixelCopySurface;
        private Bitmap pixelCopyBuffer;
        private Rect pixelCopyDestination;

        private TextureView textureVideo;
        private Bitmap textureVideoBuffer;
        private Rect textureVideoDestination;
        private long lastTextureCopyAt;
        private String lastVideoBackdropMode;

        private final Paint hiddenHighlightPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private RuntimeShader hiddenInteractiveShader;
        private RuntimeShader hiddenEdgeShader;

        GlassLayer(Context context, FrameLayout captureRoot, ViewGroup nativeBar, View nativeShadow) {
            super(context);
            this.captureRoot = captureRoot;
            this.nativeBar = nativeBar;
            this.nativeShadow = nativeShadow;
            this.nativeBarBaseScaleX = nativeBar.getScaleX();
            this.nativeBarBaseScaleY = nativeBar.getScaleY();

            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            setClickable(false);
            setFocusable(false);

            backdropView = new BackdropView(context, false);
            backdropView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(
                            0, 0, view.getWidth(), view.getHeight(),
                            view.getHeight() / 2f
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
            surface.setCornerRadius(dp(context, 100));
            surface.setColor(isLightTheme(context) ? 0x66FAFAFA : 0x66121212);
            surfaceTint.setBackground(surface);
            addView(surfaceTint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            bodyChrome = new KyantChromeView(context, false);
            addView(bodyChrome, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            selectorHost = new FrameLayout(context);
            selectorHost.setClipChildren(false);
            selectorHost.setClipToPadding(false);
            selectorHost.setClickable(false);
            selectorHost.setFocusable(false);
            selectorHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

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

            selectorHost.addView(selectionLens, new FrameLayout.LayoutParams(1, 1));

            selectorChrome = new KyantChromeView(context, true);
            selectorChrome.setVisibility(View.INVISIBLE);
            selectorChrome.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            );
            selectorHost.addView(selectorChrome, new FrameLayout.LayoutParams(1, 1));

            // Kyant's outer Row uses the default shadow; platform elevation is the closest
            // native-View equivalent without rasterizing the whole navbar.
            surfaceTint.setElevation(dp(context, 6));

            // Kyant: velocityAnimation spring(0.5f, 300f).
            velocitySpring = new SpringAnimation(velocityValueHolder);
            velocitySpring.setSpring(new SpringForce()
                    .setDampingRatio(0.5f)
                    .setStiffness(300f));
            velocitySpring.setMinimumVisibleChange(0.01f);
            velocitySpring.addUpdateListener((animation, value, velocity) -> {
                smoothedVelocity = value;
                applyKyantTransform();
            });

            tabValueSpring = new SpringAnimation(tabValueHolder);
            tabValueSpring.setSpring(new SpringForce()
                    .setDampingRatio(1f)
                    .setStiffness(1000f));
            tabValueSpring.setMinimumVisibleChange(0.001f);
            tabValueSpring.addUpdateListener((animation, value, velocity) -> {
                tabValue = value;

                List<View> tabs = visibleNativeTabs(nativeBar);
                float range = Math.max(1f, tabs.size() - 1f);
                velocitySpring.animateToFinalPosition(velocity / range);

                applySelectorFromTabValue();
            });
            tabValueSpring.addEndListener((animation, canceled, value, velocity) -> {
                tabValue = value;
                velocitySpring.animateToFinalPosition(0f);
                if (!draggingLens && releaseScaleWhenSettled) {
                    releaseScaleWhenSettled = false;
                    releaseKyantPress();
                }
                applySelectorFromTabValue();
                postOnAnimation(this::captureBackdrop);
            });

            // Kyant: pressProgressAnimation spring(1f, 1000f).
            pressProgressSpring = new SpringAnimation(pressProgressValue);
            pressProgressSpring.setSpring(new SpringForce()
                    .setDampingRatio(1f)
                    .setStiffness(1000f));
            pressProgressSpring.setMinimumVisibleChange(0.001f);
            pressProgressSpring.addUpdateListener((animation, value, velocity) -> {
                pressProgress = Math.max(0f, Math.min(1f, value));
                selectionLens.setInteractionProgress(pressProgress);
                selectorChrome.setPressProgress(pressProgress);
                applyOuterPressTransform();
            });

            pressScaleXSpring = new SpringAnimation(pressScaleXValue);
            pressScaleXSpring.setSpring(new SpringForce()
                    .setDampingRatio(0.6f)
                    .setStiffness(250f));
            pressScaleXSpring.setMinimumVisibleChange(0.001f);
            pressScaleXSpring.addUpdateListener((animation, value, velocity) -> {
                pressScaleX = value;
                applyKyantTransform();
            });

            pressScaleYSpring = new SpringAnimation(pressScaleYValue);
            pressScaleYSpring.setSpring(new SpringForce()
                    .setDampingRatio(0.7f)
                    .setStiffness(250f));
            pressScaleYSpring.setMinimumVisibleChange(0.001f);
            pressScaleYSpring.addUpdateListener((animation, value, velocity) -> {
                pressScaleY = value;
                applyKyantTransform();
            });

            highlightProgressSpring = new SpringAnimation(highlightProgressValue);
            highlightProgressSpring.setSpring(new SpringForce()
                    .setDampingRatio(0.5f)
                    .setStiffness(300f));
            highlightProgressSpring.setMinimumVisibleChange(0.001f);
            highlightProgressSpring.addUpdateListener((animation, value, velocity) -> {
                highlightProgress = Math.max(0f, Math.min(1f, value));
                updateInteractiveHighlight();
            });

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
                if (selectorHost.getVisibility() != wantedVisibility) {
                    selectorHost.setVisibility(wantedVisibility);
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

        FrameLayout getSelectorHost() {
            return selectorHost;
        }

        boolean isSelectorOverlayAttached() {
            return selectorHost.getParent() == captureRoot;
        }

        void syncSelectorOverlayLayout(int sideMargin, int bottomMargin, int height) {
            if (!(selectorHost.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) selectorHost.getLayoutParams();
            int desiredHeight = Math.max(1, height);
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                    && lp.height == desiredHeight
                    && lp.gravity == Gravity.BOTTOM
                    && lp.leftMargin == sideMargin
                    && lp.rightMargin == sideMargin
                    && lp.bottomMargin == bottomMargin) {
                return;
            }
            FrameLayout.LayoutParams fixed = new FrameLayout.LayoutParams(lp);
            fixed.width = ViewGroup.LayoutParams.MATCH_PARENT;
            fixed.height = desiredHeight;
            fixed.gravity = Gravity.BOTTOM;
            fixed.leftMargin = sideMargin;
            fixed.rightMargin = sideMargin;
            fixed.bottomMargin = bottomMargin;
            selectorHost.setLayoutParams(fixed);
        }

        void attachDragHandle(View handle) {
            this.dragHandle = handle;
            handle.setOnTouchListener((v, event) -> handleLensTouch(event));
            postOnAnimation(() -> primeInitialSelection(0));
        }

        private void primeInitialSelection(int attempt) {
            if (!isAttachedToWindow() || attempt > 90) return;

            List<View> tabs = visibleNativeTabs(nativeBar);
            if (getWidth() <= 1 || getHeight() <= 1 || tabs.size() < 3) {
                postOnAnimation(() -> primeInitialSelection(attempt + 1));
                return;
            }

            int selected = selectedNativeTabIndex(nativeBar);
            if (selected < 0 || selected >= tabs.size()) {
                selected = visualSelectedTabIndex >= 0
                        && visualSelectedTabIndex < tabs.size()
                        ? visualSelectedTabIndex
                        : 0;
            }

            visualSelectedTabIndex = selected;
            View tab = tabs.get(selected);
            if (tab.getWidth() <= 0 || tab.getHeight() <= 0) {
                postOnAnimation(() -> primeInitialSelection(attempt + 1));
                return;
            }

            captureBackdrop();
            moveLensToTab(tab, false);
            updateDragHandleFromLens();

            if (selectionLens.getWidth() <= 1 || selectionLens.getHeight() <= 1) {
                postOnAnimation(() -> primeInitialSelection(attempt + 1));
                return;
            }

            ModuleLog.line(
                    "(InstaLy | FloatingNav): initial liquid selector -> " + selected
            );
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
            if (selectorHost.getParent() instanceof ViewGroup) {
                ((ViewGroup) selectorHost.getParent()).removeView(selectorHost);
            }
            tabValueSpring.cancel();
            velocitySpring.cancel();
            pressProgressSpring.cancel();
            pressScaleXSpring.cancel();
            pressScaleYSpring.cancel();
            highlightProgressSpring.cancel();
            if (pixelCopyBuffer != null) {
                pixelCopyBuffer.recycle();
                pixelCopyBuffer = null;
            }
            if (textureVideoBuffer != null) {
                textureVideoBuffer.recycle();
                textureVideoBuffer = null;
            }
            pixelCopySurface = null;
            textureVideo = null;
            nativeBar.setScaleX(nativeBarBaseScaleX);
            nativeBar.setScaleY(nativeBarBaseScaleY);
            liveBackdropNode = null;
            tabsGlassBackdropNode = null;
            tabsBackdropNode = null;
            tabsGlassEffectState = null;
            backdropView.setLiveBackdrop(null, 0, 0);
            backdropView.setSecondaryLiveBackdrop(null);
            selectionLens.setLiveBackdrop(null, 0, 0);
            selectionLens.setSecondaryLiveBackdrop(null);
            backdropView.setSurfacePatch(null, null);
            selectionLens.setSurfacePatch(null, null);
            backdropView.setTexturePatch(null, null);
            selectionLens.setTexturePatch(null, null);
            if (snapshot != null) {
                snapshot.recycle();
                snapshot = null;
            }
            backdropView.setSnapshot(null, 0, 0);
            selectionLens.setSnapshot(null, 0, 0);
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
            pressKyant();
            releaseScaleWhenSettled = true;
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

            boolean recorded = false;
            if (Build.VERSION.SDK_INT >= 29) {
                recorded = recordLiveBackdrop(width, height);
            }

            if (!recorded) {
                captureBitmapFallback(width, height);
            }

            captureIntersectingVideoLayer();
        }

        private boolean recordLiveBackdrop(int width, int height) {
            try {
                liveBackdropNode = Api29LiveBackdrop.record(
                        liveBackdropNode,
                        width,
                        height,
                        this::drawBackdropSource
                );

                int hiddenHeight = Math.max(
                        1,
                        Math.min(dp(getContext(), 56), height)
                );
                int hiddenTop = Math.max(0, (height - hiddenHeight) / 2);
                float density = getResources().getDisplayMetrics().density;
                float blurPadding = 8f * density;
                float lensHeight =
                        Build.VERSION.SDK_INT >= 33
                                ? 24f * density * pressProgress
                                : 0f;
                int hiddenPadding =
                        Build.VERSION.SDK_INT >= 31
                                ? Math.max(
                                        0,
                                        Math.round(blurPadding - lensHeight)
                                )
                                : 0;

                tabsGlassBackdropNode = Api29LiveBackdrop.record(
                        tabsGlassBackdropNode,
                        width + hiddenPadding * 2,
                        hiddenHeight + hiddenPadding * 2,
                        canvas -> drawHiddenGlassSource(
                                canvas,
                                hiddenTop,
                                hiddenPadding
                        )
                );

                if (Build.VERSION.SDK_INT >= 33) {
                    tabsGlassEffectState = Api33Effects.applyHiddenTabsGlass(
                            tabsGlassBackdropNode,
                            tabsGlassEffectState,
                            width,
                            hiddenHeight,
                            hiddenPadding,
                            density,
                            pressProgress
                    );
                } else if (Build.VERSION.SDK_INT >= 31) {
                    Api31Effects.applyHiddenTabsGlass(
                            tabsGlassBackdropNode,
                            dp(getContext(), 8)
                    );
                }

                tabsBackdropNode = Api29LiveBackdrop.record(
                        tabsBackdropNode,
                        width,
                        height,
                        canvas -> drawHiddenTabsBackdrop(
                                canvas,
                                hiddenTop,
                                hiddenHeight,
                                hiddenPadding
                        )
                );

                liveBackdropAvailable = true;
                if (!loggedLiveBackdrop) {
                    loggedLiveBackdrop = true;
                    ModuleLog.line(
                            "(InstaLy | FloatingNav): backdrop source=live RenderNode"
                    );
                }
                if (!loggedCombinedBackdrop) {
                    loggedCombinedBackdrop = true;
                    ModuleLog.line(
                            "(InstaLy | FloatingNav): selector backdrop=page+exportedHiddenTabs (Kyant catalog)"
                    );
                }

                backdropView.setLiveBackdrop(liveBackdropNode, 0, 0);
                backdropView.setSecondaryLiveBackdrop(null);

                selectionLens.setLiveBackdrop(
                        liveBackdropNode,
                        Math.round(selectionLens.getX()),
                        Math.round(selectionLens.getY())
                );
                selectionLens.setSecondaryLiveBackdrop(tabsBackdropNode);
                return true;
            } catch (Throwable t) {
                liveBackdropAvailable = false;
                liveBackdropNode = null;
                tabsGlassBackdropNode = null;
                tabsBackdropNode = null;
                tabsGlassEffectState = null;
                backdropView.setLiveBackdrop(null, 0, 0);
                backdropView.setSecondaryLiveBackdrop(null);
                selectionLens.setLiveBackdrop(
                        null,
                        Math.round(selectionLens.getX()),
                        Math.round(selectionLens.getY())
                );
                selectionLens.setSecondaryLiveBackdrop(null);
                long now = SystemClock.uptimeMillis();
                if (now - lastLiveLayerFailureLogAt > 3000L) {
                    lastLiveLayerFailureLogAt = now;
                    ModuleLog.line(
                            "(InstaLy | FloatingNav): RenderNode live backdrop failed; using bitmap fallback",
                            t
                    );
                }
                return false;
            }
        }

        private void drawHiddenGlassSource(
                Canvas canvas,
                int hiddenTop,
                int hiddenPadding
        ) {
            int save = canvas.save();
            canvas.translate(
                    hiddenPadding,
                    hiddenPadding - hiddenTop
            );
            drawBackdropSource(canvas);
            drawVideoPatches(canvas);
            canvas.restoreToCount(save);
        }

        private void drawHiddenTabsBackdrop(
                Canvas canvas,
                int hiddenTop,
                int hiddenHeight,
                int hiddenPadding
        ) {
            float radius = hiddenHeight / 2f;
            RectF body = new RectF(
                    0f,
                    hiddenTop,
                    getWidth(),
                    hiddenTop + hiddenHeight
            );
            Path clip = new Path();
            clip.addRoundRect(body, radius, radius, Path.Direction.CW);

            int save = canvas.save();
            canvas.clipPath(clip);

            if (tabsGlassBackdropNode != null) {
                int glassSave = canvas.save();
                canvas.translate(
                        -hiddenPadding,
                        hiddenTop - hiddenPadding
                );
                Api29LiveBackdrop.draw(canvas, tabsGlassBackdropNode);
                canvas.restoreToCount(glassSave);
            }

            Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
            surface.setColor(
                    isLightTheme(getContext()) ? 0x66FAFAFA : 0x66121212
            );
            canvas.drawRoundRect(body, radius, radius, surface);

            if (Build.VERSION.SDK_INT >= 33) {
                if (hiddenInteractiveShader == null) {
                    hiddenInteractiveShader = new RuntimeShader(
                            KyantChromeView.INTERACTIVE_HIGHLIGHT_SHADER
                    );
                }
                if (hiddenEdgeShader == null) {
                    hiddenEdgeShader = new RuntimeShader(
                            Api33Effects.DEFAULT_HIGHLIGHT_SHADER
                    );
                }
            }

            KyantChromeView.drawInteractiveHighlight(
                    canvas,
                    body,
                    hiddenHighlightPaint,
                    hiddenInteractiveShader,
                    highlightProgress,
                    selectorCenterX()
            );

            drawAccentTabsBackdrop(canvas);

            KyantChromeView.drawDefaultHighlight(
                    canvas,
                    body,
                    hiddenHighlightPaint,
                    hiddenEdgeShader,
                    pressProgress,
                    getResources().getDisplayMetrics().density
            );

            canvas.restoreToCount(save);
        }

        private void drawAccentTabsBackdrop(Canvas canvas) {
            // Crema captures an invisible, accent-treated copy of the complete tab row into a
            // second Backdrop. The moving selector samples that together with the page backdrop.
            int[] layerLocation = new int[2];
            int[] childLocation = new int[2];
            getLocationInWindow(layerLocation);

            int accentColor = isLightTheme(getContext())
                    ? Color.rgb(0, 136, 255)
                    : Color.rgb(0, 145, 255);

            Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            tintPaint.setColorFilter(
                    new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
            );

            float tabScale = 1f + 0.20f * pressProgress;

            List<View> tabs = visibleNativeTabs(nativeBar);
            for (View tab : tabs) {
                if (tab.getVisibility() != View.VISIBLE || tab.getAlpha() <= 0f) continue;

                tab.getLocationInWindow(childLocation);
                int left = childLocation[0] - layerLocation[0];
                int top = childLocation[1] - layerLocation[1];

                int save = canvas.save();
                canvas.translate(left, top);
                canvas.scale(
                        tabScale,
                        tabScale,
                        tab.getWidth() / 2f,
                        tab.getHeight() / 2f
                );

                int layerSave = canvas.saveLayer(
                        0f,
                        0f,
                        tab.getWidth(),
                        tab.getHeight(),
                        tintPaint
                );
                tab.draw(canvas);
                canvas.restoreToCount(layerSave);
                canvas.restoreToCount(save);
            }
        }

        private void captureBitmapFallback(int width, int height) {
            try {
                if (snapshot == null
                        || snapshot.getWidth() != width
                        || snapshot.getHeight() != height) {
                    if (snapshot != null) snapshot.recycle();
                    snapshot = Bitmap.createBitmap(
                            width,
                            height,
                            Bitmap.Config.ARGB_8888
                    );
                } else {
                    snapshot.eraseColor(Color.TRANSPARENT);
                }

                Canvas canvas = new Canvas(snapshot);
                drawBackdropSource(canvas);

                backdropView.setSnapshot(snapshot, 0, 0);
                selectionLens.setSnapshot(
                        snapshot,
                        Math.round(selectionLens.getX()),
                        Math.round(selectionLens.getY())
                );
            } catch (Throwable t) {
                ModuleLog.line("(InstaLy | FloatingNav): bitmap backdrop fallback failed", t);
            }
        }

        private void drawBackdropSource(Canvas canvas) {
            int[] layerLocation = new int[2];
            getLocationInWindow(layerLocation);

            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());

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
                        || child == selectorHost
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

            canvas.restore();
        }

        private void drawVideoPatches(Canvas canvas) {
            Paint patchPaint =
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (pixelCopyBuffer != null
                    && !pixelCopyBuffer.isRecycled()
                    && pixelCopyDestination != null) {
                canvas.drawBitmap(
                        pixelCopyBuffer,
                        null,
                        pixelCopyDestination,
                        patchPaint
                );
            }
            if (textureVideoBuffer != null
                    && !textureVideoBuffer.isRecycled()
                    && textureVideoDestination != null) {
                canvas.drawBitmap(
                        textureVideoBuffer,
                        null,
                        textureVideoDestination,
                        patchPaint
                );
            }
        }

        private void captureIntersectingVideoLayer() {
            int[] layerLocation = new int[2];
            getLocationInWindow(layerLocation);
            Rect layerRect = new Rect(
                    layerLocation[0],
                    layerLocation[1],
                    layerLocation[0] + getWidth(),
                    layerLocation[1] + getHeight()
            );

            View video = findTopmostIntersectingVideoView(captureRoot, layerRect);
            if (video instanceof SurfaceView) {
                clearTexturePatch();
                captureSurfaceVideo((SurfaceView) video);
            } else if (video instanceof TextureView) {
                clearSurfacePatch();
                captureTextureVideo((TextureView) video);
            } else {
                clearSurfacePatch();
                clearTexturePatch();
            }
        }

        private void captureSurfaceVideo(SurfaceView surface) {
            if (pixelCopyInFlight
                    || !surface.isAttachedToWindow()
                    || surface.getWidth() <= 1
                    || surface.getHeight() <= 1
                    || surface.getHolder() == null
                    || surface.getHolder().getSurface() == null
                    || !surface.getHolder().getSurface().isValid()
                    || SystemClock.uptimeMillis() - lastPixelCopyAt < 33L) {
                return;
            }

            int sourceWidth = surface.getWidth();
            int sourceHeight = surface.getHeight();
            float scale = Math.min(1f, 720f / Math.max(1, sourceWidth));
            int copyWidth = Math.max(2, Math.round(sourceWidth * scale));
            int copyHeight = Math.max(2, Math.round(sourceHeight * scale));

            if (pixelCopyBuffer == null
                    || pixelCopyBuffer.isRecycled()
                    || pixelCopyBuffer.getWidth() != copyWidth
                    || pixelCopyBuffer.getHeight() != copyHeight
                    || pixelCopySurface != surface) {
                if (pixelCopyBuffer != null && !pixelCopyBuffer.isRecycled()) {
                    pixelCopyBuffer.recycle();
                }
                pixelCopyBuffer = Bitmap.createBitmap(
                        copyWidth,
                        copyHeight,
                        Bitmap.Config.ARGB_8888
                );
                pixelCopySurface = surface;
            }

            pixelCopyInFlight = true;
            lastPixelCopyAt = SystemClock.uptimeMillis();
            Bitmap destination = pixelCopyBuffer;

            try {
                PixelCopy.request(
                        surface,
                        destination,
                        result -> {
                            pixelCopyInFlight = false;
                            if (result != PixelCopy.SUCCESS
                                    || destination.isRecycled()
                                    || !surface.isAttachedToWindow()
                                    || pixelCopySurface != surface) {
                                if (result != PixelCopy.SUCCESS) {
                                    long now = SystemClock.uptimeMillis();
                                    if (now - lastPixelCopyFailureLogAt > 3000L) {
                                        lastPixelCopyFailureLogAt = now;
                                        ModuleLog.line(
                                                "(InstaLy | FloatingNav): SurfaceView PixelCopy result="
                                                        + result
                                        );
                                    }
                                }
                                return;
                            }

                            Rect destinationRect = viewRectInLayer(surface);
                            pixelCopyDestination = destinationRect;
                            logVideoBackdropMode("SurfaceView/PixelCopy");
                            backdropView.setSurfacePatch(destination, destinationRect);
                            selectionLens.setSurfacePatch(destination, destinationRect);
                        },
                        mainHandler
                );
            } catch (Throwable t) {
                pixelCopyInFlight = false;
                long now = SystemClock.uptimeMillis();
                if (now - lastPixelCopyFailureLogAt > 3000L) {
                    lastPixelCopyFailureLogAt = now;
                    ModuleLog.line(
                            "(InstaLy | FloatingNav): SurfaceView PixelCopy failed",
                            t
                    );
                }
            }
        }

        private void captureTextureVideo(TextureView texture) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTextureCopyAt < 33L) return;
            lastTextureCopyAt = now;

            if (!texture.isAvailable()
                    || texture.getWidth() <= 1
                    || texture.getHeight() <= 1) {
                clearTexturePatch();
                return;
            }

            int sourceWidth = texture.getWidth();
            int sourceHeight = texture.getHeight();
            float scale = Math.min(1f, 720f / Math.max(1, sourceWidth));
            int copyWidth = Math.max(2, Math.round(sourceWidth * scale));
            int copyHeight = Math.max(2, Math.round(sourceHeight * scale));

            if (textureVideoBuffer == null
                    || textureVideoBuffer.isRecycled()
                    || textureVideoBuffer.getWidth() != copyWidth
                    || textureVideoBuffer.getHeight() != copyHeight
                    || textureVideo != texture) {
                if (textureVideoBuffer != null && !textureVideoBuffer.isRecycled()) {
                    textureVideoBuffer.recycle();
                }
                textureVideoBuffer = Bitmap.createBitmap(
                        copyWidth,
                        copyHeight,
                        Bitmap.Config.ARGB_8888
                );
                textureVideo = texture;
            }

            try {
                Bitmap copied = texture.getBitmap(textureVideoBuffer);
                if (copied == null) {
                    clearTexturePatch();
                    return;
                }
                textureVideoBuffer = copied;
                textureVideoDestination = viewRectInLayer(texture);
                logVideoBackdropMode("TextureView/getBitmap");
                backdropView.setTexturePatch(copied, textureVideoDestination);
                selectionLens.setTexturePatch(copied, textureVideoDestination);
            } catch (Throwable t) {
                clearTexturePatch();
            }
        }

        private void logVideoBackdropMode(String mode) {
            if (mode.equals(lastVideoBackdropMode)) return;
            lastVideoBackdropMode = mode;
            ModuleLog.line(
                    "(InstaLy | FloatingNav): video backdrop source=" + mode
            );
        }

        private Rect viewRectInLayer(View view) {
            int[] layerLocation = new int[2];
            int[] viewLocation = new int[2];
            getLocationInWindow(layerLocation);
            view.getLocationInWindow(viewLocation);
            return new Rect(
                    viewLocation[0] - layerLocation[0],
                    viewLocation[1] - layerLocation[1],
                    viewLocation[0] - layerLocation[0] + view.getWidth(),
                    viewLocation[1] - layerLocation[1] + view.getHeight()
            );
        }

        private void clearSurfacePatch() {
            pixelCopySurface = null;
            pixelCopyDestination = null;
            backdropView.setSurfacePatch(null, null);
            selectionLens.setSurfacePatch(null, null);
        }

        private void clearTexturePatch() {
            textureVideo = null;
            textureVideoDestination = null;
            backdropView.setTexturePatch(null, null);
            selectionLens.setTexturePatch(null, null);
        }

        private View findTopmostIntersectingVideoView(View root, Rect layerRect) {
            if (root == null
                    || root == this
                    || root == selectorHost
                    || root == nativeBar
                    || root == dragHandle
                    || root == nativeShadow
                    || root.getVisibility() != View.VISIBLE
                    || root.getAlpha() <= 0f) {
                return null;
            }

            if (root instanceof SurfaceView || root instanceof TextureView) {
                int[] location = new int[2];
                root.getLocationInWindow(location);
                Rect rect = new Rect(
                        location[0],
                        location[1],
                        location[0] + root.getWidth(),
                        location[1] + root.getHeight()
                );
                return Rect.intersects(layerRect, rect) ? root : null;
            }

            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View found =
                            findTopmostIntersectingVideoView(group.getChildAt(i), layerRect);
                    if (found != null) return found;
                }
            }
            return null;
        }

        private void moveLensToTab(View tab, boolean animate) {
            List<View> tabs = visibleNativeTabs(nativeBar);
            int index = tabs.indexOf(tab);
            if (index < 0 || tab.getWidth() <= 0 || tab.getHeight() <= 0) return;

            configureLensGeometry(tab);
            dragTargetValue = index;

            if (animate) {
                tabValueSpring.animateToFinalPosition(index);
            } else {
                tabValueSpring.cancel();
                tabValueHolder.setValue(index);
                tabValue = index;
                applySelectorFromTabValue();
            }
        }

        private void configureLensGeometry(View tab) {
            List<View> tabs = visibleNativeTabs(nativeBar);
            if (tabs.isEmpty()) return;

            int horizontalPadding = dp(getContext(), 4);
            float tabWidth = Math.max(
                    1f,
                    (getWidth() - horizontalPadding * 2f) / tabs.size()
            );

            int verticalPadding = dp(getContext(), 4);
            int availableHeight = Math.max(1, getHeight() - verticalPadding * 2);
            int desiredHeight = dp(getContext(), 56);
            int lensHeight = Math.min(desiredHeight, availableHeight);

            FrameLayout.LayoutParams lensLp =
                    (FrameLayout.LayoutParams) selectionLens.getLayoutParams();
            lensLp.width = Math.max(1, Math.round(tabWidth));
            lensLp.height = Math.max(1, lensHeight);
            lensLp.leftMargin = 0;
            lensLp.topMargin = Math.max(0, (getHeight() - lensHeight) / 2);
            selectionLens.setLayoutParams(lensLp);
            selectionLens.setVisibility(View.VISIBLE);
            selectionLens.setAlpha(1f);

            FrameLayout.LayoutParams chromeLp =
                    new FrameLayout.LayoutParams(lensLp);
            selectorChrome.setLayoutParams(chromeLp);
            selectorChrome.setVisibility(View.VISIBLE);
            selectorChrome.setAlpha(1f);
        }

        private void applySelectorFromTabValue() {
            List<View> tabs = visibleNativeTabs(nativeBar);
            if (tabs.isEmpty()) return;

            float value = Math.max(0f, Math.min(tabs.size() - 1f, tabValue));
            View nearest = tabs.get(
                    Math.max(0, Math.min(tabs.size() - 1, Math.round(value)))
            );
            configureLensGeometry(nearest);

            int horizontalPadding = dp(getContext(), 4);
            float tabWidth = Math.max(
                    1f,
                    (getWidth() - horizontalPadding * 2f) / tabs.size()
            );

            // Exact LiquidBottomTabs geometry:
            // translationX = value * tabWidth with a 4dp horizontal container padding.
            float x = horizontalPadding + value * tabWidth;
            selectionLens.setX(x);
            selectorChrome.setX(x);
            selectionLens.setSampleOffset(
                    Math.round(x),
                    Math.round(selectionLens.getY())
            );
            updateInteractiveHighlight();
            applyKyantTransform();
            updateDragHandleFromLens();
        }

        private float approximateTabWidth(List<View> tabs) {
            int horizontalPadding = dp(getContext(), 4);
            return Math.max(
                    1f,
                    (getWidth() - horizontalPadding * 2f)
                            / Math.max(1, tabs.size())
            );
        }

        private void pressKyant() {
            pressProgressSpring.animateToFinalPosition(1f);
            pressScaleXSpring.animateToFinalPosition(KYANT_PRESSED_SCALE);
            pressScaleYSpring.animateToFinalPosition(KYANT_PRESSED_SCALE);
        }

        private void releaseKyantPress() {
            pressProgressSpring.animateToFinalPosition(0f);
            pressScaleXSpring.animateToFinalPosition(1f);
            pressScaleYSpring.animateToFinalPosition(1f);
        }

        private void applyKyantTransform() {
            // Direct LiquidBottomTabs relationship:
            // velocity = dampedDragAnimation.velocity / 10
            // scaleX /= 1 - clamp(velocity * .75, -.2, .2)
            // scaleY *= 1 - clamp(velocity * .25, -.2, .2)
            float velocity = smoothedVelocity / 10f;
            float xVelocityShape = Math.max(-0.2f, Math.min(0.2f, velocity * 0.75f));
            float yVelocityShape = Math.max(-0.2f, Math.min(0.2f, velocity * 0.25f));
            float scaleX = pressScaleX / (1f - xVelocityShape);
            float scaleY = pressScaleY * (1f - yVelocityShape);
            selectionLens.setScaleX(scaleX);
            selectionLens.setScaleY(scaleY);
            selectionLens.setInverseScale(scaleX, scaleY);
            selectorChrome.setScaleX(scaleX);
            selectorChrome.setScaleY(scaleY);
        }

        private void applyOuterPressTransform() {
            float width = Math.max(1f, getWidth());
            float scale =
                    1f + (dp(getContext(), 16) / width) * pressProgress;

            backdropView.setScaleX(scale);
            backdropView.setScaleY(scale);
            backdropView.setInverseScale(scale, scale);
            surfaceTint.setScaleX(scale);
            surfaceTint.setScaleY(scale);
            bodyChrome.setScaleX(scale);
            bodyChrome.setScaleY(scale);
            nativeBar.setScaleX(nativeBarBaseScaleX * scale);
            nativeBar.setScaleY(nativeBarBaseScaleY * scale);
        }

        private float selectorCenterX() {
            List<View> tabs = visibleNativeTabs(nativeBar);
            if (tabs.isEmpty()) return getWidth() / 2f;
            int horizontalPadding = dp(getContext(), 4);
            float tabWidth = Math.max(
                    1f,
                    (getWidth() - horizontalPadding * 2f) / tabs.size()
            );
            float value = Math.max(
                    0f,
                    Math.min(tabs.size() - 1f, tabValue)
            );
            return horizontalPadding + (value + 0.5f) * tabWidth;
        }

        private void updateInteractiveHighlight() {
            bodyChrome.setInteractiveHighlight(
                    highlightProgress,
                    selectorCenterX()
            );
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
                    releaseScaleWhenSettled = false;
                    dragLastRawX = event.getRawX();
                    dragTargetValue = tabValue;
                    pressKyant();
                    highlightProgressSpring.animateToFinalPosition(1f);
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    if (!draggingLens) return false;

                    List<View> tabs = visibleNativeTabs(nativeBar);
                    if (tabs.isEmpty()) return true;

                    float rawX = event.getRawX();
                    float dx = rawX - dragLastRawX;
                    dragLastRawX = rawX;

                    float tabWidth = Math.max(1f, approximateTabWidth(tabs));
                    dragTargetValue = Math.max(
                            0f,
                            Math.min(
                                    tabs.size() - 1f,
                                    dragTargetValue + dx / tabWidth
                            )
                    );

                    // Exact DampedDragAnimation idea: the pointer only changes targetValue;
                    // the visible selector follows through the critical spring.
                    tabValueSpring.animateToFinalPosition(dragTargetValue);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!draggingLens) return false;
                    draggingLens = false;
                    highlightProgressSpring.animateToFinalPosition(0f);

                    List<View> tabs = visibleNativeTabs(nativeBar);
                    if (tabs.isEmpty()) {
                        releaseKyantPress();
                        return true;
                    }

                    int targetIndex;
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                            && visualSelectedTabIndex >= 0
                            && visualSelectedTabIndex < tabs.size()) {
                        targetIndex = visualSelectedTabIndex;
                    } else {
                        // Kyant rounds targetValue when the drag stops.
                        targetIndex = Math.round(dragTargetValue);
                    }

                    targetIndex = Math.max(0, Math.min(tabs.size() - 1, targetIndex));
                    visualSelectedTabIndex = targetIndex;
                    releaseScaleWhenSettled = true;
                    tabValueSpring.animateToFinalPosition(targetIndex);

                    if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                        try {
                            tabs.get(targetIndex).performClick();
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


    }

    private interface CanvasRecorder {
        void draw(Canvas canvas);
    }

    private static final class BackdropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final boolean selectionLens;

        private Bitmap snapshot;
        private Object liveBackdropNode;
        private Object secondaryLiveBackdropNode;
        private Bitmap surfacePatch;
        private Rect surfacePatchDestination;
        private Bitmap texturePatch;
        private Rect texturePatchDestination;

        private int sampleOffsetX;
        private int sampleOffsetY;
        private float inverseScaleX = 1f;
        private float inverseScaleY = 1f;
        private int configuredWidth = -1;
        private int configuredHeight = -1;
        private float interactionProgress;
        private Object api33EffectState;

        BackdropView(Context context, boolean selectionLens) {
            super(context);
            this.selectionLens = selectionLens;
            setWillNotDraw(false);
        }

        void setSnapshot(Bitmap bitmap, int sampleOffsetX, int sampleOffsetY) {
            this.snapshot = bitmap;
            this.sampleOffsetX = sampleOffsetX;
            this.sampleOffsetY = sampleOffsetY;
            invalidate();
        }

        void setLiveBackdrop(
                Object renderNode,
                int sampleOffsetX,
                int sampleOffsetY
        ) {
            this.liveBackdropNode = renderNode;
            this.sampleOffsetX = sampleOffsetX;
            this.sampleOffsetY = sampleOffsetY;
            invalidate();
        }

        void setSecondaryLiveBackdrop(Object renderNode) {
            this.secondaryLiveBackdropNode = renderNode;
            invalidate();
        }

        void setSurfacePatch(Bitmap bitmap, Rect destination) {
            surfacePatch = bitmap;
            surfacePatchDestination = destination != null ? new Rect(destination) : null;
            invalidate();
        }

        void setTexturePatch(Bitmap bitmap, Rect destination) {
            texturePatch = bitmap;
            texturePatchDestination = destination != null ? new Rect(destination) : null;
            invalidate();
        }

        void setInteractionProgress(float progress) {
            progress = Math.max(0f, Math.min(1f, progress));
            if (Math.abs(interactionProgress - progress) < 0.002f) return;
            interactionProgress = progress;
            if (getWidth() > 0 && getHeight() > 0) {
                configureEffect(getWidth(), getHeight());
            }
            invalidate();
        }

        void setSampleOffset(int offsetX, int offsetY) {
            if (sampleOffsetX == offsetX && sampleOffsetY == offsetY) return;
            sampleOffsetX = offsetX;
            sampleOffsetY = offsetY;
            invalidate();
        }

        void setInverseScale(float scaleX, float scaleY) {
            float safeX = Math.max(0.001f, Math.abs(scaleX));
            float safeY = Math.max(0.001f, Math.abs(scaleY));
            if (Math.abs(inverseScaleX - safeX) < 0.0005f
                    && Math.abs(inverseScaleY - safeY) < 0.0005f) {
                return;
            }
            inverseScaleX = safeX;
            inverseScaleY = safeY;
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

            int save = canvas.save();

            // Kyant LayerBackdrop applies the inverse layer scale before translating to the
            // captured backdrop coordinates. This keeps the scene fixed while the glass
            // capsule stretches.
            canvas.scale(
                    1f / inverseScaleX,
                    1f / inverseScaleY,
                    0f,
                    0f
            );
            canvas.translate(-sampleOffsetX, -sampleOffsetY);

            boolean drewLiveLayer = false;
            if (Build.VERSION.SDK_INT >= 29
                    && liveBackdropNode != null
                    && canvas.isHardwareAccelerated()) {
                try {
                    Api29LiveBackdrop.draw(canvas, liveBackdropNode);
                    drewLiveLayer = true;
                } catch (Throwable ignored) {
                    drewLiveLayer = false;
                }
            }

            if (!drewLiveLayer && snapshot != null && !snapshot.isRecycled()) {
                canvas.drawBitmap(snapshot, 0f, 0f, paint);
            }

            // Video patches belong to the PAGE backdrop, so composite them before the
            // hidden accent-treated tab backdrop. Crema's selector samples page + tabs in that
            // same order.
            if (surfacePatch != null
                    && !surfacePatch.isRecycled()
                    && surfacePatchDestination != null) {
                canvas.drawBitmap(
                        surfacePatch,
                        null,
                        surfacePatchDestination,
                        paint
                );
            }

            if (texturePatch != null
                    && !texturePatch.isRecycled()
                    && texturePatchDestination != null) {
                canvas.drawBitmap(
                        texturePatch,
                        null,
                        texturePatchDestination,
                        paint
                );
            }

            if (Build.VERSION.SDK_INT >= 29
                    && secondaryLiveBackdropNode != null
                    && canvas.isHardwareAccelerated()) {
                try {
                    Api29LiveBackdrop.draw(canvas, secondaryLiveBackdropNode);
                } catch (Throwable ignored) {}
            }

            canvas.restoreToCount(save);
        }

        private void configureEffect(int width, int height) {
            if (width <= 0 || height <= 0) return;
            boolean geometryChanged =
                    width != configuredWidth || height != configuredHeight;
            configuredWidth = width;
            configuredHeight = height;

            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    api33EffectState = Api33Effects.applyLiquidGlass(
                            this,
                            api33EffectState,
                            width,
                            height,
                            selectionLens,
                            interactionProgress,
                            geometryChanged
                    );
                } else if (Build.VERSION.SDK_INT >= 31 && geometryChanged) {
                    if (selectionLens) {
                        setRenderEffect(null);
                    } else {
                        Api31Effects.applyBlurAndVibrancy(
                                this,
                                dp(getContext(), 8)
                        );
                    }
                }
            } catch (Throwable t) {
                ModuleLog.line("(InstaLy | FloatingNav): RenderEffect fallback", t);
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        if (selectionLens) {
                            setRenderEffect(null);
                        } else {
                            Api31Effects.applyBlurAndVibrancy(
                                    this,
                                    dp(getContext(), 8)
                            );
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
    }


    private static final class KyantChromeView extends View {
        private final boolean selector;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clipPath = new Path();
        private RuntimeShader edgeShader;
        private RuntimeShader interactiveShader;
        private Object innerShadowNode;

        private float pressProgress;
        private float interactiveProgress;
        private float interactiveCenterX;

        KyantChromeView(Context context, boolean selector) {
            super(context);
            this.selector = selector;
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
        }

        void setPressProgress(float progress) {
            progress = Math.max(0f, Math.min(1f, progress));
            if (Math.abs(pressProgress - progress) < 0.002f) return;
            pressProgress = progress;
            if (selector) {
                setElevation(dp(getContext(), 6) * progress);
            }
            invalidate();
        }

        void setInteractiveHighlight(float progress, float centerX) {
            progress = Math.max(0f, Math.min(1f, progress));
            if (Math.abs(interactiveProgress - progress) < 0.002f
                    && Math.abs(interactiveCenterX - centerX) < 0.5f) {
                return;
            }
            interactiveProgress = progress;
            interactiveCenterX = centerX;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;

            rect.set(0f, 0f, getWidth(), getHeight());
            float radius = getHeight() / 2f;

            if (selector) {
                drawSelectorSurface(canvas, rect, radius);
            } else {
                drawInteractiveHighlight(
                        canvas,
                        rect,
                        paint,
                        interactiveShader,
                        interactiveProgress,
                        interactiveCenterX
                );
                if (Build.VERSION.SDK_INT >= 33 && interactiveShader == null) {
                    interactiveShader = new RuntimeShader(
                            INTERACTIVE_HIGHLIGHT_SHADER
                    );
                }
            }

            float edgeAlpha = selector ? pressProgress : 1f;
            if (Build.VERSION.SDK_INT >= 33 && edgeShader == null) {
                edgeShader = new RuntimeShader(
                        Api33Effects.DEFAULT_HIGHLIGHT_SHADER
                );
            }
            drawDefaultHighlight(
                    canvas,
                    rect,
                    paint,
                    edgeShader,
                    edgeAlpha,
                    getResources().getDisplayMetrics().density
            );

            if (selector && pressProgress > 0.001f) {
                innerShadowNode = drawInnerShadow(
                        canvas,
                        innerShadowNode,
                        rect,
                        pressProgress,
                        getResources().getDisplayMetrics().density
                );
            }
        }

        private void drawSelectorSurface(
                Canvas canvas,
                RectF rect,
                float radius
        ) {
            float p = pressProgress;
            boolean light = isLightTheme(getContext());

            int restAlpha = Math.round(255f * 0.10f * (1f - p));
            paint.setShader(null);
            paint.setMaskFilter(null);
            paint.setXfermode(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(
                    light
                            ? Color.argb(restAlpha, 0, 0, 0)
                            : Color.argb(restAlpha, 255, 255, 255)
            );
            canvas.drawRoundRect(rect, radius, radius, paint);

            paint.setColor(
                    Color.argb(
                            Math.round(255f * 0.03f * p),
                            0, 0, 0
                    )
            );
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        private static final String INTERACTIVE_HIGHLIGHT_SHADER = """
                uniform float2 size;
                layout(color) uniform half4 color;
                uniform float radius;
                uniform float2 position;

                half4 main(float2 coord) {
                    float dist = distance(coord, position);
                    float intensity =
                            smoothstep(radius, radius * 0.5, dist);
                    return color * intensity;
                }
                """;

        static void drawInteractiveHighlight(
                Canvas canvas,
                RectF rect,
                Paint paint,
                RuntimeShader shader,
                float progress,
                float centerX
        ) {
            progress = Math.max(0f, Math.min(1f, progress));
            if (progress <= 0.001f) return;

            float centerY = rect.centerY();
            float radius = rect.height() * 1.5f;

            paint.setShader(null);
            paint.setMaskFilter(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setXfermode(
                    new PorterDuffXfermode(PorterDuff.Mode.ADD)
            );
            paint.setColor(
                    Color.argb(
                            Math.round(255f * 0.08f * progress),
                            255, 255, 255
                    )
            );
            canvas.drawRect(rect, paint);

            if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                shader.setFloatUniform(
                        "size",
                        rect.width(),
                        rect.height()
                );
                shader.setColorUniform(
                        "color",
                        Color.argb(
                                Math.round(255f * 0.15f * progress),
                                255, 255, 255
                        )
                );
                shader.setFloatUniform("radius", radius);
                shader.setFloatUniform(
                        "position",
                        Math.max(rect.left, Math.min(rect.right, centerX)),
                        centerY
                );
                paint.setShader(shader);
                paint.setColor(Color.WHITE);
            } else {
                paint.setShader(
                        new RadialGradient(
                                centerX,
                                centerY,
                                radius,
                                Color.argb(
                                        Math.round(255f * 0.15f * progress),
                                        255, 255, 255
                                ),
                                Color.TRANSPARENT,
                                Shader.TileMode.CLAMP
                        )
                );
                paint.setColor(Color.WHITE);
            }
            canvas.drawRect(rect, paint);

            paint.setShader(null);
            paint.setXfermode(null);
        }

        static void drawDefaultHighlight(
                Canvas canvas,
                RectF rect,
                Paint paint,
                RuntimeShader shader,
                float alpha,
                float density
        ) {
            alpha = Math.max(0f, Math.min(1f, alpha));
            if (alpha <= 0.001f) return;

            float radius = rect.height() / 2f;
            float strokeWidth =
                    (float) Math.ceil(0.5f * density) * 2f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            paint.setMaskFilter(
                    new BlurMaskFilter(
                            Math.max(0.01f, 0.25f * density),
                            BlurMaskFilter.Blur.NORMAL
                    )
            );
            paint.setXfermode(
                    new PorterDuffXfermode(PorterDuff.Mode.ADD)
            );
            paint.setColor(
                    Color.argb(
                            Math.round(255f * 0.50f * alpha),
                            255, 255, 255
                    )
            );

            if (Build.VERSION.SDK_INT >= 33 && shader != null) {
                shader.setFloatUniform(
                        "size",
                        rect.width(),
                        rect.height()
                );
                shader.setFloatUniform(
                        "cornerRadii",
                        radius, radius, radius, radius
                );
                shader.setColorUniform("color", Color.WHITE);
                shader.setFloatUniform(
                        "angle",
                        (float) (Math.PI / 4.0)
                );
                shader.setFloatUniform("falloff", 1f);
                paint.setShader(shader);
            } else {
                paint.setShader(null);
            }

            clipCapsule(canvas, rect, radius, () ->
                    canvas.drawRoundRect(rect, radius, radius, paint)
            );

            paint.setShader(null);
            paint.setMaskFilter(null);
            paint.setXfermode(null);
            paint.setStyle(Paint.Style.FILL);
        }

        private static Object drawInnerShadow(
                Canvas canvas,
                Object existing,
                RectF rect,
                float progress,
                float density
        ) {
            if (Build.VERSION.SDK_INT < 31) return existing;

            float blurRadius = 8f * density * progress;
            if (blurRadius <= 0.001f) return existing;

            int width = Math.max(1, Math.round(rect.width()));
            int height = Math.max(1, Math.round(rect.height()));
            float capsuleRadius = rect.height() / 2f;

            RenderNode node = existing instanceof RenderNode
                    ? (RenderNode) existing
                    : new RenderNode("InstaLy-KyantInnerShadow");
            node.setPosition(0, 0, width, height);
            node.setAlpha(progress);
            node.setRenderEffect(
                    RenderEffect.createBlurEffect(
                            blurRadius,
                            blurRadius,
                            Shader.TileMode.DECAL
                    )
            );

            Canvas layer = node.beginRecording(width, height);
            Path clip = new Path();
            RectF local = new RectF(0f, 0f, width, height);
            clip.addRoundRect(
                    local,
                    capsuleRadius,
                    capsuleRadius,
                    Path.Direction.CW
            );
            layer.save();
            layer.clipPath(clip);

            Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
            dark.setColor(Color.argb(
                    Math.round(255f * 0.15f),
                    0, 0, 0
            ));
            layer.drawRoundRect(
                    local,
                    capsuleRadius,
                    capsuleRadius,
                    dark
            );

            Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setXfermode(
                    new PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            );
            layer.translate(0f, blurRadius);
            layer.drawRoundRect(
                    local,
                    capsuleRadius,
                    capsuleRadius,
                    clear
            );
            layer.restore();
            node.endRecording();

            canvas.save();
            Path outerClip = new Path();
            outerClip.addRoundRect(
                    rect,
                    capsuleRadius,
                    capsuleRadius,
                    Path.Direction.CW
            );
            canvas.clipPath(outerClip);
            canvas.translate(rect.left, rect.top);
            canvas.drawRenderNode(node);
            canvas.restore();
            return node;
        }

        private interface CanvasBlock {
            void run();
        }

        private static void clipCapsule(
                Canvas canvas,
                RectF rect,
                float radius,
                CanvasBlock block
        ) {
            Path path = new Path();
            path.addRoundRect(
                    rect,
                    radius,
                    radius,
                    Path.Direction.CW
            );
            int save = canvas.save();
            canvas.clipPath(path);
            block.run();
            canvas.restoreToCount(save);
        }
    }

    private static final class Api29LiveBackdrop {
        static Object record(
                Object existing,
                int width,
                int height,
                CanvasRecorder recorder
        ) {
            RenderNode node = existing instanceof RenderNode
                    ? (RenderNode) existing
                    : new RenderNode("InstaLy-LiveBackdrop");
            node.setPosition(0, 0, width, height);
            Canvas canvas = node.beginRecording(width, height);
            recorder.draw(canvas);
            node.endRecording();
            return node;
        }

        static void draw(Canvas canvas, Object renderNode) {
            canvas.drawRenderNode((RenderNode) renderNode);
        }
    }

    private static final class Api31Effects {
        static void applyHiddenTabsGlass(Object renderNode, float blurRadius) {
            if (!(renderNode instanceof RenderNode)) return;
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(1.5f);
            RenderEffect vibrancy = RenderEffect.createColorFilterEffect(
                    new ColorMatrixColorFilter(matrix)
            );
            RenderEffect blur = RenderEffect.createBlurEffect(
                    blurRadius,
                    blurRadius,
                    vibrancy,
                    Shader.TileMode.CLAMP
            );
            ((RenderNode) renderNode).setRenderEffect(blur);
        }

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

        // Exact rounded-rect optical mapping used by Kyant0/Backdrop 2.x. Crema depends on
        // Backdrop 2.0.1 directly; keep the same offset/corner-radii/depth uniforms here.
        private static final String ROUNDED_RECT_SDF = """
                float radiusAt(float2 coord, float4 radii) {
                    if (coord.x >= 0.0) {
                        if (coord.y <= 0.0) return radii.y;
                        else return radii.z;
                    } else {
                        if (coord.y <= 0.0) return radii.x;
                        else return radii.w;
                    }
                }

                float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
                    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                    float outside = length(max(cornerCoord, 0.0)) - radius;
                    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
                    return outside + inside;
                }

                float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
                    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
                    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                        return sign(coord) * normalize(max(cornerCoord, 0.0));
                    } else {
                        float gradX = step(cornerCoord.y, cornerCoord.x);
                        return sign(coord) * float2(gradX, 1.0 - gradX);
                    }
                }
                """;

        private static final String REFRACTION_SHADER = """
                uniform shader content;

                uniform float2 size;
                uniform float2 offset;
                uniform float4 cornerRadii;
                uniform float refractionHeight;
                uniform float refractionAmount;
                uniform float depthEffect;

                """ + ROUNDED_RECT_SDF + """

                float circleMap(float x) {
                    return 1.0 - sqrt(1.0 - x * x);
                }

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centeredCoord = (coord + offset) - halfSize;
                    float radius = radiusAt(coord, cornerRadii);

                    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
                    if (-sd >= refractionHeight) {
                        return content.eval(coord);
                    }
                    sd = min(sd, 0.0);

                    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
                    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 grad = normalize(
                            gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
                            + depthEffect * normalize(centeredCoord)
                    );

                    float2 refractedCoord = coord + d * grad;
                    return content.eval(refractedCoord);
                }
                """;

        private static final String DISPERSION_SHADER = """
                uniform shader content;

                uniform float2 size;
                uniform float2 offset;
                uniform float4 cornerRadii;
                uniform float refractionHeight;
                uniform float refractionAmount;
                uniform float depthEffect;
                uniform float chromaticAberration;

                """ + ROUNDED_RECT_SDF + """

                float circleMap(float x) {
                    return 1.0 - sqrt(1.0 - x * x);
                }

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centeredCoord = (coord + offset) - halfSize;
                    float radius = radiusAt(coord, cornerRadii);

                    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
                    if (-sd >= refractionHeight) {
                        return content.eval(coord);
                    }
                    sd = min(sd, 0.0);

                    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
                    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 grad = normalize(
                            gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
                            + depthEffect * normalize(centeredCoord)
                    );

                    float2 refractedCoord = coord + d * grad;
                    float dispersionIntensity = chromaticAberration
                            * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
                    float2 dispersedCoord = d * grad * dispersionIntensity;

                    half4 color = half4(0.0);

                    half4 red = content.eval(refractedCoord + dispersedCoord);
                    color.r += red.r / 3.5;
                    color.a += red.a / 7.0;

                    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
                    color.r += orange.r / 3.5;
                    color.g += orange.g / 7.0;
                    color.a += orange.a / 7.0;

                    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
                    color.r += yellow.r / 3.5;
                    color.g += yellow.g / 3.5;
                    color.a += yellow.a / 7.0;

                    half4 green = content.eval(refractedCoord);
                    color.g += green.g / 3.5;
                    color.a += green.a / 7.0;

                    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
                    color.g += cyan.g / 3.5;
                    color.b += cyan.b / 3.0;
                    color.a += cyan.a / 7.0;

                    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
                    color.b += blue.b / 3.0;
                    color.a += blue.a / 7.0;

                    half4 purple = content.eval(refractedCoord - dispersedCoord);
                    color.r += purple.r / 7.0;
                    color.b += purple.b / 3.0;
                    color.a += purple.a / 7.0;

                    return color;
                }
                """;

        private static final class EffectState {
            final RuntimeShader shader;
            final boolean selectionLens;

            EffectState(View view, boolean selectionLens) {
                this.selectionLens = selectionLens;
                shader = new RuntimeShader(
                        selectionLens ? DISPERSION_SHADER : REFRACTION_SHADER
                );

                RenderEffect lens =
                        RenderEffect.createRuntimeShaderEffect(shader, "content");

                if (selectionLens) {
                    // LiquidBottomTabs selector: combined backdrop + lens only.
                    view.setRenderEffect(lens);
                } else {
                    ColorMatrix matrix = new ColorMatrix();
                    matrix.setSaturation(1.5f);
                    RenderEffect vibrancy = RenderEffect.createColorFilterEffect(
                            new ColorMatrixColorFilter(matrix)
                    );
                    float blurRadius =
                            8f * view.getResources().getDisplayMetrics().density;
                    RenderEffect blur = RenderEffect.createBlurEffect(
                            blurRadius,
                            blurRadius,
                            vibrancy,
                            Shader.TileMode.CLAMP
                    );
                    view.setRenderEffect(
                            RenderEffect.createChainEffect(lens, blur)
                    );
                }
            }
        }

        private static final String DEFAULT_HIGHLIGHT_SHADER = """
                uniform float2 size;
                uniform float4 cornerRadii;
                layout(color) uniform half4 color;
                uniform float angle;
                uniform float falloff;

                """ + ROUNDED_RECT_SDF + """

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centeredCoord = coord - halfSize;
                    float radius = radiusAt(coord, cornerRadii);

                    float gradRadius =
                            min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 grad = gradSdRoundedRect(
                            centeredCoord,
                            halfSize,
                            gradRadius
                    );
                    float2 normal = float2(cos(angle), sin(angle));
                    float d = dot(grad, normal);
                    float intensity = pow(abs(d), falloff);
                    return color * intensity;
                }
                """;

        private static final class HiddenTabsEffectState {
            final RuntimeShader shader;
            final RenderEffect blur;
            final RenderEffect lens;
            final RenderEffect combined;

            HiddenTabsEffectState(float density) {
                shader = new RuntimeShader(REFRACTION_SHADER);

                ColorMatrix matrix = new ColorMatrix();
                matrix.setSaturation(1.5f);
                RenderEffect vibrancy = RenderEffect.createColorFilterEffect(
                        new ColorMatrixColorFilter(matrix)
                );
                float blurRadius = 8f * density;
                blur = RenderEffect.createBlurEffect(
                        blurRadius,
                        blurRadius,
                        vibrancy,
                        Shader.TileMode.CLAMP
                );
                lens = RenderEffect.createRuntimeShaderEffect(shader, "content");
                combined = RenderEffect.createChainEffect(lens, blur);
            }
        }

        static Object applyHiddenTabsGlass(
                Object renderNode,
                Object existingState,
                int width,
                int height,
                int padding,
                float density,
                float progress
        ) {
            if (!(renderNode instanceof RenderNode)) return existingState;

            HiddenTabsEffectState state =
                    existingState instanceof HiddenTabsEffectState
                            ? (HiddenTabsEffectState) existingState
                            : new HiddenTabsEffectState(density);

            progress = Math.max(0f, Math.min(1f, progress));
            float radius = height / 2f;
            state.shader.setFloatUniform("size", (float) width, (float) height);
            state.shader.setFloatUniform("offset", -padding, -padding);
            state.shader.setFloatUniform(
                    "cornerRadii",
                    radius, radius, radius, radius
            );
            state.shader.setFloatUniform(
                    "refractionHeight",
                    Math.max(0.001f, 24f * density * progress)
            );
            state.shader.setFloatUniform(
                    "refractionAmount",
                    -(24f * density * progress)
            );
            state.shader.setFloatUniform("depthEffect", 0f);

            ((RenderNode) renderNode).setRenderEffect(
                    progress > 0.0001f ? state.combined : state.blur
            );
            return state;
        }

        static Object applyLiquidGlass(
                View view,
                Object existingState,
                int width,
                int height,
                boolean selectionLens,
                float interactionProgress,
                boolean geometryChanged
        ) {
            EffectState state =
                    existingState instanceof EffectState
                            && ((EffectState) existingState).selectionLens == selectionLens
                            ? (EffectState) existingState
                            : new EffectState(view, selectionLens);

            float density = view.getResources().getDisplayMetrics().density;
            float progress = selectionLens
                    ? Math.max(0f, Math.min(1f, interactionProgress))
                    : 1f;

            float radius = height / 2f;

            float refractionHeight = selectionLens
                    ? Math.max(0.001f, 10f * progress * density)
                    : 24f * density;
            float refractionAmount = selectionLens
                    ? -(14f * progress * density)
                    : -(24f * density);

            state.shader.setFloatUniform("size", (float) width, (float) height);
            state.shader.setFloatUniform("offset", 0f, 0f);
            state.shader.setFloatUniform(
                    "cornerRadii",
                    radius, radius, radius, radius
            );
            state.shader.setFloatUniform("refractionHeight", refractionHeight);
            state.shader.setFloatUniform("refractionAmount", refractionAmount);
            state.shader.setFloatUniform("depthEffect", 0f);
            if (selectionLens) {
                state.shader.setFloatUniform("chromaticAberration", 1f);
            }

            if (geometryChanged) {
                view.invalidate();
            }
            return state;
        }
    }
}
