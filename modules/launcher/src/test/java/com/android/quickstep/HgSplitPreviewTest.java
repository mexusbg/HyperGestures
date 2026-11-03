package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Cards of apps in, or just out of, HyperOS's split screen (HgSplitPreview). The device: 1200 x
 * 2608 px; a card 800 x 1740.
 */
public class HgSplitPreviewTest {
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;
    private static final int MULTI_WINDOW = 6;

    /** Slack after leaving split (owner, 2026-11-03): only a half-screen snapshot exists. */
    @Test
    public void halfScreenSnapshot_iconCard() {
        assertTrue(HgSplitPreview.iconCard(FULLSCREEN, true, 1200f, 1292f, 800, 1740));
    }

    /** An app still in split (its snapshot taken there) shows the icon card too. */
    @Test
    public void snapshotTakenInSplit_iconCard() {
        assertTrue(HgSplitPreview.iconCard(MULTI_WINDOW, true, 1200f, 1292f, 800, 1740));
    }

    @Test
    public void fullScreenSnapshot_shown() {
        assertFalse(HgSplitPreview.iconCard(FULLSCREEN, true, 1200f, 2608f, 800, 1740));
    }

    /** A reduced-resolution snapshot keeps the screen's shape: shown. */
    @Test
    public void reducedFullScreenSnapshot_shown() {
        assertFalse(HgSplitPreview.iconCard(FULLSCREEN, true, 600f, 1304f, 800, 1740));
    }

    /** A half of a split pair card fills its half, as before. */
    @Test
    public void halfOfSplitPairCard_shown() {
        assertFalse(HgSplitPreview.iconCard(FULLSCREEN, false, 1200f, 1292f, 800, 860));
    }

    /** Floating windows keep their own fit (2026-10-07). */
    @Test
    public void floatingWindow_shown() {
        assertFalse(HgSplitPreview.iconCard(FREEFORM, true, 602f, 1200f, 800, 1740));
    }

    /** A landscape snapshot in portrait recents is AOSP's rotation case. */
    @Test
    public void landscapeSnapshotInPortrait_shown() {
        assertFalse(HgSplitPreview.iconCard(FULLSCREEN, true, 2608f, 1200f, 800, 1740));
    }

    @Test
    public void unmeasuredCard_shown() {
        assertFalse(HgSplitPreview.iconCard(FULLSCREEN, true, 1200f, 1292f, 0, 0));
    }

    /** HyperOS's split with one app left: the app pushed up to a strip (21:48, Slack). */
    @Test
    public void appPushedOffScreen_minimizedSplit() {
        assertTrue(HgSplitPreview.minimizedSplit(MULTI_WINDOW, 0, -1044, 1200, 248, 1200, 2608));
    }

    /** Both halves on screen: the split pair, not minimized. */
    @Test
    public void halfOnScreen_notMinimized() {
        assertFalse(HgSplitPreview.minimizedSplit(MULTI_WINDOW, 0, 0, 1200, 1292, 1200, 2608));
    }

    @Test
    public void fullScreenApp_notMinimized() {
        assertFalse(HgSplitPreview.minimizedSplit(FULLSCREEN, 0, 0, 1200, 2608, 1200, 2608));
    }
}
