// HyperGestures: keeps the running app's live tile with its card when our gestures move the card.
//
// After a swipe up into recents the running app's card is a live tile: the app's own surface,
// drawn below recents through a hole in the card (RecentsView.setDrawLiveTileBelowRecents). Moving
// the card's view alone would move the frame and the hole but not the surface. AOSP's dismiss swipe
// mirrors the move on the TaskViewSimulator and redraws (TaskViewDismissTouchController); the lock
// swipe (secondary axis) and the hold buttons' slide aside (primary axis) do the same here.
package com.android.quickstep;

import com.android.quickstep.orientation.RecentsPagedOrientationHandler;
import com.android.quickstep.util.TaskViewSimulator;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;

final class HgLiveTile {
    private HgLiveTile() {}

    /**
     * The card's move across the scroll axis (the lock swipe), as the same signed secondary
     * translation the card got; the simulator maps it per orientation as for AOSP's dismiss.
     */
    static void mirrorSecondary(TaskView taskView, float value) {
        RecentsView<?, ?> recentsView = liveTileRecents(taskView);
        if (recentsView == null) return;
        recentsView.runActionOnRemoteHandles(
                handle -> handle.getTaskViewSimulator().taskSecondaryTranslation.value = value);
        recentsView.redrawLiveTile();
    }

    /**
     * The live tile's own primary translation now (RecentsView keeps the running card's offsets
     * there), or NaN when there is no live tile to move or recents is not in portrait (the slide
     * aside is screen x, the primary axis only in portrait).
     */
    static float primaryBase(TaskView taskView) {
        RecentsView<?, ?> recentsView = liveTileRecents(taskView);
        if (recentsView == null
                || recentsView.getPagedOrientationHandler() != RecentsPagedOrientationHandler.PORTRAIT) {
            return Float.NaN;
        }
        float[] base = {Float.NaN};
        recentsView.runActionOnRemoteHandles(handle -> {
            if (Float.isNaN(base[0])) {
                base[0] = handle.getTaskViewSimulator().taskPrimaryTranslation.value;
            }
        });
        return base[0];
    }

    /** Sets the live tile's primary translation to {@code value} (a base from primaryBase + move). */
    static void setPrimary(TaskView taskView, float value) {
        RecentsView<?, ?> recentsView = liveTileRecents(taskView);
        if (recentsView == null) return;
        recentsView.runActionOnRemoteHandles(handle -> {
            TaskViewSimulator simulator = handle.getTaskViewSimulator();
            simulator.taskPrimaryTranslation.value = value;
        });
        recentsView.redrawLiveTile();
    }

    private static RecentsView<?, ?> liveTileRecents(TaskView taskView) {
        RecentsView<?, ?> recentsView = taskView.getRecentsView();
        if (recentsView == null || !taskView.isRunningTask()
                || !recentsView.getEnableDrawingLiveTile()) {
            return null;
        }
        return recentsView;
    }
}
