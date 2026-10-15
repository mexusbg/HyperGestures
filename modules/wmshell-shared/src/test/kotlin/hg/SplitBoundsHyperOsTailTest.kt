package hg

import com.android.wm.shell.shared.split.SplitBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HyperOS's Shell appends four fields to SplitBounds' parcel; they are read only on HyperOS,
 * recognised by ro.mi.os.version.name (the device: "OS4.0").
 */
class SplitBoundsHyperOsTailTest {
    @Test
    fun hyperOs_readsTail() {
        assertTrue(SplitBounds.hgHasHyperOsTail("OS4.0"))
    }

    @Test
    fun otherAndroid_doesNot() {
        assertFalse(SplitBounds.hgHasHyperOsTail(""))
        assertFalse(SplitBounds.hgHasHyperOsTail(null))
    }
}
