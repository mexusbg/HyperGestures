// HyperGestures: the watchdog of a split launch's remote transition runner
// (SplitSelectStateController.RemoteSplitLaunchTransitionRunner).
//
// AOSP expects Shell to answer every split start with startAnimation or onTransitionConsumed; the
// runner's callback and the split select reset run only from those. HyperOS's split screen returns
// from SoScStageCoordinator.startTasks in seven places without touching the RemoteTransition (a
// double tap, "already exist", the exit after home opened, a fixed-rotation app, the one-app split
// fill...; bug sweep 2026-10-09, finding 3), and recents waited for good: after split select it
// stayed blank with the two floating thumbnails and ignored taps until back or home. When Shell has
// not called within TIMEOUT_MS the runner runs its failure cleanup, once, and recents returns to
// its normal state (RecentsActivity.hgOnSplitLaunchLost). A Shell call in time disarms it; one
// after it (a slow split, not a dropped one) is reported as late, so the runner can keep the split
// that is forming (review of the sweep fixes, finding 1).
package com.android.quickstep;

public final class HgSplitLaunchWatch {
    /**
     * WM's transition sync timeout (BLASTSyncEngine): Shell sends startAnimation once the apps
     * have drawn, so a cold or slow app can delay a split that does form, up to about this long
     * after the transition starts collecting.
     */
    public static final long SYNC_TIMEOUT_MS = 5000;

    /**
     * Past the sync timeout with a margin: the watchdog starts before the transition does and
     * Shell needs a moment to play it, so at exactly the sync timeout recents went back to the
     * cards and the slow split played right after (2026-10-09: a cold app kept its splash for
     * over 2 s).
     */
    public static final long TIMEOUT_MS = SYNC_TIMEOUT_MS + 1500;

    private boolean mShellCalled;
    private boolean mCleanedUp;
    private boolean mTimedOut;

    /**
     * Shell called the runner (startAnimation or onTransitionConsumed), on any thread. True if
     * the watchdog had already cleaned up: a late answer.
     */
    public synchronized boolean onShellCallback() {
        mShellCalled = true;
        return mTimedOut;
    }

    /**
     * The watchdog fired: true, and the cleanup is claimed, only if Shell never called and no
     * cleanup ran (one step, so a Shell call cannot slip in between).
     */
    public synchronized boolean claimTimeoutCleanup() {
        if (mShellCalled || mCleanedUp) return false;
        mCleanedUp = true;
        mTimedOut = true;
        return true;
    }

    /** The runner's own cleanup is starting: true the first time only, whoever comes first. */
    public synchronized boolean claimCleanup() {
        if (mCleanedUp) return false;
        mCleanedUp = true;
        return true;
    }
}
