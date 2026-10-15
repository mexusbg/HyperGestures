// HyperGestures: opening a recents card whose app sits in HyperOS's split screen (SoSc), extracted
// so it is testable.
//
// Owner, 2026-10-07: Facebook + Messenger in split, recents showed one card (Messenger); tapping
// it brought the split back, then both apps closed. HyperOS puts both halves in its split stages
// in multi-window mode, so the card's task has WINDOWING_MODE_MULTI_WINDOW. Two things on the tap
// path then take the split down:
// - launchAsStaticTile asks for a fullscreen launch, which pulls the task out of its stage;
// - the stop that follows runs RecentsActivity's moveTaskToBack, the TO_BACK transition that made
//   SoSc exit right after a split pair was launched (2026-09-30, hgOnSplitPairLaunched).
// For such a card the launch keeps the task's windowing mode and recents skips that
// moveTaskToBack, as for a split pair started from here.
//
// A floating window's card (freeform, also when shrunk to a mini window; HgFreeformState) keeps
// its windowing mode too, so HyperOS brings it back floating as MiuiHome's recents does, not
// fullscreen.
package com.android.quickstep;

public final class HgSystemSplitLaunch {
    private HgSystemSplitLaunch() {}

    /** android.app.WindowConfiguration constants (hidden API values, stable since Android 7). */
    static final int WINDOWING_MODE_UNDEFINED = 0;
    static final int WINDOWING_MODE_FULLSCREEN = 1;
    static final int WINDOWING_MODE_FREEFORM = 5;
    static final int WINDOWING_MODE_MULTI_WINDOW = 6;

    /** Whether any of the card's tasks was in a split stage when recents loaded it. */
    public static boolean inSystemSplit(int[] windowingModes) {
        for (int mode : windowingModes) {
            if (mode == WINDOWING_MODE_MULTI_WINDOW) return true;
        }
        return false;
    }

    /**
     * Whether opening a card fills HyperOS's one-app split ({@code oneAppSplit}, HgSoScState): the
     * framework sends a launch from a RECENTS-type task into the empty half if the app can be split
     * (research 2026-10-08-sosc-one-app-split.md, 3), so, as for a card in a split stage, recents'
     * next stop must not move its task back (bug sweep 2026-10-09, finding 8). A pair card starts
     * its own split (SplitSelectStateController); a non-resizable app is not sent there.
     */
    public static boolean fillsOneAppSplit(boolean oneAppSplit, int taskCount, boolean dockable) {
        return oneAppSplit && taskCount == 1 && dockable;
    }

    /** Whether the card's task should keep its windowing mode: in a split stage, or floating. */
    public static boolean keepsWindowingMode(int[] windowingModes) {
        for (int mode : windowingModes) {
            if (mode == WINDOWING_MODE_MULTI_WINDOW || mode == WINDOWING_MODE_FREEFORM) return true;
        }
        return false;
    }

    /**
     * The windowing mode to launch the card's task in: AOSP's fullscreen, or undefined (its own,
     * so it stays in the split or floating) when it keeps its mode.
     */
    public static int launchWindowingMode(boolean keepsMode) {
        return keepsMode ? WINDOWING_MODE_UNDEFINED : WINDOWING_MODE_FULLSCREEN;
    }
}
