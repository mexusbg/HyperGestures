package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The split launch watchdog cleans up once, only when Shell never called (HgSplitLaunchWatch). */
public class HgSplitLaunchWatchTest {
    /** HyperOS's split screen dropped the request: the watchdog cleans up, once. */
    @Test public void noShellCall_timeoutCleansUpOnce() {
        HgSplitLaunchWatch watch = new HgSplitLaunchWatch();
        assertTrue(watch.claimTimeoutCleanup());
        assertFalse(watch.claimTimeoutCleanup());
        assertFalse(watch.claimCleanup());
    }

    /** Shell started the animation in time: the watchdog does nothing, the animation cleans up. */
    @Test public void shellCallInTime_timeoutIsNoOp() {
        HgSplitLaunchWatch watch = new HgSplitLaunchWatch();
        assertFalse(watch.onShellCallback()); // not late
        assertFalse(watch.claimTimeoutCleanup());
        assertTrue(watch.claimCleanup());
    }

    /** Shell called after the watchdog ran: reported late, and no second cleanup. */
    @Test public void lateShellCall_isLate_noSecondCleanup() {
        HgSplitLaunchWatch watch = new HgSplitLaunchWatch();
        assertTrue(watch.claimTimeoutCleanup());
        assertTrue(watch.onShellCallback());
        assertFalse(watch.claimCleanup());
    }

    /** Consumed, then the timeout's runnable already queued: still one cleanup, not late. */
    @Test public void consumedThenTimeout_cleansUpOnce() {
        HgSplitLaunchWatch watch = new HgSplitLaunchWatch();
        assertFalse(watch.onShellCallback());
        assertTrue(watch.claimCleanup());
        assertFalse(watch.claimTimeoutCleanup());
        assertFalse(watch.claimCleanup());
    }

    /** The watchdog waits as long as WM's ~5 s sync timeout, so a slow split is not undone. */
    @Test public void timeoutCoversSyncTimeout() {
        assertTrue(HgSplitLaunchWatch.SYNC_TIMEOUT_MS >= 5000);
        assertTrue(HgSplitLaunchWatch.TIMEOUT_MS >= HgSplitLaunchWatch.SYNC_TIMEOUT_MS + 1000);
    }
}
