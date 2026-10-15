// HyperGestures: a quick switch (the gesture's NEW_TASK end, AbsSwipeUpHandler.startNewTask) to a
// split pair's card or to a floating window's card.
//
// AOSP launches the card while the gesture's recents animation still runs and expects Shell to
// merge the launch into it (onTasksAppeared finishes the animation). HyperOS does not merge these
// two (bug sweep 2026-10-09, findings 1 and 2):
// - a split pair starts through HyperOS's split screen (SoSc), which cancels the recents animation
//   instead, as for a split card tapped over a live app (owner, 2026-10-08: "taken to the other
//   app"; 05fd123);
// - a floating or mini window is always-on-top on HyperOS, and Shell's RecentsController.merge
//   cancels the animation for any always-on-top task ("task #N is always_on_top, and it's
//   freeform").
// On that cancel AbsSwipeUpHandler started the next older card as a fallback, so the switch also
// opened a second, wrong app. Here the animation ends first (to recents, as for a tap on such a
// card), then the card launches: the pair as AOSP's quick switch does, the floating window the way
// its card's tap restores it (HgFloatingCard).
package com.android.quickstep;

import android.util.Log;

import com.android.quickstep.views.GroupedTaskView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;

import java.util.Arrays;
import java.util.List;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class HgQuickSwitch {
    private HgQuickSwitch() {}

    private static final String TAG = "HgQuickSwitch";

    /** AOSP's launch into the running recents animation. */
    static final int ROUTE_AOSP = 0;
    /** A split pair through HyperOS's split screen, once the recents animation has ended. */
    static final int ROUTE_SPLIT = 1;
    /** A floating window restored as its card's tap does, once the recents animation has ended. */
    static final int ROUTE_FLOATING = 2;

    /**
     * How a quick switch to a card launches: {@code pairCard} for a split pair's card,
     * {@code windowingModes} of its tasks, {@code soscSplit} when pairs start through HyperOS's
     * split screen (SystemUiProxy.hgCanStartSoScSingle, the gate of the tap fix 05fd123).
     */
    static int route(boolean pairCard, int[] windowingModes, boolean soscSplit) {
        if (pairCard) return soscSplit ? ROUTE_SPLIT : ROUTE_AOSP;
        return HgFloatingCard.isFloating(windowingModes) ? ROUTE_FLOATING : ROUTE_AOSP;
    }

    /** Whether a quick switch to {@code taskView} must end the recents animation before it. */
    public static boolean endsRecentsFirst(TaskView taskView) {
        return routeOf(taskView) != ROUTE_AOSP;
    }

    /**
     * Launches {@code taskView} after the gesture's recents animation finished to recents, and
     * calls {@code callback} once with whether it started (launchWithoutAnimation's contract).
     */
    public static void launch(TaskView taskView, Function1<? super Boolean, Unit> callback) {
        int route = routeOf(taskView);
        Log.i(TAG, "quick switch to " + Arrays.toString(taskView.getTaskIds()) + ": "
                + (route == ROUTE_FLOATING ? "floating window" : "split pair")
                + ", recents animation ended first");
        if (route != ROUTE_FLOATING) {
            taskView.launchWithoutAnimation(true /* isQuickSwitch */, launched -> {
                // The split watchdog (HgSplitLaunchWatch) reports a failure 2 s on, maybe after
                // recents has left. The handler's failure path would then put the stopped recents
                // in its card state, which also shrinks floating windows (HgFreeformState).
                if (!launched && !recentsStarted(taskView)) {
                    Log.i(TAG, "quick switch: no split launch reported, recents already left");
                    return callback.invoke(true);
                }
                return callback.invoke(launched);
            });
            return;
        }
        RecentsView<?, ?> rv = taskView.getRecentsView();
        if (rv == null) {
            callback.invoke(false);
            return;
        }
        int taskId = taskView.getTaskIds()[0];
        rv.post(() -> callback.invoke(HgFloatingCard.restore(rv, taskId)));
    }

    private static boolean recentsStarted(TaskView taskView) {
        try {
            return !(RecentsViewContainer.containerFromContext(taskView.getContext())
                    instanceof RecentsActivity recents) || recents.isStarted();
        } catch (RuntimeException e) {
            return true; // not in RecentsActivity: AOSP's handling
        }
    }

    private static int routeOf(TaskView taskView) {
        List<TaskContainer> containers = taskView.getTaskContainers();
        int[] modes = new int[containers.size()];
        for (int i = 0; i < modes.length; i++) {
            modes[i] = containers.get(i).getTask().key.windowingMode;
        }
        boolean pairCard = taskView instanceof GroupedTaskView;
        return route(pairCard, modes, pairCard
                && SystemUiProxy.INSTANCE.get(taskView.getContext()).hgCanStartSoScSingle());
    }
}
