package com.android.quickstep;

import static com.android.quickstep.HgSettingsSections.MODE_ACTIVE;
import static com.android.quickstep.HgSettingsSections.MODE_ACTIVE_REBOOT;
import static com.android.quickstep.HgSettingsSections.MODE_OFF;
import static com.android.quickstep.HgSettingsSections.MODE_STOCK;
import static com.android.quickstep.HgSettingsSections.MODE_STOCK_REBOOT;
import static com.android.quickstep.HgStatusCard.FAILED;
import static com.android.quickstep.HgStatusCard.NEUTRAL;
import static com.android.quickstep.HgStatusCard.OK;
import static com.android.quickstep.HgStatusCard.PENDING;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class HgStatusCardTest {
    private static final String BOOT = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    /** As the module's hg_app_fact: "<bootId> <pid> <uptimeMs>", this boot only. */
    @Test public void factPidParsesOnlyThisBootsValue() {
        assertEquals(4321, HgStatusCard.factPid(BOOT + " 4321 99887", BOOT));
        assertEquals(0, HgStatusCard.factPid("another 4321 99887", BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 4321 99887", null));
        assertEquals(0, HgStatusCard.factPid(null, BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 4321", BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 4321 99 1", BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 0432 99887", BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 12a 99887", BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 4321 9x", BOOT));
        assertEquals(0, HgStatusCard.factPid(BOOT + " 1234567890 1", BOOT));
    }

    /** /proc/<pid>/cmdline is NUL-separated; only our main process counts. */
    @Test public void ourMainProcessOnly() {
        assertEquals(true, HgStatusCard.isMainProcess("com.hypergestures\0", "com.hypergestures"));
        assertEquals(true, HgStatusCard.isMainProcess("com.hypergestures", "com.hypergestures"));
        assertEquals(false, HgStatusCard.isMainProcess("com.hypergestures:settings\0",
                "com.hypergestures"));
        assertEquals(false, HgStatusCard.isMainProcess(null, "com.hypergestures"));
        assertEquals(false, HgStatusCard.isMainProcess("", "com.hypergestures"));
    }

    @Test public void mountedWhenWeRunFromTheSystemImage() {
        check(HgStatusCard.mount(true, "ok", ""), HgStatusCard.MOUNTED, OK);
        check(HgStatusCard.mount(true, "", ""), HgStatusCard.MOUNTED, OK);
        check(HgStatusCard.mount(false, "ok", ""), HgStatusCard.NOT_MOUNTED, FAILED);
        // The module's own mount failed: not mounted, whatever path we came from.
        check(HgStatusCard.mount(true, "FAILED: overlay on /system/priv-app; rolled back", ""),
                HgStatusCard.NOT_MOUNTED, FAILED);
        check(HgStatusCard.mount(true, "metamodule + overlay FAILED: staging the payload", ""),
                HgStatusCard.NOT_MOUNTED, FAILED);
    }

    /** A metamodule that mounts the app is named; the module's own mount needs no name. */
    @Test public void mountNamesTheMetamodule() {
        HgStatusCard.Row r = HgStatusCard.mount(true, "metamodule + overlay ok",
                "auto \u2192 mixed (hybrid_mount)");
        check(r, HgStatusCard.MOUNTED_VIA, OK);
        assertEquals("hybrid_mount", r.detail);
        check(HgStatusCard.mount(true, "ok", "auto \u2192 self (no active metamodule)"),
                HgStatusCard.MOUNTED, OK);
        check(HgStatusCard.mount(true, "ok", "self (MOUNT=self)"), HgStatusCard.MOUNTED, OK);
        check(HgStatusCard.mount(true, "ok", "mixed ()"), HgStatusCard.MOUNTED, OK);
    }

    /** The module's state this boot, with the health check's verdict while it is active. */
    @Test public void moduleState() {
        check(HgStatusCard.module("active", "ok: provider bound, no crash loop"),
                HgStatusCard.STATE_ACTIVE, OK);
        check(HgStatusCard.module("active", "running (checks for 120 s after setup)"),
                HgStatusCard.STATE_CHECKING, PENDING);
        check(HgStatusCard.module("active", "FAILED: provider not bound"),
                HgStatusCard.STATE_HEALTH_FAILED, FAILED);
        check(HgStatusCard.module("active", ""), HgStatusCard.STATE_ACTIVE, OK);
        check(HgStatusCard.module("starting", ""), HgStatusCard.STATE_STARTING, PENDING);
        // HyperOS's own gestures run by choice (MiuiHome is HOME): nothing wrong.
        check(HgStatusCard.module("dormant", ""), HgStatusCard.STATE_STANDBY, OK);
        check(HgStatusCard.module("rolled-back", ""), HgStatusCard.STATE_ROLLED_BACK, FAILED);
        check(HgStatusCard.module("not-set-up", ""), HgStatusCard.STATE_NOT_SET_UP, FAILED);
        check(HgStatusCard.module("refused", ""), HgStatusCard.STATE_REFUSED, FAILED);
        check(HgStatusCard.module(null, ""), HgStatusCard.STATE_NONE, PENDING);
        HgStatusCard.Row other = HgStatusCard.module("paused", "");
        check(other, HgStatusCard.STATE_OTHER, NEUTRAL);
        assertEquals("paused", other.detail);
    }

    /** Problems get a row each, only when present. */
    @Test public void problemsOnlyWhenPresent() {
        assertEquals(0, HgStatusCard.problems("", false, false).size());
        List<HgStatusCard.Row> all = HgStatusCard.problems("hybrid_mount 170", true, true);
        assertEquals(3, all.size());
        check(all.get(0), HgStatusCard.PROBLEM_METAMODULE, PENDING);
        assertEquals("hybrid_mount", all.get(0).detail);
        check(all.get(1), HgStatusCard.PROBLEM_BATTERY, PENDING);
        check(all.get(2), HgStatusCard.PROBLEM_RESTORE, FAILED);
    }

    @Test public void oursWhenTheFactHolds() {
        for (int mode : new int[] {MODE_ACTIVE, MODE_ACTIVE_REBOOT, MODE_STOCK, MODE_OFF}) {
            check(HgStatusCard.provider(true, mode, false, true), HgStatusCard.BY_US, OK);
        }
    }

    @Test public void systemIsExpectedWhileHyperOsRuns() {
        check(HgStatusCard.provider(false, MODE_STOCK, false, true), HgStatusCard.BY_SYSTEM, OK);
        check(HgStatusCard.provider(false, MODE_STOCK_REBOOT, false, true),
                HgStatusCard.BY_SYSTEM, OK);
    }

    @Test public void systemWhileOursShouldRunIsAFailure() {
        check(HgStatusCard.provider(false, MODE_ACTIVE, false, true), HgStatusCard.BY_SYSTEM,
                FAILED);
        check(HgStatusCard.provider(false, MODE_ACTIVE_REBOOT, false, true),
                HgStatusCard.BY_SYSTEM, FAILED);
        check(HgStatusCard.provider(false, MODE_OFF, false, true), HgStatusCard.BY_SYSTEM,
                FAILED);
        // Setup still running: not bound yet is expected for a while.
        check(HgStatusCard.provider(false, MODE_ACTIVE, true, true), HgStatusCard.BY_SYSTEM,
                PENDING);
    }

    /** hypergestures_health_facts=0: nothing is published, so nothing can be said. */
    @Test public void unknownWithoutFacts() {
        check(HgStatusCard.provider(false, MODE_ACTIVE, false, false), HgStatusCard.UNKNOWN,
                PENDING);
    }

    @Test public void hooksListTheirScopes() {
        HgStatusCard.Row r = HgStatusCard.hooks(List.of("systemui", "aod"), true);
        check(r, HgStatusCard.HOOKS_LOADED, OK);
        assertEquals(List.of("systemui", "aod"), r.scopes);
        check(HgStatusCard.hooks(List.of("systemui"), false), HgStatusCard.HOOKS_LOADED, OK);
        check(HgStatusCard.hooks(List.of(), true), HgStatusCard.HOOKS_NOT_LOADED, FAILED);
        // The fix is off: hooks that did not load need no attention.
        check(HgStatusCard.hooks(List.of(), false), HgStatusCard.HOOKS_NOT_LOADED, NEUTRAL);
    }

    @Test public void rebootReasons() {
        assertEquals(List.of(), HgStatusCard.rebootReasons(MODE_ACTIVE,
                HgHideGestureLine.STATUS_HIDDEN, false, false));
        assertEquals(List.of(HgStatusCard.REASON_HOME), HgStatusCard.rebootReasons(
                MODE_ACTIVE_REBOOT, HgHideGestureLine.STATUS_NONE, false, false));
        assertEquals(List.of(HgStatusCard.REASON_HOME), HgStatusCard.rebootReasons(
                MODE_STOCK_REBOOT, HgHideGestureLine.STATUS_NONE, false, false));
        assertEquals(List.of(HgStatusCard.REASON_NAV_LINE), HgStatusCard.rebootReasons(
                MODE_ACTIVE, HgHideGestureLine.STATUS_HIDDEN_AFTER_REBOOT, false, false));
        assertEquals(List.of(HgStatusCard.REASON_NAV_LINE), HgStatusCard.rebootReasons(
                MODE_ACTIVE, HgHideGestureLine.STATUS_SHOWN_AFTER_REBOOT, false, false));
        assertEquals(List.of(HgStatusCard.REASON_HOME, HgStatusCard.REASON_NAV_LINE,
                HgStatusCard.REASON_MODULE_UPDATE, HgStatusCard.REASON_APP_UPDATE),
                HgStatusCard.rebootReasons(MODE_ACTIVE_REBOOT,
                        HgHideGestureLine.STATUS_HIDDEN_AFTER_REBOOT, true, true));
        // An overlay the system refused is a failure, not something a reboot fixes.
        assertEquals(List.of(), HgStatusCard.rebootReasons(MODE_ACTIVE,
                HgHideGestureLine.STATUS_NOT_APPLIED, false, false));
    }

    @Test public void rebootRow() {
        check(HgStatusCard.reboot(List.of()), HgStatusCard.REBOOT_NOT_NEEDED, OK);
        HgStatusCard.Row r = HgStatusCard.reboot(List.of(HgStatusCard.REASON_NAV_LINE));
        check(r, HgStatusCard.REBOOT_PENDING, PENDING);
        assertEquals(List.of(HgStatusCard.REASON_NAV_LINE), r.reasons);
    }

    private static void check(HgStatusCard.Row row, int value, int level) {
        assertEquals("value", value, row.value);
        assertEquals("level", level, row.level);
    }
}
