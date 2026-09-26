package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Context;
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
import android.view.WindowInsets;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Floats Instagram's own bottom tab bar and renders a live liquid-glass backdrop behind it.
 *
 * The native Instagram tab views are preserved and remain responsible for navigation, selected
 * state, accessibility and experiments. InstaLy only reparents/stylizes the visual container.
 *
 * Liquid-glass refraction math is adapted from Kyant0/AndroidLiquidGlass (Backdrop), Copyright
 * 2025 Kyant, Apache License 2.0. The original Compose implementation records a backdrop layer and
 * chains vibrancy -> blur -> RuntimeShader refraction. This native-View adaptation captures only
 * the narrow screen strip behind Instagram's tab bar and applies the same effect ordering through
 * Android RenderEffect. See THIRD_PARTY_NOTICES.md.
 */
public final class FloatingIosBottomNavHook {

    private static final String FEATURE_KEY = "FloatingIosBottomNav";
    private static final long CAPTURE_INTERVAL_MS = 33L;   // ~30 FPS, only while enabled/visible.
    private static final long SELECTION_INTERVAL_MS = 120L;
    private static final int MAX_APPLY_ATTEMPTS = 6;

    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Integer> RETRIES = new WeakHashMap<>();

    private FloatingIosBottomNavHook() {}

    public static void refresh(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            if (FeatureFlags.floatingIosBottomNavbar) {
                ensureApplied(activity, 0);
            } else {
                restore(activity);
            }
        });
    }

    private static void ensureApplied(Activity activity, int attempt) {
        if (!FeatureFlags.floatingIosBottomNavbar || activity.isFinishing()) return;

        State existing = STATES.get(activity);
        if (existing != null && existing.wrapper.getParent() != null) {
            existing.wrapper.requestCapture();
            return;
        }

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            retry(activity, attempt, "content root is not a FrameLayout");
            return;
        }
        FrameLayout root = (FrameLayout) content;

        ViewGroup bar = findBottomTabBar(activity, root);
        if (bar == null || bar.getParent() == null || bar.getWidth() <= 0 || bar.getHeight() <= 0) {
            retry(activity, attempt, "native tab bar not ready");
            return;
        }

        if (!(bar.getParent() instanceof ViewGroup)) {
            retry(activity, attempt, "tab bar parent unavailable");
            return;
        }

        ViewGroup originalParent = (ViewGroup) bar.getParent();
        int originalIndex = originalParent.indexOfChild(bar);
        ViewGroup.LayoutParams originalLayoutParams = bar.getLayoutParams();
        Drawable originalBackground = bar.getBackground();
        float originalElevation = bar.getElevation();
        int originalPaddingStart = bar.getPaddingStart();
        int originalPaddingTop = bar.getPaddingTop();
        int originalPaddingEnd = bar.getPaddingEnd();
        int originalPaddingBottom = bar.getPaddingBottom();
        int originalParentVisibility = originalParent.getVisibility();

        // Locate the compact dock that used to own the native tab row BEFORE detaching the bar.
        // Instagram 449 leaves decorative/background siblings behind, so checking childCount()==0
        // is not enough; that is the full-width dark strip visible behind the floating capsule.
        ViewGroup collapsedDock = findDockContainer(bar, root, activity);
        int collapsedDockVisibility = collapsedDock != null
                ? collapsedDock.getVisibility()
                : View.VISIBLE;

        int navInset = navigationBarInset(root);
        int sideMargin = dp(activity, 12);
        int bottomMargin = navInset + dp(activity, 12);

        originalParent.removeView(bar);
        boolean collapsedOriginalParent = false;
        if (collapsedDock != null && collapsedDock != root) {
            collapsedDock.setVisibility(View.GONE);
        } else if (originalParent != root && originalParent.getChildCount() == 0) {
            // Conservative fallback for older Instagram layouts.
            originalParent.setVisibility(View.GONE);
            collapsedOriginalParent = true;
        }

        final int nativeBarHeight = Math.max(1, bar.getHeight());
        LiquidGlassContainer wrapper = new LiquidGlassContainer(activity, root, bar);
        FrameLayout.LayoutParams wrapperLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                nativeBarHeight,
                Gravity.BOTTOM
        );
        wrapperLp.leftMargin = sideMargin;
        wrapperLp.rightMargin = sideMargin;
        wrapperLp.bottomMargin = bottomMargin;

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                nativeBarHeight,
                Gravity.CENTER
        );
        bar.setLayoutParams(barLp);
        bar.setBackground(null);
        bar.setElevation(0f);

        // Instagram commonly consumes the navigation-bar inset as bottom padding while its tab bar
        // is docked. Once floating above that inset we remove only the duplicated portion.
        if (navInset > 0 && originalPaddingBottom >= navInset) {
            bar.setPaddingRelative(
                    originalPaddingStart,
                    originalPaddingTop,
                    originalPaddingEnd,
                    originalPaddingBottom - navInset
            );
        }

        wrapper.addNativeBar(bar);
        root.addView(wrapper, wrapperLp);

        State state = new State(
                bar,
                wrapper,
                originalParent,
                originalIndex,
                originalLayoutParams,
                originalBackground,
                originalElevation,
                originalPaddingStart,
                originalPaddingTop,
                originalPaddingEnd,
                originalPaddingBottom,
                originalParentVisibility,
                collapsedOriginalParent,
                collapsedDock,
                collapsedDockVisibility
        );
        STATES.put(activity, state);
        RETRIES.remove(activity);

        FeatureStatusTracker.setHooked(FEATURE_KEY);
        ModuleLog.line("(InstaLy | FloatingNav): applied liquid glass to "
                + describeView(activity, bar)
                + ", wrapperHeight=" + nativeBarHeight + "px"
                + ", collapsedDock=" + (collapsedDock != null
                ? describeView(activity, collapsedDock)
                : "none"));

        wrapper.post(wrapper::requestCapture);
    }

    private static void retry(Activity activity, int attempt, String reason) {
        if (attempt >= MAX_APPLY_ATTEMPTS) {
            RETRIES.remove(activity);
            ModuleLog.line("(InstaLy | FloatingNav): could not locate bottom bar (" + reason + ")");
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
        }, 120L + attempt * 80L);
    }

    public static void restore(Activity activity) {
        if (activity == null) return;
        State state = STATES.remove(activity);
        RETRIES.remove(activity);
        if (state == null) return;

        try {
            state.wrapper.dispose();

            if (state.bar.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.bar.getParent()).removeView(state.bar);
            }
            if (state.wrapper.getParent() instanceof ViewGroup) {
                ((ViewGroup) state.wrapper.getParent()).removeView(state.wrapper);
            }

            state.bar.setBackground(state.originalBackground);
            state.bar.setElevation(state.originalElevation);
            state.bar.setPaddingRelative(
                    state.originalPaddingStart,
                    state.originalPaddingTop,
                    state.originalPaddingEnd,
                    state.originalPaddingBottom
            );
            state.bar.setLayoutParams(state.originalLayoutParams);

            if (state.collapsedDock != null) {
                state.collapsedDock.setVisibility(state.collapsedDockVisibility);
            } else if (state.collapsedOriginalParent) {
                state.originalParent.setVisibility(state.originalParentVisibility);
            }

            int index = Math.max(0, Math.min(state.originalIndex, state.originalParent.getChildCount()));
            state.originalParent.addView(state.bar, index, state.originalLayoutParams);
            ModuleLog.line("(InstaLy | FloatingNav): restored Instagram native bottom bar");
        } catch (Throwable t) {
            ModuleLog.line("(InstaLy | FloatingNav): restore failed", t);
        }
    }

    @SuppressWarnings("DiscouragedApi")
    private static ViewGroup findBottomTabBar(Activity activity, ViewGroup root) {
        String pkg = activity.getPackageName();

        // Fast path for resource names seen across Instagram/Meta tab implementations.
        String[] barNames = {
                "bottom_navigation", "bottom_nav", "bottom_navigation_bar",
                "tab_bar", "tab_bar_layout", "main_tab_bar", "main_tabs", "tabs_container"
        };
        for (String name : barNames) {
            int id = activity.getResources().getIdentifier(name, "id", pkg);
            if (id == 0) continue;
            View v = activity.findViewById(id);
            if (v instanceof ViewGroup && looksLikeBottomBar((ViewGroup) v, root, activity)) {
                return (ViewGroup) v;
            }
        }

        // Version-resilient path: InstaLy already relies on these stable Instagram IDs to open its
        // settings. Walk upward from an actual native tab and choose the horizontal near-bottom row.
        String[] anchorNames = {
                "search_tab", "direct_tab", "profile_tab", "reels_tab", "feed_tab", "home_tab"
        };

        View anchor = null;
        for (String name : anchorNames) {
            int id = activity.getResources().getIdentifier(name, "id", pkg);
            if (id == 0) continue;
            anchor = activity.findViewById(id);
            if (anchor != null) break;
        }
        if (anchor == null) return null;

        ViewGroup best = null;
        int bestScore = Integer.MIN_VALUE;
        int depth = 0;
        View current = anchor;
        while (current != null && current != root && depth < 9) {
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                int score = scoreBottomBar(group, root, activity) - depth;
                if (score > bestScore) {
                    bestScore = score;
                    best = group;
                }
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
            depth++;
        }

        return bestScore >= 7 ? best : null;
    }

    private static boolean looksLikeBottomBar(ViewGroup candidate, ViewGroup root, Context context) {
        return scoreBottomBar(candidate, root, context) >= 7;
    }

    private static int scoreBottomBar(ViewGroup candidate, ViewGroup root, Context context) {
        int width = candidate.getWidth();
        int height = candidate.getHeight();
        int rootWidth = root.getWidth();
        int score = 0;

        if (width > 0 && rootWidth > 0 && width >= rootWidth * 0.55f) score += 4;
        int minH = dp(context, 38);
        int maxH = dp(context, 132);
        if (height >= minH && height <= maxH) score += 4;

        int[] c = new int[2];
        int[] r = new int[2];
        try {
            candidate.getLocationInWindow(c);
            root.getLocationInWindow(r);
            int rootBottom = r[1] + root.getHeight();
            int candidateBottom = c[1] + height;
            int gap = Math.abs(rootBottom - candidateBottom);
            if (gap <= dp(context, 180)) score += 4;
        } catch (Throwable ignored) {}

        int visibleChildren = 0;
        for (int i = 0; i < candidate.getChildCount(); i++) {
            View child = candidate.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.getWidth() > 0) visibleChildren++;
        }
        if (visibleChildren >= 3 && visibleChildren <= 7) score += 3;
        if (countInteractiveDescendants(candidate, 2) >= 3) score += 2;

        try {
            int id = candidate.getId();
            if (id != View.NO_ID && id != 0) {
                String name = candidate.getResources().getResourceEntryName(id).toLowerCase();
                if (name.contains("tab") || name.contains("nav")) score += 5;
            }
        } catch (Throwable ignored) {}

        return score;
    }

    private static ViewGroup findDockContainer(
            ViewGroup bar,
            FrameLayout root,
            Context context
    ) {
        int barHeight = Math.max(1, bar.getHeight());
        int rootWidth = root.getWidth();
        int[] rootLocation = new int[2];
        try { root.getLocationInWindow(rootLocation); } catch (Throwable ignored) {}
        int rootBottom = rootLocation[1] + root.getHeight();

        ViewGroup best = null;
        View current = bar.getParent() instanceof View ? (View) bar.getParent() : null;
        int depth = 0;
        while (current instanceof ViewGroup && current != root && depth < 5) {
            ViewGroup group = (ViewGroup) current;
            int h = group.getHeight();
            int w = group.getWidth();
            int[] location = new int[2];
            try { group.getLocationInWindow(location); } catch (Throwable ignored) {}

            int bottomGap = Math.abs(rootBottom - (location[1] + h));
            boolean compactHeight = h > 0
                    && h <= barHeight + dp(context, 80)
                    && h >= Math.max(dp(context, 36), barHeight / 2);
            boolean wideEnough = rootWidth <= 0 || w >= rootWidth * 0.70f;
            boolean nearBottom = bottomGap <= dp(context, 200);

            if (compactHeight && wideEnough && nearBottom) {
                // Keep walking and prefer the outermost compact dock. That catches Instagram's
                // divider/background wrapper as well as the immediate tab parent.
                best = group;
            }

            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
            depth++;
        }
        return best;
    }

    private static int countInteractiveDescendants(ViewGroup group, int depth) {
        if (depth < 0) return 0;
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.isClickable() || child.isFocusable() || child.getContentDescription() != null) {
                count++;
            }
            if (child instanceof ViewGroup && depth > 0) {
                count += countInteractiveDescendants((ViewGroup) child, depth - 1);
            }
        }
        return count;
    }

    private static int navigationBarInset(View view) {
        try {
            WindowInsets insets = view.getRootWindowInsets();
            if (insets == null) return 0;
            if (Build.VERSION.SDK_INT >= 30) {
                return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            return insets.getStableInsetBottom();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String describeView(Context context, View view) {
        String idName = "no-id";
        try {
            if (view.getId() != 0 && view.getId() != View.NO_ID) {
                idName = context.getResources().getResourceEntryName(view.getId());
            }
        } catch (Throwable ignored) {}
        return view.getClass().getName() + "#" + idName
                + " (" + view.getWidth() + "x" + view.getHeight() + ")";
    }

    private static boolean isLightTheme(Context context) {
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    private static final class State {
        final ViewGroup bar;
        final LiquidGlassContainer wrapper;
        final ViewGroup originalParent;
        final int originalIndex;
        final ViewGroup.LayoutParams originalLayoutParams;
        final Drawable originalBackground;
        final float originalElevation;
        final int originalPaddingStart;
        final int originalPaddingTop;
        final int originalPaddingEnd;
        final int originalPaddingBottom;
        final int originalParentVisibility;
        final boolean collapsedOriginalParent;
        final ViewGroup collapsedDock;
        final int collapsedDockVisibility;

        State(
                ViewGroup bar,
                LiquidGlassContainer wrapper,
                ViewGroup originalParent,
                int originalIndex,
                ViewGroup.LayoutParams originalLayoutParams,
                Drawable originalBackground,
                float originalElevation,
                int originalPaddingStart,
                int originalPaddingTop,
                int originalPaddingEnd,
                int originalPaddingBottom,
                int originalParentVisibility,
                boolean collapsedOriginalParent,
                ViewGroup collapsedDock,
                int collapsedDockVisibility
        ) {
            this.bar = bar;
            this.wrapper = wrapper;
            this.originalParent = originalParent;
            this.originalIndex = originalIndex;
            this.originalLayoutParams = originalLayoutParams;
            this.originalBackground = originalBackground;
            this.originalElevation = originalElevation;
            this.originalPaddingStart = originalPaddingStart;
            this.originalPaddingTop = originalPaddingTop;
            this.originalPaddingEnd = originalPaddingEnd;
            this.originalPaddingBottom = originalPaddingBottom;
            this.originalParentVisibility = originalParentVisibility;
            this.collapsedOriginalParent = collapsedOriginalParent;
            this.collapsedDock = collapsedDock;
            this.collapsedDockVisibility = collapsedDockVisibility;
        }
    }

    private static final class LiquidGlassContainer extends FrameLayout {
        private final FrameLayout captureRoot;
        private final ViewGroup nativeBar;
        private final BackdropView backdropView;
        private final View surfaceTint;
        private final BackdropView selectionLens;
        private final float radiusPx;
        private final ViewTreeObserver.OnPreDrawListener preDrawListener;

        private Bitmap snapshot;
        private long lastCaptureAt;
        private long lastSelectionAt;
        private boolean listenerAttached;

        LiquidGlassContainer(Context context, FrameLayout captureRoot, ViewGroup nativeBar) {
            super(context);
            this.captureRoot = captureRoot;
            this.nativeBar = nativeBar;
            this.radiusPx = dp(context, 30);

            setClipChildren(false);
            setClipToPadding(false);
            setElevation(dp(context, 8));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            setClipToOutline(true);

            backdropView = new BackdropView(context, false);
            addView(backdropView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            surfaceTint = new View(context);
            GradientDrawable surface = new GradientDrawable();
            surface.setShape(GradientDrawable.RECTANGLE);
            surface.setCornerRadius(radiusPx);
            surface.setColor(isLightTheme(context) ? 0x66FAFAFA : 0x66121212);
            surfaceTint.setBackground(surface);
            addView(surfaceTint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            selectionLens = new BackdropView(context, true);
            selectionLens.setVisibility(View.INVISIBLE);
            selectionLens.setAlpha(0f);
            selectionLens.setElevation(dp(context, 3));
            selectionLens.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
            selectionLens.setClipToOutline(true);
            GradientDrawable lensStroke = new GradientDrawable();
            lensStroke.setColor(Color.TRANSPARENT);
            lensStroke.setCornerRadius(dp(context, 32));
            lensStroke.setStroke(dp(context, 1), isLightTheme(context) ? 0x26000000 : 0x38FFFFFF);
            selectionLens.setForeground(lensStroke);
            addView(selectionLens, new FrameLayout.LayoutParams(1, 1));

            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.TRANSPARENT);
            border.setCornerRadius(radiusPx);
            border.setStroke(dp(context, 1), isLightTheme(context) ? 0x30000000 : 0x55FFFFFF);
            setForeground(border);

            preDrawListener = () -> {
                boolean nativeVisible = nativeBar.getVisibility() == View.VISIBLE
                        && nativeBar.getAlpha() > 0.01f;
                int wantedVisibility = nativeVisible ? View.VISIBLE : View.INVISIBLE;
                if (getVisibility() != wantedVisibility) {
                    setVisibility(wantedVisibility);
                }

                long now = SystemClock.uptimeMillis();
                if (nativeVisible && getAlpha() > 0f
                        && now - lastCaptureAt >= CAPTURE_INTERVAL_MS) {
                    lastCaptureAt = now;
                    captureBackdrop();
                }
                if (now - lastSelectionAt >= SELECTION_INTERVAL_MS) {
                    lastSelectionAt = now;
                    updateSelectionLens();
                }
                return true;
            };
        }

        void addNativeBar(ViewGroup bar) {
            // The outer container is explicitly locked to Instagram's already-measured native
            // bar height. MATCH_PARENT here is therefore safe and prevents the background glass
            // layers from forcing a WRAP_CONTENT FrameLayout to expand to the full viewport.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            );
            addView(bar, lp);
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
                updateSelectionLens();
            });
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
                if (observer.isAlive()) observer.removeOnPreDrawListener(preDrawListener);
            } catch (Throwable ignored) {}
            listenerAttached = false;
        }

        private void captureBackdrop() {
            int width = getWidth();
            int height = getHeight();
            if (width <= 1 || height <= 1 || !isAttachedToWindow()) return;

            try {
                if (snapshot == null || snapshot.getWidth() != width || snapshot.getHeight() != height) {
                    if (snapshot != null) snapshot.recycle();
                    snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                } else {
                    snapshot.eraseColor(Color.TRANSPARENT);
                }

                Canvas canvas = new Canvas(snapshot);
                int[] wrapperLocation = new int[2];
                getLocationInWindow(wrapperLocation);

                Drawable rootBackground = captureRoot.getBackground();
                if (rootBackground != null) {
                    int[] rootLocation = new int[2];
                    captureRoot.getLocationInWindow(rootLocation);
                    int save = canvas.save();
                    canvas.translate(
                            rootLocation[0] - wrapperLocation[0],
                            rootLocation[1] - wrapperLocation[1]
                    );
                    rootBackground.draw(canvas);
                    canvas.restoreToCount(save);
                } else {
                    canvas.drawColor(isLightTheme(getContext()) ? Color.WHITE : Color.BLACK);
                }

                int[] childLocation = new int[2];
                for (int i = 0; i < captureRoot.getChildCount(); i++) {
                    View child = captureRoot.getChildAt(i);
                    if (child == this || child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) {
                        continue;
                    }
                    child.getLocationInWindow(childLocation);
                    int save = canvas.save();
                    canvas.translate(
                            childLocation[0] - wrapperLocation[0],
                            childLocation[1] - wrapperLocation[1]
                    );
                    child.draw(canvas);
                    canvas.restoreToCount(save);
                }

                backdropView.setSnapshot(snapshot, 0);
                selectionLens.setSnapshot(snapshot, Math.round(selectionLens.getX()));
            } catch (Throwable t) {
                ModuleLog.line("(InstaLy | FloatingNav): backdrop capture failed", t);
            }
        }

        private void updateSelectionLens() {
            ViewGroup tabGroup = findBestTabGroup(nativeBar);
            if (tabGroup == null) {
                hideLens();
                return;
            }

            View selected = null;
            for (int i = 0; i < tabGroup.getChildCount(); i++) {
                View child = tabGroup.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (hasSelectedState(child)) {
                    selected = child;
                    break;
                }
            }
            if (selected == null) {
                hideLens();
                return;
            }

            int[] selectedLocation = new int[2];
            int[] wrapperLocation = new int[2];
            selected.getLocationInWindow(selectedLocation);
            getLocationInWindow(wrapperLocation);

            int left = selectedLocation[0] - wrapperLocation[0];
            int top = selectedLocation[1] - wrapperLocation[1];
            int width = selected.getWidth();
            int height = selected.getHeight();
            int verticalInset = dp(getContext(), 4);

            if (width <= 0 || height <= 0) {
                hideLens();
                return;
            }

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) selectionLens.getLayoutParams();
            lp.width = width;
            lp.height = Math.max(dp(getContext(), 42), height - verticalInset * 2);
            lp.leftMargin = 0;
            lp.topMargin = Math.max(0, top + verticalInset);
            selectionLens.setLayoutParams(lp);
            selectionLens.setSnapshot(snapshot, left);

            if (selectionLens.getVisibility() != View.VISIBLE) {
                selectionLens.setX(left);
                selectionLens.setAlpha(0f);
                selectionLens.setVisibility(View.VISIBLE);
                selectionLens.animate().alpha(1f).setDuration(150L).start();
            } else {
                selectionLens.animate()
                        .x(left)
                        .alpha(1f)
                        .setDuration(180L)
                        .start();
            }
        }

        private void hideLens() {
            if (selectionLens.getVisibility() == View.VISIBLE) {
                selectionLens.animate().alpha(0f).setDuration(120L).withEndAction(() ->
                        selectionLens.setVisibility(View.INVISIBLE)
                ).start();
            }
        }

        private static ViewGroup findBestTabGroup(ViewGroup root) {
            List<ViewGroup> groups = new ArrayList<>();
            collectGroups(root, groups, 0);
            ViewGroup best = null;
            int bestScore = Integer.MIN_VALUE;

            for (ViewGroup group : groups) {
                int visible = 0;
                int selected = 0;
                int previousCenter = Integer.MIN_VALUE;
                boolean ordered = true;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) continue;
                    visible++;
                    if (hasSelectedState(child)) selected++;
                    int center = child.getLeft() + child.getWidth() / 2;
                    if (center <= previousCenter) ordered = false;
                    previousCenter = center;
                }
                if (visible < 3 || visible > 7 || !ordered) continue;

                int score = visible * 2;
                if (selected == 1) score += 8;
                if (group.getWidth() >= root.getWidth() * 0.7f) score += 4;
                if (score > bestScore) {
                    bestScore = score;
                    best = group;
                }
            }
            return best;
        }

        private static void collectGroups(View view, List<ViewGroup> out, int depth) {
            if (!(view instanceof ViewGroup) || depth > 4) return;
            ViewGroup group = (ViewGroup) view;
            out.add(group);
            for (int i = 0; i < group.getChildCount(); i++) {
                collectGroups(group.getChildAt(i), out, depth + 1);
            }
        }

        private static boolean hasSelectedState(View view) {
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
    }

    private static final class BackdropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final boolean selectionLens;
        private Bitmap snapshot;
        private int sampleOffsetX;
        private int configuredWidth = -1;
        private int configuredHeight = -1;

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
                    Api33Effects.applyLiquidGlass(this, width, height, selectionLens);
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

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centered = coord - halfSize;
                    float sd = sdRoundedRect(centered, halfSize, radius);
                    if (-sd >= refractionHeight) {
                        return content.eval(coord);
                    }
                    sd = min(sd, 0.0);
                    float d = circleMap(1.0 - (-sd / refractionHeight)) * refractionAmount;
                    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
                    float2 grad = normalize(gradSdRoundedRect(centered, halfSize, gradRadius));
                    return content.eval(coord + d * grad);
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

                half4 main(float2 coord) {
                    float2 halfSize = size * 0.5;
                    float2 centered = coord - halfSize;
                    float sd = sdRoundedRect(centered, halfSize, radius);
                    if (-sd >= refractionHeight) {
                        return content.eval(coord);
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
                    half4 red = content.eval(refracted + dispersed);
                    color.r += red.r / 3.5; color.a += red.a / 7.0;
                    half4 orange = content.eval(refracted + dispersed * 0.6667);
                    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
                    half4 yellow = content.eval(refracted + dispersed * 0.3333);
                    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
                    half4 green = content.eval(refracted);
                    color.g += green.g / 3.5; color.a += green.a / 7.0;
                    half4 cyan = content.eval(refracted - dispersed * 0.3333);
                    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
                    half4 blue = content.eval(refracted - dispersed * 0.6667);
                    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
                    half4 purple = content.eval(refracted - dispersed);
                    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
                    return color;
                }
                """;

        static void applyLiquidGlass(View view, int width, int height, boolean selectionLens) {
            float density = view.getResources().getDisplayMetrics().density;
            float blurRadius = 8f * density;
            float radius = selectionLens ? height / 2f : Math.min(30f * density, height / 2f);
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
