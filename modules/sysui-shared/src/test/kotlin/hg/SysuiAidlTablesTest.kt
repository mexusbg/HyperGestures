package hg

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Data-driven: ILauncherProxy and ISystemUiProxy must agree code for code with the device table
 * prebuilts/device/hyperos4-sysui-aidl-tables.txt (from the firmware's SystemUI). A failure
 * means the vendored AOSP 17 AIDL differs from the device: never edit the table to match; the AIDL
 * needs a ruling (an aosp-patch). HyperOS-only codes (ILauncherProxy 58, 101–105) are skipped by name.
 */
class SysuiAidlTablesTest {
    private val table = File(requireNotNull(System.getProperty("hg.sysuiAidlTables")) { "hg.sysuiAidlTables not set" })
    private val checked = AidlTables.check(AidlTables.parse(table))

    @Test fun bothProxiesMatchTheDevice() {
        assertEquals("wire mismatches", emptyList<String>(), checked.problems)
    }

    /** Every AOSP-named row of the device table matched a compiled method (nothing silently skipped). */
    @Test fun everyAospRowIsChecked() {
        assertEquals(
            mapOf(
                "com.android.systemui.shared.recents.ILauncherProxy" to 27,
                "com.android.systemui.shared.recents.ISystemUiProxy" to 29,
            ),
            checked.matched,
        )
    }
}
