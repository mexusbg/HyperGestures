// HyperGestures: when the back arrow ticks, as MiuiHome does it (docs/research/
// 2026-10-09-miuihome-recents-features.md §4b). One "ready" tick the first time in a gesture the
// finger has travelled more than 180 px sideways (GesturesBackTouchProcessor.isShowBackArrow;
// MiuiHome guards the tick with mArrowFeedBackDone) while a release would trigger back, and one
// "hand-up" tick when the release triggers back, dropped if it comes less than 140 ms after the
// ready tick (MiuiHome's TimeOutBlocker BLOCKER_ID_FOR_HAPTIC_GESTURE_BACK, 0x8c ms) so a quick
// flick does not buzz twice. A back released before 180 px (our arrow arms at AOSP's drag slop,
// MiuiHome's only at 180 px) gets the hand-up tick alone. Pure timing; HgBackHaptics plays them.
package com.android.quickstep.interaction;

final class HgBackHapticTimer {
    /**
     * Sideways travel past which the ready tick plays. Raw px, not dp, as in MiuiHome (its
     * offset > 180 test ignores density); the stock feel on the 1200 px wide phone.
     */
    static final float READY_TRAVEL_PX = 180;
    /** A release this soon after the ready tick plays no second tick. */
    static final long RELEASE_BLOCK_MS = 140;

    /** Uptime of this gesture's ready tick, or -1 if it has not played yet. */
    private long mReadyAtMs = -1;

    /** A new gesture (touch-down): the arrow may tick again. */
    void reset() {
        mReadyAtMs = -1;
    }

    /**
     * The finger moved: travelPx sideways from the touch-down, armed if a release now triggers
     * back. True if the ready tick plays now (the first time both hold in this gesture).
     */
    boolean onMove(float travelPx, boolean armed, long nowMs) {
        if (mReadyAtMs >= 0 || !armed || !(travelPx > READY_TRAVEL_PX)) return false;
        mReadyAtMs = nowMs;
        return true;
    }

    /** The release triggered back; true if the hand-up tick plays. */
    boolean onBackTriggered(long nowMs) {
        return mReadyAtMs < 0 || nowMs - mReadyAtMs >= RELEASE_BLOCK_MS;
    }
}
