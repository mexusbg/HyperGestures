// HyperGestures: the home screen closing along with an app when recents opens.
//
// HyperOS's split screen with one app left (minimized: the app pushed up to a strip at the top,
// home under it) sends recents both as closing targets: the app and the home task. AOSP's recents
// animation moves only the app's surface; home's stayed drawn over recents, its icons over the
// cards, until the animation ended (owner, 2026-11-03). Home is hidden when it closes along with
// an app, and shown again once the system has finished or cancelled the animation (a surface's
// alpha outlives it; restoring earlier would draw home over recents for a frame). Recents over the
// third-party home alone is AOSP's own case and left as it is.
package com.android.quickstep;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;

import android.util.Log;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.List;

public final class HgHomeUnderRecents {
    private static final String TAG = "HgHomeUnderRecents";

    private HgHomeUnderRecents() {}

    /**
     * The indices of the closing home targets to hide: those closing along with a closing app
     * ({@code modes}, {@code activityTypes} and whether the task has an activity, per target). An
     * app is any task with an activity that is not home: in HyperOS's split its type is undefined.
     */
    public static int[] homesToHide(int[] modes, int[] activityTypes, boolean[] hasActivity) {
        boolean appClosing = false;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == MODE_CLOSING && activityTypes[i] != ACTIVITY_TYPE_HOME
                    && hasActivity[i]) {
                appClosing = true;
            }
        }
        if (!appClosing) return new int[0];
        int[] out = new int[modes.length];
        int n = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == MODE_CLOSING && activityTypes[i] == ACTIVITY_TYPE_HOME) out[n++] = i;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * The home window's alpha for RecentsView's live tile (its own TransformParams, which draw
     * once recents has settled): 0 when home closes along with an app (as {@link #homesToHide}:
     * Circle to Search over the home screen is translucent, so home closes with it; owner,
     * 2026-11-08), otherwise 1, AOSP's always-visible home.
     */
    public static float liveTileHomeAlpha(int[] modes, int[] activityTypes, boolean[] hasActivity) {
        return homesToHide(modes, activityTypes, hasActivity).length > 0 ? 0f : 1f;
    }

    /** {@link #liveTileHomeAlpha} for all the recents animation's targets (unfilteredApps). */
    public static float liveTileHomeAlpha(RemoteAnimationTarget[] apps) {
        int[] modes = new int[apps.length];
        int[] types = new int[apps.length];
        boolean[] hasActivity = new boolean[apps.length];
        for (int i = 0; i < apps.length; i++) {
            modes[i] = apps[i].mode;
            types[i] = apps[i].windowConfiguration.getActivityType();
            hasActivity[i] = apps[i].taskInfo != null && apps[i].taskInfo.topActivity != null;
        }
        return liveTileHomeAlpha(modes, types, hasActivity);
    }

    /** Hides the homes to hide among {@code apps}; returns their surfaces, for {@link #restore}. */
    public static List<SurfaceControl> hide(RemoteAnimationTarget[] apps) {
        int[] modes = new int[apps.length];
        int[] types = new int[apps.length];
        boolean[] hasActivity = new boolean[apps.length];
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < apps.length; i++) {
            modes[i] = apps[i].mode;
            types[i] = apps[i].windowConfiguration.getActivityType();
            hasActivity[i] = apps[i].taskInfo != null && apps[i].taskInfo.topActivity != null;
            seen.append(' ').append(apps[i].taskId).append('/').append(modes[i]).append('/')
                    .append(types[i]).append(hasActivity[i] ? "/act" : "");
        }
        Log.d(TAG, "recents targets (task/mode/type):" + seen);
        List<SurfaceControl> hidden = new ArrayList<>();
        int[] homes = homesToHide(modes, types, hasActivity);
        if (homes.length == 0) return hidden;
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (int i : homes) {
            SurfaceControl leash = apps[i].leash;
            if (leash == null || !leash.isValid()) continue;
            t.setAlpha(leash, 0f);
            hidden.add(leash);
            Log.d(TAG, "home task " + apps[i].taskId + " closes with an app: hidden under recents");
        }
        t.apply();
        return hidden;
    }

    /** Shows the surfaces {@link #hide} hid again (those still valid). */
    public static void restore(List<SurfaceControl> hidden) {
        if (hidden.isEmpty()) return;
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (SurfaceControl leash : hidden) {
            if (leash.isValid()) t.setAlpha(leash, 1f);
        }
        t.apply();
    }
}
