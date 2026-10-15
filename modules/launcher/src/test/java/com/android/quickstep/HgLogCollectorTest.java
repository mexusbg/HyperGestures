package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.TimeZone;

public class HgLogCollectorTest {
    /** HyperGestures-logs-<yyyyMMdd-HHmmss>.zip, local time. */
    @Test public void zipName() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            // 2026-10-10 08:05:09 UTC
            assertEquals("HyperGestures-logs-20261010-080509.zip",
                    HgLogCollector.zipName(1791619509000L));
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /** The module version comes from hg_log_version's boot line, as hg_log prefixes it. */
    @Test public void moduleVersionOf() {
        assertEquals("0.3.9.24-alpha (36)", HgLogCollector.moduleVersionOf(
                "2026-10-10 08:05:09 service: module version 0.3.9.24-alpha (36)"));
        assertNull(HgLogCollector.moduleVersionOf("2026-10-10 08:05:09 service: module version "));
        assertNull(HgLogCollector.moduleVersionOf("2026-10-10 08:05:09 boot_completed reached"));
    }

    /** The latest boot's line wins; a cut first line (the tail read) is harmless. */
    @Test public void moduleVersionIn() {
        assertEquals("0.3.9.25-alpha (37)", HgLogCollector.moduleVersionIn(
                "ion 0.3.9.1\n"
                + "2026-10-09 07:00:00 service: module version 0.3.9.24-alpha (36)\n"
                + "2026-10-09 07:00:01 boot_completed reached\n"
                + "2026-10-10 08:05:09 service: module version 0.3.9.25-alpha (37)\n"
                + "2026-10-10 08:05:10 setup done\n"));
        assertNull(HgLogCollector.moduleVersionIn("2026-10-10 08:05:10 setup done\n"));
        assertNull(HgLogCollector.moduleVersionIn(""));
    }

    /** hg_publish_version's values: the log line's display form, with " boot=<id>" or not. */
    @Test public void parseModuleVersion() {
        HgLogCollector.ModuleVersion v =
                HgLogCollector.parseModuleVersion("0.3.9.38-alpha (50) boot=1b2c-77");
        assertEquals("0.3.9.38-alpha", v.name);
        assertEquals("50", v.code);
        assertEquals("0.3.9.38-alpha (50)", v.display());
        assertEquals("0.3.9.39-alpha (51)",
                HgLogCollector.parseModuleVersion(" 0.3.9.39-alpha (51) \n").display());
        assertEquals("0.3.9.39-alpha", // the module.prop without a versionCode
                HgLogCollector.parseModuleVersion("0.3.9.39-alpha () boot=x").display());
        assertNull(HgLogCollector.parseModuleVersion("0.3.9.39-alpha"));
        assertNull(HgLogCollector.parseModuleVersion("(51) boot=x"));
        assertNull(HgLogCollector.parseModuleVersion("0.3.9.39-alpha (5x)"));
        assertNull(HgLogCollector.parseModuleVersion("version=0.3.9.38-alpha\nversionCode=50"));
        assertNull(HgLogCollector.parseModuleVersion(""));
        assertNull(HgLogCollector.parseModuleVersion(null));
        StringBuilder big = new StringBuilder("0.3.9.39-alpha (51) boot=");
        while (big.length() <= HgLogCollector.MODULE_VALUE_MAX_CHARS) big.append('x');
        assertNull(HgLogCollector.parseModuleVersion(big.toString()));
    }

    @Test public void bootIdOf() {
        assertEquals("1b2c-77", HgLogCollector.bootIdOf("0.3.9.38-alpha (50) boot=1b2c-77"));
        assertNull(HgLogCollector.bootIdOf("0.3.9.38-alpha (50) boot="));
        assertNull(HgLogCollector.bootIdOf("0.3.9.38-alpha (50)"));
        assertNull(HgLogCollector.bootIdOf(null));
    }

    private static HgLogCollector.ModuleVersion v(String display) {
        return HgLogCollector.ModuleVersion.fromDisplay(display);
    }

    /**
     * An installed update is named only while it is not the running version: the names differ,
     * or the codes do where both name one.
     */
    @Test public void pendingUpdate() {
        HgLogCollector.ModuleVersion update =
                HgLogCollector.parseModuleVersion("0.3.9.39-alpha (51)");
        assertSame(update, HgLogCollector.pendingUpdate(v("0.3.9.38-alpha (50)"), update));
        assertSame(update, HgLogCollector.pendingUpdate(null, update));
        assertSame(update, HgLogCollector.pendingUpdate(v("0.3.9.39-alpha (52)"), update));
        assertNull(HgLogCollector.pendingUpdate(v("0.3.9.39-alpha (51)"), update));
        assertNull(HgLogCollector.pendingUpdate(v("0.3.9.39-alpha"), update));
        assertNull(HgLogCollector.pendingUpdate(v("0.3.9.39-alpha (51)"),
                HgLogCollector.parseModuleVersion("0.3.9.39-alpha ()")));
        assertNull(HgLogCollector.pendingUpdate(v("0.3.9.38-alpha (50)"), null));
    }

    /** The log line's form read back. */
    @Test public void fromDisplay() {
        assertEquals("0.3.9.38-alpha", v("0.3.9.38-alpha (50)").name);
        assertEquals("50", v("0.3.9.38-alpha (50)").code);
        assertEquals("0.3.9.38-alpha", v(" 0.3.9.38-alpha ").name);
        assertEquals("", v("0.3.9.38-alpha").code);
        assertNull(v(null));
        assertNull(v(" "));
    }

    private static final String RUNNING = "0.3.9.39-alpha (51) boot=b-2";

    /**
     * hypergestures_module_version counts only for this boot; an earlier boot's means the module
     * has not published this boot (not running): the log's version then, marked as an earlier
     * boot's.
     */
    @Test public void runningModule() {
        HgLogCollector.RunningModule r =
                HgLogCollector.runningModule(RUNNING, "b-2", "0.3.9.38-alpha (50)");
        assertEquals(HgLogCollector.FROM_SETTING, r.source);
        assertEquals("0.3.9.39-alpha (51)", r.version.display());
        assertTrue(r.running());
        r = HgLogCollector.runningModule(RUNNING, "b-3", "0.3.9.38-alpha (50)");
        assertEquals(HgLogCollector.FROM_EARLIER_BOOT, r.source);
        assertEquals("0.3.9.38-alpha (50)", r.version.display());
        assertFalse(r.running());
        r = HgLogCollector.runningModule(RUNNING, "b-3", null);
        assertEquals(HgLogCollector.FROM_EARLIER_BOOT, r.source);
        assertEquals("0.3.9.39-alpha (51)", r.version.display());
        // No boot id in the value: not this boot's.
        assertEquals(HgLogCollector.FROM_EARLIER_BOOT, HgLogCollector.runningModule(
                "0.3.9.39-alpha (51)", "b-3", null).source);
        // Our boot id unreadable: the value can't be placed, so the module is unknown (never
        // claimed running), as HgModuleStatus.forBoot gives no status then.
        r = HgLogCollector.runningModule(RUNNING, null, "0.3.9.38-alpha (50)");
        assertEquals(HgLogCollector.FROM_NONE, r.source);
        assertNull(r.version);
        assertFalse(r.running());
        assertEquals("unknown", HgLogCollector.describe(r));
        assertFalse(HgLogCollector.isThisBoot("b-2", null));
        assertTrue(HgLogCollector.isThisBoot("b-2", "b-2"));
        assertNull(HgModuleStatus.appBehind(50, r));
        // Unset (not written): the log, as before.
        r = HgLogCollector.runningModule(null, "b-3", "0.3.9.38-alpha (50)");
        assertEquals(HgLogCollector.FROM_LOG, r.source);
        assertTrue(r.running());
        r = HgLogCollector.runningModule("garbage", "b-3", null);
        assertEquals(HgLogCollector.FROM_NONE, r.source);
        assertNull(r.version);
    }

    @Test public void describeForInfo() {
        assertEquals("0.3.9.39-alpha (51)", HgLogCollector.describe(
                HgLogCollector.runningModule(RUNNING, "b-2", null)));
        assertEquals("not running this boot (last: 0.3.9.39-alpha (51), an earlier boot)",
                HgLogCollector.describe(HgLogCollector.runningModule(RUNNING, "b-1", null)));
        assertEquals("0.3.9.38-alpha (50) (from the log: no version setting)",
                HgLogCollector.describe(
                        HgLogCollector.runningModule(null, "b-1", "0.3.9.38-alpha (50)")));
        assertEquals("unknown",
                HgLogCollector.describe(HgLogCollector.runningModule(null, "b-1", null)));
    }

    /** hypergestures_module_status's pairs: ';' separates them, as a newline does. */
    @Test public void parseKeyValues() {
        java.util.Map<String, String> m =
                HgLogCollector.parseKeyValues("a=1;\r\n b = two words ;=x;none\na=3;c=;d=x=y");
        assertEquals("3", m.get("a"));
        assertEquals("two words", m.get("b"));
        assertEquals("", m.get("c"));
        assertEquals("x=y", m.get("d"));
        assertEquals(4, m.size());
    }

    /** The module's values are listed in info.txt, set or not. */
    @Test public void infoListsTheModuleValues() {
        java.util.List<String> keys = java.util.Arrays.asList(HgLogCollector.GLOBAL_KEYS);
        assertTrue(keys.contains("hypergestures_module_version"));
        assertTrue(keys.contains("hypergestures_module_update"));
        assertTrue(keys.contains("hypergestures_module_status"));
    }

    /** Filtered logs leave out the activity manager's every-app dumps; ours is always there. */
    @Test public void filteredDumpsAreOursOnly() {
        assertEquals("com.hypergestures/com.android.quickstep.TouchInteractionService",
                HgLogCollector.DUMPSYS_OURS[3]);
        assertEquals(2, HgLogCollector.DUMPSYS_SYSTEM.length);
    }
}
