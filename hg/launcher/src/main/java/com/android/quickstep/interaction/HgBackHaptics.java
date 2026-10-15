// HyperGestures: the back arrow's haptics as on stock HyperOS (plan ruling R5; docs/research/
// 2026-10-09-miuihome-recents-features.md §4). MiuiHome's timing (HgBackHapticTimer) and its
// effects, played through miui.util.HapticFeedbackUtil found by reflection; AOSP's
// VibratorWrapper click where the class or the effect is missing. Used for both arrow styles; the
// only switch is the system's haptic feedback setting, read once per gesture at touch-down and
// checked before every tick on either path (touchFeedbackOn): off, nothing plays. HyperOS's
// toggle most likely writes Settings.System haptic_feedback_disable (0 = haptics on; the owner's
// phone reports haptic_feedback_enabled = 0 while back vibrates), so that key decides when it
// holds an integer, else AOSP's haptic_feedback_enabled (not 0 = on). The log zip's info.txt
// lists both, with haptic_feedback_level and _intensity, to confirm which key the toggle flips.
// The paths have checks of their own too: VibratorWrapper plays only while
// haptic_feedback_enabled is 1, and HapticFeedbackUtil returns early when its isHapticsDisable()
// says so. (The page's "Back vibration" switch, Global hypergestures_back_haptics, is gone: off
// only swapped these ticks for AOSP's clicks.)
package com.android.quickstep.interaction;

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.content.Context;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.launcher3.util.VibratorWrapper;

import java.lang.reflect.Method;
import java.util.function.IntPredicate;

final class HgBackHaptics {
    private static final String TAG = "HgBackHaptics";
    private static final String UTIL_CLASS = "miui.util.HapticFeedbackUtil";

    /**
     * MiuiHome's HapticFeedbackCompatV2 (sys.haptic.version 2.0, as on our phone) passes 0 for
     * both ticks. 0 is VibrationEffect.EFFECT_CLICK's id, played through Xiaomi's vibrateExt.
     */
    static final int V2_EFFECT = 0;
    /** HapticFeedbackCompatLinear's back "ready" and "hand-up" ext effects. */
    static final int LINEAR_READY = 162;
    static final int LINEAR_HAND_UP = 163;

    // Resolved once per process, on UI_HELPER_EXECUTOR (the support check is a vibrator binder
    // call). sUtil == null after resolving: HyperOS haptics unavailable, VibratorWrapper only.
    private static boolean sResolved;
    private static Object sUtil;
    private static Method sPerform;
    /** {ready, hand-up} ext effect ids; -1 = not supported, use VibratorWrapper. */
    private static int[] sIds = {-1, -1};

    /** Settings.System keys of the haptic feedback setting: HyperOS's, then AOSP's. */
    static final String KEY_DISABLE = "haptic_feedback_disable";
    static final String KEY_ENABLED = Settings.System.HAPTIC_FEEDBACK_ENABLED;

    private final Context mContext;
    private final HgBackHapticTimer mTimer = new HgBackHapticTimer();
    /**
     * This gesture's haptic feedback setting, read on UI_HELPER_EXECUTOR at touch-down. That
     * executor is serial, so the read lands before any tick of the same gesture.
     */
    private volatile boolean mFeedbackOn = true;

    HgBackHaptics(Context context) {
        mContext = context;
        // Resolve ahead so the first tick of the first gesture is not late.
        Context app = context.getApplicationContext();
        UI_HELPER_EXECUTOR.execute(() -> resolve(app));
    }

    /** A back touch-down: a new gesture; reads the haptic feedback setting once for it. */
    void onDown() {
        mTimer.reset();
        Context app = mContext.getApplicationContext();
        UI_HELPER_EXECUTOR.execute(() -> mFeedbackOn = touchFeedbackOn(app));
    }

    /**
     * The finger moved travelPx sideways from the touch-down; armed if a release now triggers
     * back. The ready tick plays once per gesture, past HgBackHapticTimer.READY_TRAVEL_PX.
     */
    void onMove(float travelPx, boolean armed) {
        if (mTimer.onMove(travelPx, armed, SystemClock.uptimeMillis())) play(true);
    }

    /** The release triggered back: the hand-up tick, unless the ready tick was < 140 ms ago. */
    void onBackTriggered() {
        if (mTimer.onBackTriggered(SystemClock.uptimeMillis())) play(false);
    }

    private void play(boolean ready) {
        Context app = mContext.getApplicationContext();
        VibratorWrapper fallback = VibratorWrapper.INSTANCE.get(mContext);
        UI_HELPER_EXECUTOR.execute(() -> {
            if (!mFeedbackOn) return; // either path: nothing while haptic feedback is off
            resolve(app);
            int id = sIds[ready ? 0 : 1];
            if (id >= 0) {
                try {
                    sPerform.invoke(sUtil, id);
                    return;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Log.w(TAG, "HyperOS haptic " + id + " failed; using VibratorWrapper", e);
                    sIds = new int[] {-1, -1};
                }
            }
            fallback.vibrate(VibratorWrapper.EFFECT_CLICK);
        });
    }

    /** The system's haptic feedback setting, read now; unreadable counts as on. */
    private static boolean touchFeedbackOn(Context app) {
        String disable = null;
        String enabled = null;
        try {
            disable = Settings.System.getString(app.getContentResolver(), KEY_DISABLE);
            enabled = Settings.System.getString(app.getContentResolver(), KEY_ENABLED);
        } catch (RuntimeException e) {
            Log.w(TAG, "haptic feedback setting unreadable; back ticks on: " + e);
        }
        return touchFeedbackOn(disable, enabled);
    }

    /**
     * Whether the back ticks play, from the Settings.System values (null when unset) of
     * haptic_feedback_disable (HyperOS) and haptic_feedback_enabled (AOSP). HyperOS's key
     * decides when it holds an integer: on only when it is 0. Otherwise AOSP's: on unless it is
     * 0; unset or not an integer counts as on (AOSP's default).
     */
    static boolean touchFeedbackOn(String hapticFeedbackDisable, String hapticFeedbackEnabled) {
        Integer disable = parseInt(hapticFeedbackDisable);
        if (disable != null) return disable == 0;
        Integer enabled = parseInt(hapticFeedbackEnabled);
        return enabled == null || enabled != 0;
    }

    private static Integer parseInt(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void resolve(Context app) {
        if (sResolved) return;
        sResolved = true;
        try {
            Class<?> c = Class.forName(UTIL_CLASS);
            // onceOnly = true: no settings observer (on linear-motor phones it registers none
            // anyway); play() checks the touch feedback switch itself.
            Object util = c.getConstructor(Context.class, boolean.class).newInstance(app, true);
            Method supported = c.getMethod("isSupportExtHapticFeedback", int.class);
            Method perform = c.getMethod("performExtHapticFeedback", int.class);
            int[] ids = effectIds(SystemProperties.get("sys.haptic.version", ""), id -> {
                try {
                    return (Boolean) supported.invoke(util, id);
                } catch (ReflectiveOperationException e) {
                    return false;
                }
            });
            if (ids[0] >= 0 || ids[1] >= 0) {
                sUtil = util;
                sPerform = perform;
                sIds = ids;
            }
            Log.i(TAG, "back haptics: ready " + ids[0] + ", hand-up " + ids[1]
                    + " (-1 = VibratorWrapper click)");
        } catch (Throwable t) {
            Log.i(TAG, "HyperOS haptics not available; back uses VibratorWrapper: " + t);
        }
    }

    /**
     * The {ready, hand-up} ext effect ids MiuiHome's HapticFeedbackCompat would play here, each -1
     * if this device's vibrator does not support it (the caller then plays VibratorWrapper's
     * click). Haptic v2 (sys.haptic.version "2.0") uses 0 for both; otherwise the linear-motor
     * pair 162/163, which MiuiHome uses only when both are supported.
     */
    static int[] effectIds(String hapticVersion, IntPredicate supported) {
        if ("2.0".equals(hapticVersion)) {
            int id = supported.test(V2_EFFECT) ? V2_EFFECT : -1;
            return new int[] {id, id};
        }
        if (supported.test(LINEAR_READY) && supported.test(LINEAR_HAND_UP)) {
            return new int[] {LINEAR_READY, LINEAR_HAND_UP};
        }
        return new int[] {-1, -1};
    }
}
