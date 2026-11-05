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

    // fullScreenShape: which screenshots are kept to show after a split (HgPreSplitThumbnails)

    @Test
    public void fullScreenSnapshot_fullScreenShape() {
        assertTrue(HgSplitPreview.fullScreenShape(FULLSCREEN, 1200f, 2608f, 1200, 2608));
    }

    @Test
    public void reducedSnapshot_fullScreenShape() {
        assertTrue(HgSplitPreview.fullScreenShape(FULLSCREEN, 600f, 1304f, 1200, 2608));
    }

    /** A landscape app's screenshot: the screen's shape turned. */
    @Test
    public void landscapeSnapshot_fullScreenShape() {
        assertTrue(HgSplitPreview.fullScreenShape(FULLSCREEN, 2608f, 1200f, 1200, 2608));
    }

    /** Slack after leaving split: recorded full-screen, but half the screen. */
    @Test
    public void halfScreenSnapshot_notFullScreenShape() {
        assertFalse(HgSplitPreview.fullScreenShape(FULLSCREEN, 1200f, 1292f, 1200, 2608));
    }

    @Test
    public void takenInSplit_notFullScreenShape() {
        assertFalse(HgSplitPreview.fullScreenShape(MULTI_WINDOW, 1200f, 2608f, 1200, 2608));
    }

    @Test
    public void floatingWindow_notFullScreenShape() {
        assertFalse(HgSplitPreview.fullScreenShape(FREEFORM, 1200f, 2608f, 1200, 2608));
    }

    @Test
    public void unknownDisplay_notFullScreenShape() {
        assertFalse(HgSplitPreview.fullScreenShape(FULLSCREEN, 1200f, 2608f, 0, 0));
    }

    // showsPreSplit: the card shows the app's last full-screen screenshot instead of the icon

    /** Slack after leaving split, its full-screen screenshot from before held: shown. */
    @Test
    public void iconCardWithPreSplit_showsPreSplit() {
        assertTrue(HgSplitPreview.showsPreSplit(true, false, FULLSCREEN, true,
                1200f, 1292f, 1200f, 2608f, 800, 1740));
    }

    /** The option off: the icon card, as before. */
    @Test
    public void optionOff_iconCard() {
        assertFalse(HgSplitPreview.showsPreSplit(false, false, FULLSCREEN, true,
                1200f, 1292f, 1200f, 2608f, 800, 1740));
    }

    /** A locked app (App lock) never shows an old screenshot. */
    @Test
    public void appLocked_noPreSplit() {
        assertFalse(HgSplitPreview.showsPreSplit(true, true, FULLSCREEN, true,
                1200f, 1292f, 1200f, 2608f, 800, 1740));
    }

    /** No screenshot from before the split: the icon card. */
    @Test
    public void noPreSplit_iconCard() {
        assertFalse(HgSplitPreview.showsPreSplit(true, false, FULLSCREEN, true,
                1200f, 1292f, 0f, 0f, 800, 1740));
    }

    /** A card that is no icon card keeps its own screenshot. */
    @Test
    public void fullScreenSnapshotNow_ownShown() {
        assertFalse(HgSplitPreview.showsPreSplit(true, false, FULLSCREEN, true,
                1200f, 2608f, 1200f, 2608f, 800, 1740));
    }

    /** Half of a split pair card keeps its half. */
    @Test
    public void splitPairHalf_ownShown() {
        assertFalse(HgSplitPreview.showsPreSplit(true, false, MULTI_WINDOW, false,
                1200f, 1292f, 1200f, 2608f, 800, 860));
    }

    /** The screenshot from before was taken in landscape: it does not fit, the icon card. */
    @Test
    public void landscapePreSplit_iconCard() {
        assertFalse(HgSplitPreview.showsPreSplit(true, false, FULLSCREEN, true,
                1200f, 1292f, 2608f, 1200f, 800, 1740));
    }
}
