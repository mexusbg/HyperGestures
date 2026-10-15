// HyperGestures: where recents' "Clear all" lives, extracted so it is testable.
//
// AOSP puts ClearAllButton at the end of the task list, as one more page of RecentsView. On a phone
// that button sits off-screen past the oldest card, so we show clear all as a trash-can button in
// the overview actions row under the cards instead (in place of Screenshot, which does nothing on
// HyperOS) and keep the in-list button invisible and out of the scroll range. Large screens hide the
// whole actions row (HIDDEN_ACTIONS_IN_MENU), so they keep AOSP's in-list button.
package com.android.quickstep.views;

public final class OverviewClearAllPolicy {
    private OverviewClearAllPolicy() {}

    /**
     * @param isLargeScreen the device profile's {@code isLargeScreen()}
     * @return true if clear all is the actions-row trash button rather than the in-list button
     */
    public static boolean inActionsRow(boolean isLargeScreen) {
        return !isLargeScreen;
    }

    /**
     * @param stateHasClearAll the recents state shows clear all ({@code hasClearAllButton()})
     * @return true if the in-list ClearAllButton is shown and RecentsView may scroll to it
     */
    public static boolean showListButton(boolean stateHasClearAll, boolean isLargeScreen) {
        return stateHasClearAll && !inActionsRow(isLargeScreen);
    }

    /**
     * @param stateHasClearAll the recents state shows clear all ({@code hasClearAllButton()})
     * @return true if the trash button in the actions row is shown
     */
    public static boolean showActionsButton(boolean stateHasClearAll, boolean isLargeScreen) {
        return stateHasClearAll && inActionsRow(isLargeScreen);
    }

    /**
     * A trash tap starts RecentsView's dismiss-all only when there is something to clear and no
     * dismissal is already running (a second tap during the dismiss-all animation would start the
     * dismiss-all path twice).
     *
     * @param taskViewCount the number of task views in RecentsView
     * @param anyBeingDismissed a task view is already being dismissed
     */
    public static boolean canClearAll(int taskViewCount, boolean anyBeingDismissed) {
        return taskViewCount > 0 && !anyBeingDismissed;
    }
}
