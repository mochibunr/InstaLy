package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Forces Instagram's native high-quality media-upload preference when enabled.
 *
 * Recent Instagram builds keep this state behind an obfuscated, zero-argument
 * boolean getter that reads the stable preference key "high_quality_media_upload".
 * Resolving by that string survives ordinary X.* class/method renames and uses
 * Instagram's own photo/video transcoding path instead of replacing media bytes.
 *
 * Instagram's server may still impose final dimensions/bitrate limits.
 */
public final class OriginalUploadQualityHook {

    private static final String CACHE_KEY = "OriginalUploadQuality_getter";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook forceHighQuality = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (FeatureFlags.originalUploadQuality) {
                    param.setResult(true);
                    FeatureStatusTracker.setHooked("OriginalUploadQuality");
                }
            }
        };

        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods(CACHE_KEY, classLoader);
            if (cached != null && !cached.isEmpty()) {
                int hooked = 0;
                for (Method method : cached) {
                    try {
                        method.setAccessible(true);
                        XposedBridge.hookMethod(method, forceHighQuality);
                        hooked++;
                    } catch (Throwable ignored) {}
                }
                if (hooked > 0) {
                    ModuleLog.line("(InstaLy | UploadQuality): hooked " + hooked + " cached quality gate(s)");
                    return;
                }
            }
        }

        if (bridge == null) {
            ModuleLog.line("(InstaLy | UploadQuality): DexKit unavailable; quality gate not installed");
            return;
        }

        try {
            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("high_quality_media_upload")
                            .paramCount(0)
                            .returnType("boolean")));

            if (results.isEmpty()) {
                ModuleLog.line("(InstaLy | UploadQuality): high_quality_media_upload getter not found");
                if (FeatureFlags.originalUploadQuality) {
                    FeatureStatusTracker.setBroken("OriginalUploadQuality");
                }
                return;
            }

            List<Method> hookedMethods = new ArrayList<>();
            for (MethodData data : results) {
                try {
                    Method method = data.getMethodInstance(classLoader);
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, forceHighQuality);
                    hookedMethods.add(method);
                    if (hookedMethods.size() >= 4) break;
                } catch (Throwable ignored) {}
            }

            if (hookedMethods.isEmpty()) {
                ModuleLog.line("(InstaLy | UploadQuality): candidates found but none were hookable");
                if (FeatureFlags.originalUploadQuality) {
                    FeatureStatusTracker.setBroken("OriginalUploadQuality");
                }
                return;
            }

            DexKitCache.saveMethods(CACHE_KEY, hookedMethods);
            ModuleLog.line("(InstaLy | UploadQuality): hooked "
                    + hookedMethods.size() + " native high-quality upload gate(s)");
            FeatureStatusTracker.setHooked("OriginalUploadQuality");
        } catch (Throwable t) {
            ModuleLog.line("(InstaLy | UploadQuality): discovery failed", t);
            if (FeatureFlags.originalUploadQuality) {
                FeatureStatusTracker.setBroken("OriginalUploadQuality");
            }
        }
    }
}
