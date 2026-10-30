// HyperGestures: recents' haptics as on stock HyperOS (docs/research/
// 2026-10-09-miuihome-recents-features.md §1 step 8 and §4c): MiuiHome's HapticFeedbackCompat
// plays Clear all as ext effect 213 on haptic v2 phones (ours: sys.haptic.version 2.0), 90 on
// linear-motor v1 ones, and locking or unlocking a card as 206 / 207 on v2. Played through
// miui.util.HapticFeedbackUtil found by reflection, as the back arrow's (HgBackHaptics), and
// only while the system's haptic feedback setting is on (HgBackHaptics.touchFeedbackOn). Where
// HyperOS has no such effect: Clear all plays nothing (as MiuiHome on such phones), a lock
// toggle the view's CONFIRM tick it played before. All on UI_HELPER_EXECUTOR (binder calls).
package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.android.quickstep.interaction.HgBackHaptics;

import java.lang.reflect.Method;

public final class HgRecentsHaptics {
    private static final String TAG = "HgRecentsHaptics";
    private static final String UTIL_CLASS = "miui.util.HapticFeedbackUtil";

    static final int CLEAR_ALL_V2 = 213;
    static final int CLEAR_ALL_LINEAR = 90;
    static final int LOCK_V2 = 206;
    static final int UNLOCK_V2 = 207;

    // Resolved once per process on UI_HELPER_EXECUTOR; sPerform == null: not available.
    private static boolean sResolved;
    private static Object sUtil;
    private static Method sPerform;
    private static Method sSupported;
    private static String sVersion = "";

    private HgRecentsHaptics() {}

    /** Clear all was tapped and will run. */
    public static void playClearAll(Context context) {
        Context app = context.getApplicationContext();
        UI_HELPER_EXECUTOR.execute(() -> {
            if (!feedbackOn(app)) return;
            resolve(app);
            int id = clearAllEffect(sVersion);
            if (id >= 0) perform(id);
        });
    }

    /** A card was locked ({@code locked}) or unlocked; {@code view} plays the fallback tick. */
    static void playLock(View view, boolean locked) {
        Context app = view.getContext().getApplicationContext();
        UI_HELPER_EXECUTOR.execute(() -> {
            if (!feedbackOn(app)) return;
            resolve(app);
            int id = lockEffect(sVersion, locked);
            if (id >= 0 && perform(id)) return;
            MAIN_EXECUTOR.execute(() ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM));
        });
    }

    /**
     * MiuiHome's Clear all effect for {@code hapticVersion} (sys.haptic.version); a phone whose
     * vibrator does not support it plays nothing (perform checks).
     */
    static int clearAllEffect(String hapticVersion) {
        return "2.0".equals(hapticVersion) ? CLEAR_ALL_V2 : CLEAR_ALL_LINEAR;
    }

    /** MiuiHome's lock (or unlock) effect for {@code hapticVersion}; -1: none (v2 only). */
    static int lockEffect(String hapticVersion, boolean locked) {
        if (!"2.0".equals(hapticVersion)) return -1;
        return locked ? LOCK_V2 : UNLOCK_V2;
    }

    private static boolean feedbackOn(Context app) {
        try {
            return HgBackHaptics.touchFeedbackOn(
                    Settings.System.getString(app.getContentResolver(),
                            HgBackHaptics.KEY_DISABLE),
                    Settings.System.getString(app.getContentResolver(),
                            HgBackHaptics.KEY_ENABLED));
        } catch (RuntimeException e) {
            return true; // unreadable counts as on, as for back
        }
    }

    /** Plays ext effect {@code id} if this phone supports it; false if not or it failed. */
    private static boolean perform(int id) {
        if (sPerform == null) return false;
        try {
            if (!(Boolean) sSupported.invoke(sUtil, id)) return false;
            sPerform.invoke(sUtil, id);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "HyperOS haptic " + id + " failed", e);
            return false;
        }
    }

    private static void resolve(Context app) {
        if (sResolved) return;
        sResolved = true;
        sVersion = SystemProperties.get("sys.haptic.version", "");
        try {
            Class<?> c = Class.forName(UTIL_CLASS);
            // onceOnly = true: no settings observer; feedbackOn checks the switch itself.
            sUtil = c.getConstructor(Context.class, boolean.class).newInstance(app, true);
            sSupported = c.getMethod("isSupportExtHapticFeedback", int.class);
            sPerform = c.getMethod("performExtHapticFeedback", int.class);
            Log.i(TAG, "recents haptics ready, haptic version " + sVersion);
        } catch (Throwable t) {
            sPerform = null;
            Log.i(TAG, "HyperOS haptics not available for recents: " + t);
        }
    }
}
