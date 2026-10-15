package hg

import com.android.systemui.shared.recents.utilities.PreviewPositionHelper
import org.junit.Assert.assertEquals
import org.junit.Test

/** A floating window's snapshot is drawn at a full-screen card's scale, centred, never larger. */
class FreeformThumbnailFitTest {
    /** The device: 1200 px wide; the owner's floating Messenger was 602 x 1200. */
    @Test
    fun floatingWindow_keepsScreenScale_centred() {
        val fit = PreviewPositionHelper.hgFreeformFit(602f, 1200f, 1200f, 800f, 1740f)
        assertEquals(800f / 1200f, fit[0], 1e-4f)
        assertEquals((800f - 602f * fit[0]) / 2f, fit[1], 1e-3f)
        assertEquals((1740f - 1200f * fit[0]) / 2f, fit[2], 1e-3f)
    }

    /** A window taller than the card's share of the screen is shrunk to fit the card. */
    @Test
    fun tallWindow_fitsCard() {
        val fit = PreviewPositionHelper.hgFreeformFit(1200f, 3000f, 1200f, 800f, 1740f)
        assertEquals(1740f / 3000f, fit[0], 1e-4f)
        assertEquals(0f, fit[2], 1e-3f)
    }
}
