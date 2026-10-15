// HyperGestures: tapping the recents card of a floating window (HyperOS freeform, also when shrunk
// to a mini window by HgFreeformState) brings it back floating, as MiuiHome's recents does.
//
// AOSP's tap launch runs the launcher's app-launch animation and keeps recents non-interactive
// until it ends; HyperOS restores a floating window itself and never runs that animation, so
// recents ignored every later tap (owner, 2026-10-08: after tapping the floating Telegram card,
// "other cards don't work"). The card goes the way of the hold-button Floating window action
// instead (HgTaskShortcuts): the live tile ends first, the task starts from recents with plain
// options (no fullscreen request: HyperOS's activeFreeformTaskIfNeed restores it floating), and
// recents leaves for the home screen under it.
package com.android.quickstep;

import android.app.ActivityOptions;
import android.util.Log;

import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;

public final class HgFloatingCard {
    private HgFloatingCard() {}

    private static final String TAG = "HgFloating";

    /** Whether a card of these tasks is a floating window to restore (one task, freeform). */
    public static boolean isFloating(int[] windowingModes) {
        return windowingModes.length == 1
                && windowingModes[0] == HgSystemSplitLaunch.WINDOWING_MODE_FREEFORM;
    }

    /** Restores {@code task}'s floating window from recents (see the file comment). */
    public static void launch(RecentsView<?, ?> rv, Task task) {
        int taskId = task.key.id;
        rv.switchToScreenshot(() -> rv.finishRecentsAnimation(true /* toHome */,
                false /* shouldPip */, () -> rv.post(() -> restore(rv, taskId))));
    }

    /**
     * With recents' animation ended: starts {@code taskId} with plain options, so HyperOS brings it
     * back floating, and recents leaves for the home screen under it. Whether it started. Also the
     * end of a quick switch to a floating window (HgQuickSwitch).
     */
    static boolean restore(RecentsView<?, ?> rv, int taskId) {
        boolean started = ActivityManagerWrapper.getInstance()
                .startActivityFromRecents(taskId, ActivityOptions.makeBasic());
        Log.i(TAG, "floating card: task " + taskId + " restored=" + started);
        if (started) rv.startHome();
        return started;
    }
}
