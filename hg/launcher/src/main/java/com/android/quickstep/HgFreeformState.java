// HyperGestures: tells HyperOS's Shell that recents or the home screen took over, so open floating
// windows shrink to mini windows, as with MiuiHome (owner, 2026-10-07: the floating window stayed
// live on top of recents; 2026-10-08: as close as possible to the system default).
//
// HyperOS has no Shell call for this: MiuiHome sends a plain broadcast,
// Intent("com.miui.fullscreen_state_change") with "state" = "toRecents" or "toHome" and "channel"
// = "gesture" or "navigation_bar". Shell's MiuiFreeformModeSettingsObserver receiver (exported, no
// sender permission) then calls MiuiFreeformModeController.allFreeformFromFreeformToMini(). Nothing
// restores them afterwards: a mini window stays until tapped, and tapping its recents card brings
// it back floating (TaskView keeps the task's windowing mode; HgSystemSplitLaunch). The HOME key
// already sends "toHome" from system_server; nobody sent "toRecents" since MiuiHome is gone.
// Sent from a background thread, only where HyperOS's floating windows exist; a repeat of the same
// state within DEDUPE_MS (the gesture's settle and recents' DEFAULT state) is dropped.
// Off: Settings.Global hypergestures_floating_shrink=0 (floating windows stay as they are).
package com.android.quickstep;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.android.launcher3.util.Executors;

public final class HgFreeformState {
    private HgFreeformState() {}

    private static final String TAG = "HgFloating";
    static final String ACTION = "com.miui.fullscreen_state_change";
    static final String TO_RECENTS = "toRecents";
    static final String TO_HOME = "toHome";
    static final String CHANNEL_GESTURE = "gesture";
    static final String CHANNEL_NAV_BAR = "navigation_bar";
    static final long DEDUPE_MS = 1000;

    private static String sLastState;
    private static long sLastAt;

    /** Recents is showing (the gesture settled there, or the recents key opened it). */
    public static void toRecents(Context context, boolean fromGesture) {
        send(context, TO_RECENTS, fromGesture ? CHANNEL_GESTURE : CHANNEL_NAV_BAR);
    }

    /** A gesture ended on the home screen. */
    public static void toHome(Context context) {
        send(context, TO_HOME, CHANNEL_GESTURE);
    }

    /**
     * A new gesture began, or recents stopped: the next state is a new entry and is always sent
     * (a quick tap on a floating card and swipe back must not be taken for a repeat).
     */
    public static synchronized void resetDedupe() {
        sLastState = null;
    }

    /** Whether {@code state} at {@code now} repeats the last one sent within DEDUPE_MS. */
    static boolean isRepeat(String state, long now, String lastState, long lastAt) {
        return state.equals(lastState) && now - lastAt < DEDUPE_MS;
    }

    private static synchronized boolean claim(String state) {
        long now = SystemClock.uptimeMillis();
        if (isRepeat(state, now, sLastState, sLastAt)) return false;
        sLastState = state;
        sLastAt = now;
        return true;
    }

    private static void send(Context context, String state, String channel) {
        if (!HgRecentsSettings.isOn(context, HgRecentsSettings.FLOATING_SHRINK)) return;
        if (!claim(state)) return;
        Context app = context.getApplicationContext();
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            if (!HgFloatingWindow.systemSupports()) return;
            try {
                app.sendBroadcast(new Intent(ACTION)
                        .putExtra("state", state)
                        .putExtra("channel", channel));
                Log.d(TAG, "floating windows: " + state + " (" + channel + ")");
            } catch (RuntimeException e) {
                Log.w(TAG, "floating windows: " + state + " not sent: " + e);
            }
        });
    }
}
