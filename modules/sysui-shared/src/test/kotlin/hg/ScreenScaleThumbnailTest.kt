package hg

import com.android.systemui.shared.recents.utilities.PreviewPositionHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which snapshots a card draws at a full-screen card's scale (hgFreeformFit) instead of filling
 * the card. The device: 1200 x 2608 px; a card 800 x 1740.
 */
class ScreenScaleThumbnailTest {
    private val fullscreen = 1
    private val freeform = 5
    private val multiWindow = 6
    private val single = -1 // SPLIT_POSITION_UNDEFINED
    private val topOrLeft = 0 // SPLIT_POSITION_TOP_OR_LEFT

    /** Slack after leaving split (owner, 2026-11-03): HyperOS keeps the half-screen snapshot. */
    @Test
    fun appThatLeftSplit_halfScreenSnapshot_drawnAtScreenScale() {
        assertTrue(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, fullscreen, single, 0, 1200f, 1292f, 800, 1740))
    }

    @Test
    fun fullScreenSnapshot_fillsCard() {
        assertFalse(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, fullscreen, single, 0, 1200f, 2608f, 800, 1740))
    }

    /** A half of a split pair card fills its half, as before. */
    @Test
    fun halfOfSplitPairCard_fillsItsHalf() {
        assertFalse(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, fullscreen, topOrLeft, 0, 1200f, 1292f, 800, 860))
    }

    /**
     * An app still in HyperOS's split (its other half home) fills its card, as its live tile does
     * (owner, 2026-11-03: centred, the card showed home through it).
     */
    @Test
    fun snapshotTakenInSplit_fillsCardLikeItsLiveTile() {
        assertFalse(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, multiWindow, single, 0, 1200f, 1292f, 800, 1740))
    }

    /** The running app's window (TaskViewSimulator: no snapshot) fills its card, as before. */
    @Test
    fun liveWindow_fillsCard() {
        assertFalse(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                false, fullscreen, single, 0, 1200f, 1292f, 800, 1740))
    }

    /** A landscape snapshot in portrait recents is AOSP's rotation case, not this one. */
    @Test
    fun rotatedSnapshot_leftToRotation() {
        assertFalse(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, fullscreen, single, 1, 2608f, 1200f, 800, 1740))
    }

    /** Floating windows keep the 2026-10-07 behaviour, whatever their shape. */
    @Test
    fun floatingWindow_alwaysAtScreenScale() {
        assertTrue(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, freeform, single, 0, 1200f, 2608f, 800, 1740))
    }

    @Test
    fun unmeasuredCard_fillsAsBefore() {
        assertFalse(
            PreviewPositionHelper.hgDrawsAtScreenScale(
                true, fullscreen, single, 0, 1200f, 1292f, 0, 0))
    }
}
