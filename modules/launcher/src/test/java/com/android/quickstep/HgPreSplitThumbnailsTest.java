package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The last full-screen screenshot of a task, shown for it after a split (HgPreSplitThumbnails). */
public class HgPreSplitThumbnailsTest {
    /** A full-screen screenshot is held; a newer full-screen one replaces it. */
    @Test public void holdsLatestFullScreen() {
        HgPreSplitThumbnails<String> pre = new HgPreSplitThumbnails<>(4);
        pre.remember(7, "full", true);
        assertEquals("full", pre.lastOf(7));
        pre.remember(7, "full, later", true);
        assertEquals("full, later", pre.lastOf(7));
    }

    /** A split screenshot never replaces the full-screen one: that is the one shown for it. */
    @Test public void splitScreenshotKeepsFullScreen() {
        HgPreSplitThumbnails<String> pre = new HgPreSplitThumbnails<>(4);
        pre.remember(7, "full", true);
        pre.remember(7, "half", false);
        assertEquals("full", pre.lastOf(7));
    }

    @Test public void splitScreenshotAloneHoldsNothing() {
        HgPreSplitThumbnails<String> pre = new HgPreSplitThumbnails<>(4);
        pre.remember(7, "half", false);
        assertNull(pre.lastOf(7));
    }

    @Test public void nullKeepsPrevious() {
        HgPreSplitThumbnails<String> pre = new HgPreSplitThumbnails<>(4);
        pre.remember(7, "full", true);
        pre.remember(7, null, true);
        assertEquals("full", pre.lastOf(7));
    }

    /**
     * The task in split stays held while recents loads other cards: each split screenshot of it
     * marks it recently used, so the other tasks' screenshots push out older ones first.
     */
    @Test public void taskInSplitOutlivesOthers() {
        HgPreSplitThumbnails<String> pre = new HgPreSplitThumbnails<>(2);
        pre.remember(7, "full 7", true);
        pre.remember(8, "full 8", true);
        pre.remember(7, "half 7", false); // seen in split: 7 recently used
        pre.remember(9, "full 9", true); // out goes 8, not 7
        assertEquals("full 7", pre.lastOf(7));
        assertNull(pre.lastOf(8));
        assertEquals("full 9", pre.lastOf(9));
    }

    @Test public void forgetAndClear() {
        HgPreSplitThumbnails<String> pre = new HgPreSplitThumbnails<>(4);
        pre.remember(7, "full 7", true);
        pre.remember(8, "full 8", true);
        pre.forget(7);
        assertNull(pre.lastOf(7));
        assertEquals("full 8", pre.lastOf(8));
        pre.clear();
        assertNull(pre.lastOf(8));
    }
}
