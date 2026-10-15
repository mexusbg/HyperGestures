package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The last screenshot of a task, the fallback for cards that left a split (HgLastThumbnails). */
public class HgLastThumbnailsTest {
    /** A task's last screenshot comes back, the newer one replacing the older. */
    @Test public void remembersLatest() {
        HgLastThumbnails<String> last = new HgLastThumbnails<>(4);
        last.remember(7, "in split");
        assertEquals("in split", last.lastOf(7));
        last.remember(7, "fullscreen");
        assertEquals("fullscreen", last.lastOf(7));
    }

    /** No screenshot (the system had none) keeps the one before. */
    @Test public void nullKeepsPrevious() {
        HgLastThumbnails<String> last = new HgLastThumbnails<>(4);
        last.remember(7, "in split");
        last.remember(7, null);
        assertEquals("in split", last.lastOf(7));
    }

    /** Unknown tasks have none. */
    @Test public void unknownIsNull() {
        assertNull(new HgLastThumbnails<String>(4).lastOf(1));
    }

    /** Bounded: the least recently used task goes first. */
    @Test public void boundedLeastRecentlyUsedOut() {
        HgLastThumbnails<String> last = new HgLastThumbnails<>(2);
        last.remember(1, "a");
        last.remember(2, "b");
        last.lastOf(1); // 1 used again: 2 is now the oldest
        last.remember(3, "c");
        assertEquals("a", last.lastOf(1));
        assertNull(last.lastOf(2));
        assertEquals("c", last.lastOf(3));
    }

    /** A removed task and a memory trim drop them. */
    @Test public void forgetAndClear() {
        HgLastThumbnails<String> last = new HgLastThumbnails<>(4);
        last.remember(1, "a");
        last.remember(2, "b");
        last.forget(1);
        assertNull(last.lastOf(1));
        last.clear();
        assertNull(last.lastOf(2));
    }
}
