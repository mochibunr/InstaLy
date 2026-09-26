package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.misc.FollowStatusHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class IGNetworkInterceptor {

    private static final URI FAKE_URI = URI.create("https://127.0.0.1/404");
    private static final String FAKE_URL = "https://127.0.0.1/404";

    /**
     * Neutralise a dropped request by redirecting it to a dead local URL. IG 446+/447.0.0.39+ keeps
     * the request URL in MULTIPLE fields on the request object (e.g. a String copy plus two java.net.URI
     * copies), and the actual dispatch reads one of the copies — not necessarily the single URI field
     * we resolve for inspection. Rewriting only that one field is silently ignored, so the request
     * still goes out. Rewrite EVERY url-bearing field: every java.net.URI field, and every String
     * field that currently holds this request's URL (matched by the original value / same path).
     */
    private static int urlCarrierScore(Class<?> type) {
        int score = 0;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (URI.class.isAssignableFrom(fieldType)
                        || java.net.URL.class.isAssignableFrom(fieldType)
                        || "android.net.Uri".equals(fieldType.getName())) {
                    score += 10;
                } else if (fieldType == String.class) {
                    score += 1;
                }
            }
        }
        return score;
    }

    /**
     * Resolve Tigon's request entry point without assuming the method name survives
     * Instagram obfuscation. 449 still exposes startRequest on known builds, but the
     * structural fallback keeps the hook alive when the method is renamed.
     */
    private static Method resolveStartRequestMethod(Class<?> tigonClass) {
        Method best = null;
        int bestScore = Integer.MIN_VALUE;

        for (Method method : tigonClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 3) {
                continue;
            }

            if ("startRequest".equals(method.getName())) {
                return method;
            }

            int score = urlCarrierScore(method.getParameterTypes()[0]);
            if (score <= 0) continue;

            String methodName = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (methodName.contains("request")) score += 4;
            if (method.getReturnType() != void.class) score += 1;

            if (score > bestScore) {
                bestScore = score;
                best = method;
            }
        }

        // Require a strong URL carrier signal for an obfuscated-name fallback.
        return bestScore >= 5 ? best : null;
    }

    private static URI extractRequestUri(Object requestObj) {
        if (requestObj == null) return null;

        String stringCandidate = null;
        for (Class<?> c = requestObj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(requestObj);
                    if (value instanceof URI) {
                        return (URI) value;
                    }
                    if (value instanceof java.net.URL || value instanceof android.net.Uri) {
                        return URI.create(value.toString());
                    }
                    if (value instanceof String) {
                        String s = (String) value;
                        if (s.startsWith("https://") || s.startsWith("http://")) {
                            stringCandidate = s;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (stringCandidate != null) {
            try {
                return URI.create(stringCandidate);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Neutralise a dropped request by rewriting every URL carrier in the request
     * object, including inherited fields. Recent Instagram builds duplicate the URL
     * across URI/String fields and may dispatch from a copy different from the one
     * used for inspection.
     */
    private static void neutralizeUrlFields(Object requestObj, URI original) {
        if (requestObj == null || original == null) return;

        String orig = original.toString();
        String origPath = original.getPath();

        for (Class<?> c = requestObj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(requestObj);
                    if (value instanceof URI) {
                        field.set(requestObj, FAKE_URI);
                    } else if (value instanceof java.net.URL) {
                        field.set(requestObj, new java.net.URL(FAKE_URL));
                    } else if (value instanceof android.net.Uri) {
                        field.set(requestObj, android.net.Uri.parse(FAKE_URL));
                    } else if (value instanceof String) {
                        String s = (String) value;
                        if (s.startsWith("http")
                                && (s.equals(orig)
                                || (origPath != null && !origPath.isEmpty() && s.contains(origPath)))) {
                            field.set(requestObj, FAKE_URL);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void handleInterceptor(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader classLoader = Module.hostClassLoader != null
                    ? Module.hostClassLoader
                    : lpparam.classLoader;

            // TigonServiceLayer is a stable public class name, while its internal
            // request method may be renamed between Instagram releases.
            Class<?> tigonClass = classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method targetMethod = resolveStartRequestMethod(tigonClass);

            if (targetMethod != null) {
                targetMethod.setAccessible(true);
                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                              try {
                                Object requestObj = param.args[0];
                                URI uri = extractRequestUri(requestObj);

                                if (uri != null && uri.getPath() != null) {
                                    final String host = uri.getHost(); // null for opaque/relative URIs — guard before use
                                    boolean shouldDrop = false;


                                    // Ghost Mode URIs
                                    if (FeatureFlags.isGhostSeen) {
                                        shouldDrop |= uri.getPath().contains("/threads/") && uri.getPath().contains("/opened");
                                    }
                                    if (FeatureFlags.keepEphemeralMessages) {
                                        shouldDrop |= uri.getPath().contains("/mark_ephemeral_item_ranges_viewed");
                                    }
                                    if (FeatureFlags.isGhostScreenshot) {
                                        shouldDrop |= uri.getPath().endsWith("/screenshot/") || uri.getPath().endsWith("/ephemeral_screenshot/");
                                    }
                                    if (FeatureFlags.isGhostViewOnce) {
                                        shouldDrop |= uri.getPath().endsWith("/item_replayed/");
                                        shouldDrop |= (uri.getPath().contains("/direct") && uri.getPath().endsWith("/item_seen/"));
                                    }
                                    if (FeatureFlags.isGhostStory) {
                                        // Version-agnostic: getPath() excludes the ?reel=... query, so it
                                        // reads /api/vN/media/seen/ on both old (v2) and new (447: v1) builds.
                                        shouldDrop |= uri.getPath().contains("/media/seen/");
                                        FeatureStatusTracker.setHooked("GhostStories");
                                    }
                                    if (FeatureFlags.isGhostLive) {
                                        shouldDrop |= uri.getPath().contains("/heartbeat_and_get_viewer_count/");
                                        FeatureStatusTracker.setHooked("GhostLive");
                                    }
                                    // Remove Meta AI (#179): stop Meta AI from replying/loading.
                                    if (FeatureFlags.removeMetaAI) {
                                        String p = uri.getPath();
                                        shouldDrop |= p.contains("/ig_meta_ai_side_chat_send_contextual_query/")
                                                || p.contains("/ig_meta_ai_side_chat_new_session/")
                                                || p.contains("/create_ig_meta_ai_side_chat/")
                                                || p.contains("/genai/response")
                                                || (uri.getHost() != null && uri.getHost().contains("aistudio.instagram.com"));
                                    }

                                    // Distraction Free
                                    if (FeatureFlags.disableStories) {
                                        shouldDrop |= uri.getPath().contains("/feed/reels_tray/")
                                                || uri.getPath().contains("feed/get_latest_reel_media/")
                                                || uri.getPath().contains("direct_v2/pending_inbox/?visual_message")
                                                || uri.getPath().contains("stories/hallpass/")
                                                || uri.getPath().contains("/api/v1/feed/reels_media_stream/");
                                    }
                                    if (FeatureFlags.disableFeed) {
                                        shouldDrop |= uri.getPath().endsWith("/feed/timeline/");
                                    }
                                    // Full Disable Reels takes precedence over the except-DM exception:
                                    // when it is on, every clips endpoint is dropped — including
                                    // "Allow in DM" (disableReelsExceptDM) is the switch that controls DM
                                    // reels: when it is ON, DM-opened reels (/api/v1/clips/items/) are
                                    // allowed and only the reels feed/discover is dropped; when it is OFF,
                                    // full Disable Reels drops every clips endpoint including clips/items/.
                                    if (FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) {
                                        shouldDrop |= uri.getPath().endsWith("/qp/batch_fetch/")
                                                || uri.getPath().contains("api/v1/clips")
                                                || uri.getPath().contains("clips")
                                                || uri.getPath().contains("mixed_media")
                                                || uri.getPath().contains("mixed_media/discover/stream/");
                                    }
                                    if (FeatureFlags.disableReelsExceptDM) {
                                        if (uri.getPath().startsWith("/api/v1/direct_v2/")) {
                                            return;
                                        }
                                        shouldDrop |= (uri.getPath().startsWith("/api/v1/clips/") && uri.getQuery() != null
                                                && (uri.getQuery().contains("next_media_ids=")
                                                || uri.getQuery().contains("max_id=")))
                                                || uri.getPath().contains("/clips/discover/")
                                                || uri.getPath().contains("/mixed_media/discover/stream/");
                                    }
                                    if (FeatureFlags.disableExplore) {
                                        shouldDrop |= uri.getPath().contains("/discover/topical_explore")
                                                || uri.getPath().contains("/discover/topical_explore_stream")
                                                || (host != null && host.contains("i.instagram.com") && uri.getPath().contains("/api/v1/fbsearch/top_serp/"));
                                    }
                                    if (FeatureFlags.disableComments) {
                                        shouldDrop |= uri.getPath().contains("/api/v1/media/") && uri.getPath().contains("comments/");
                                    }

                                    // Ads
                                    if (FeatureFlags.isAdBlockEnabled) {
                                        shouldDrop |= uri.getPath().contains("profile_ads/get_profile_ads/")
                                                || uri.getPath().contains("/async_ads/")
                                                || uri.getPath().contains("/feed/injected_reels_media/")
                                                || uri.getPath().equals("/api/v1/ads/graphql/");
                                    }

                                    // Analytics
                                    if (FeatureFlags.isAnalyticsBlocked) {
                                        shouldDrop |= (host != null && (host.contains("graph.instagram.com")
                                                || host.contains("graph.facebook.com")))
                                                || uri.getPath().contains("/logging_client_events");
                                    }

                                    // Misc
                                    if (FeatureFlags.spoofLastSeen) {
                                        String p = uri.getPath();
                                        shouldDrop |= p.contains("/push/setForegroundState/")
                                                || p.contains("/accounts/update_active_status")
                                                || p.contains("/notes/create_note")
                                                || p.contains("/accounts/set_presence_disabled")
                                                || p.contains("/update_active_status")
                                                || p.contains("/banyan/banyan/")
                                                || p.endsWith("/last_active/")
                                                || p.contains("/presence/");
                                        FeatureStatusTracker.setHooked("SpoofLastSeen");
                                    }
                                    // NOTE: Disable Repost is handled at the UI/action level in
                                    // mods.ui.DisableRepostHook — NOT here. Dropping the repost network
                                    // request is ineffective because IG applies the repost optimistically
                                    // client-side, so the repost completes even when the request is dropped.
                                    if (FeatureFlags.disableDiscoverPeople) {
                                        shouldDrop |= uri.getPath().contains("/discover/ayml/");
                                        shouldDrop |= uri.getPath().contains("discover/chaining/");
                                        FeatureStatusTracker.setHooked("DisableDiscoverPeople");
                                    }

                                    if (shouldDrop) {
                                        neutralizeUrlFields(requestObj, uri);
                                    }

                                    // Follow status
                                    if (FeatureFlags.showFollowerToast) {
                                        FeatureStatusTracker.setHooked("FollowerToast");
                                        FollowStatusHook.handleRequest(uri, param.args);
                                    }
                                }
                              } catch (Throwable ignored) {
                                  // A malformed/opaque request must never crash IG's network dispatch.
                              }
                            }
                        }
                );
                ModuleLog.line("(InstaLy | Interceptor): hooked "
                        + targetMethod.getDeclaringClass().getName() + "." + targetMethod.getName()
                        + " with structural URL resolution");
            } else {
                ModuleLog.line("(InstaLy | Interceptor): could not resolve Tigon request method");
            }

        } catch (Throwable t) {
            ModuleLog.line("(InstaLy | Interceptor): failed to install", t);
        }
    }
}
