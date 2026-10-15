// HyperGestures: one switch for the launcher's chatty AOSP debug logging.
//
// HyperOS's logd prunes the chattiest app first, and a single recents open/close printed hundreds
// of AOSP lines (one per bound card, per thumbnail state, per state change with a stack trace),
// so our own diagnostic lines (HgSplit, HgFloating, RecentsActivity, GroupedTaskView...) were gone
// within minutes. Those per-card / per-frame lines are gated behind VERBOSE, which stays false
// (flip it locally to debug them); warnings, errors and every Hg* log are never gated.
package com.android.quickstep;

public final class HgLog {
    public static final boolean VERBOSE = false;

    private HgLog() {}
}
