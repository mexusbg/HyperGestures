package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import com.android.wm.shell.shared.split.SplitScreenConstants;

import org.junit.Test;

/** The snap position given to the gesture's SplitBounds from HyperOS's stage sizes. */
public class HgSoScStateTest {
    /** The owner's top/bottom split: 1292 and 1292 px around a 24 px divider. */
    @Test public void evenSplitIsFiftyFifty() {
        assertEquals(SplitScreenConstants.SNAP_TO_2_50_50, HgSoScState.snapPosition(1292, 1292));
    }

    @Test public void thirdsAndTenths() {
        assertEquals(SplitScreenConstants.SNAP_TO_2_33_66, HgSoScState.snapPosition(860, 1724));
        assertEquals(SplitScreenConstants.SNAP_TO_2_66_33, HgSoScState.snapPosition(1724, 860));
        assertEquals(SplitScreenConstants.SNAP_TO_2_10_90, HgSoScState.snapPosition(260, 2324));
        assertEquals(SplitScreenConstants.SNAP_TO_2_90_10, HgSoScState.snapPosition(2324, 260));
    }

    /** States 0/1 are the one-app split; -1 (off) and 2 (both halves) are not. */
    @Test public void oneAppStates() {
        assertEquals(true, HgSoScState.isOneAppState(0));
        assertEquals(true, HgSoScState.isOneAppState(1));
        assertEquals(false, HgSoScState.isOneAppState(-1));
        assertEquals(false, HgSoScState.isOneAppState(HgSoScState.STATE_FULL));
    }

    @Test public void emptySizesDoNotDivideByZero() {
        assertEquals(SplitScreenConstants.SNAP_TO_2_10_90, HgSoScState.snapPosition(0, 0));
    }
    /** What the feed posted to TopTaskTracker, in order. */
    private static final class Posts implements HgSoScState.PairSink {
        final java.util.List<String> got = new java.util.ArrayList<>();

        @Override public void post(int lt, int rb) {
            got.add(lt + "/" + rb);
        }
    }

    private static HgSoScState.Pair pair(int lt, int rb) {
        return new HgSoScState.Pair(lt, rb, new android.graphics.Rect(),
                new android.graphics.Rect());
    }

    /** Only a change of pair is posted; the same pair again is not. */
    @Test public void feed_postsOnlyChanges() {
        HgSoScState.Feed feed = new HgSoScState.Feed();
        Object source = new Object();
        Posts posts = new Posts();
        feed.start(source);
        feed.onState(source, HgSoScState.STATE_FULL, pair(11, 22), posts);
        feed.onState(source, HgSoScState.STATE_FULL, pair(11, 22), posts);
        feed.onState(source, 0, null, posts);
        assertEquals(java.util.List.of("11/22", "-1/-1"), posts.got);
        assertEquals(0, feed.lastState());
    }

    /** A report still in flight when unregister ran never posts its pair after the clear. */
    @Test public void feed_reportAfterStopIsDropped() {
        HgSoScState.Feed feed = new HgSoScState.Feed();
        Object source = new Object();
        Posts posts = new Posts();
        feed.start(source);
        feed.stop(posts);
        feed.onState(source, HgSoScState.STATE_FULL, pair(11, 22), posts);
        assertEquals(java.util.List.of("-1/-1"), posts.got);
        assertEquals(null, feed.pair());
        assertEquals(-1, feed.lastState());
    }

    /** Stop always clears TopTaskTracker's pair, even when nothing was followed. */
    @Test public void feed_stopAlwaysClears() {
        HgSoScState.Feed feed = new HgSoScState.Feed();
        Posts posts = new Posts();
        feed.stop(posts);
        assertEquals(java.util.List.of("-1/-1"), posts.got);
    }

    /** Only the listener registered last counts. */
    @Test public void feed_onlyCurrentSourceCounts() {
        HgSoScState.Feed feed = new HgSoScState.Feed();
        Object old = new Object();
        Object current = new Object();
        Posts posts = new Posts();
        feed.start(current);
        feed.onState(old, HgSoScState.STATE_FULL, pair(1, 2), posts);
        feed.onState(current, HgSoScState.STATE_FULL, pair(3, 4), posts);
        assertEquals(java.util.List.of("3/4"), posts.got);
    }
}
