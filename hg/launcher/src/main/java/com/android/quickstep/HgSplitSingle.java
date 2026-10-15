// HyperGestures: MIUI's split screen with one app (owner, 2026-10-08: "the app enters split screen
// on top with nova on bottom, we pick the second app for split screen from nova, not recents").
//
// HyperOS's split screen (SoSc) has a "single" mode: one task in a half, the other half empty with
// the home screen showing through (Shell makes the split root translucent and brings the home task
// to the front behind it). The framework then sends the next app launched from a HOME- or
// RECENTS-type task, e.g. a tap on an icon in Nova, into the empty half, and Shell switches to a
// full split. Entered with ISoScSplitScreen.startTask (HgSoScSplitScreen), which MiuiHome calls.
//
// Used by the split corner (HgCornerDrag, always, as HyperOS's drag) and by the card's Split
// screen button when recents has no other card to pair with (HgTaskShortcuts). Shell picks the
// lowest-id HOME or RECENTS root task to show: the home root task is created at boot, so Nova
// shows, not our recents. Recents does not start home itself afterwards: a launch from our
// RECENTS-type task would be sent into the empty half.
package com.android.quickstep;

import android.content.Context;
import android.util.Log;

import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;

public final class HgSplitSingle {
    private HgSplitSingle() {}

    private static final String TAG = "HgSplit";
    /** The app goes in the top (left) half, as in HyperOS. */
    static final int POSITION_TOP_OR_LEFT = 0;
    /**
     * After this, recents still up and HyperOS's split screen not entered (HgSoScState) means
     * Shell refused the oneway call (logcat only, e.g. "task in SoSc, abort!"): recents' next stop
     * must move it back again.
     */
    static final long GUARD_MS = 3000;

    /** Whether one-app split can be started: HyperOS's split binder and switch. No log. */
    public static boolean available(Context context) {
        return SystemUiProxy.INSTANCE.get(context).hgCanStartSoScSingle();
    }

    /**
     * Ends recents' live tile (as the floating window does), then puts {@code taskId} in the top
     * half with the home screen below. False if one-app split is not available.
     */
    public static boolean start(RecentsView<?, ?> rv, int taskId) {
        Context context = rv.getContext();
        if (!available(context)) return false;
        Log.i(TAG, "one-app split: task " + taskId + ", second app from the home screen");
        rv.switchToScreenshot(() -> rv.finishRecentsAnimation(true /* toHome */,
                false /* shouldPip */, () -> rv.post(() -> enter(rv, taskId))));
        return true;
    }

    private static void enter(RecentsView<?, ?> rv, int taskId) {
        // The stop that follows must not move recents back: on HyperOS that TO_BACK transition
        // makes the split exit (as for a split pair launched from here).
        RecentsActivity recents = null;
        int token = 0;
        try {
            if (RecentsViewContainer.containerFromContext(rv.getContext())
                    instanceof RecentsActivity r) {
                recents = r;
                token = recents.hgOnSplitPairLaunched();
            }
        } catch (RuntimeException ignored) {
            // not in RecentsActivity: no stop to guard
        }
        if (!SystemUiProxy.INSTANCE.get(rv.getContext())
                .hgStartSoScSingle(taskId, POSITION_TOP_OR_LEFT)) {
            Log.w(TAG, "one-app split: HyperOS's split screen went away; task " + taskId);
            if (recents != null) recents.hgClearSplitPairLaunched(token);
            return;
        }
        if (recents != null) {
            RecentsActivity guarded = recents;
            int guardToken = token;
            rv.postDelayed(() -> {
                if (guarded.isStarted() && !HgSoScState.inSplit()) {
                    Log.w(TAG, "one-app split: recents still up; task " + taskId + " not split");
                    guarded.hgClearSplitPairLaunched(guardToken);
                }
            }, GUARD_MS);
        }
    }

    /** Whether {@code task} can be put alone in a split: resizable and not already in one. */
    static boolean canSplit(com.android.systemui.shared.recents.model.Task task) {
        return task.isDockable
                && !HgSystemSplitLaunch.inSystemSplit(new int[] {task.key.windowingMode});
    }
}
