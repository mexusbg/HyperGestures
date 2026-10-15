package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgModuleStatusTest {
    /** As hg_publish_status writes it, one line (the module suite pins that side). */
    private static final String ROLLED_BACK = "bootId=b-2;state=rolled-back;boot=active;"
            + "health=FAILED: provider not bound at 2 checks in a row, rolled back (restore ok);"
            + "reason=provider not bound at 2 checks in a row, rolled back (restore ok);"
            + "mountMode=auto → mixed (hybrid_mount);mount=metamodule + overlay ok;"
            + "metamoduleMissed=hybrid_mount 170;staleApk=app 49, module 50;powerPending=1;"
            + "restoreGaveUp=1";

    @Test public void parsesEveryFact() {
        HgModuleStatus s = HgModuleStatus.forBoot(ROLLED_BACK, "b-2");
        assertEquals(HgModuleStatus.ROLLED_BACK, s.state);
        assertEquals("active", s.boot);
        assertEquals("FAILED: provider not bound at 2 checks in a row, rolled back (restore ok)",
                s.health);
        assertEquals("provider not bound at 2 checks in a row, rolled back (restore ok)", s.reason);
        assertEquals("auto → mixed (hybrid_mount)", s.mountMode);
        assertEquals("metamodule + overlay ok", s.mount);
        assertEquals("hybrid_mount 170", s.metamoduleMissed);
        assertEquals("app 49, module 50", s.staleApk);
        assertTrue(s.powerPending);
        assertTrue(s.restoreGaveUp);
        assertTrue(s.off());
    }

    /** Only the facts that apply are written; the rest read as empty or false. */
    @Test public void missingFactsAreEmpty() {
        HgModuleStatus s = HgModuleStatus.forBoot(
                "bootId=b-2;state=active;boot=active;health=ok: fine\n", "b-2");
        assertEquals(HgModuleStatus.ACTIVE, s.state);
        assertEquals("ok: fine", s.health);
        assertEquals("", s.reason);
        assertEquals("", s.mountMode);
        assertEquals("", s.metamoduleMissed);
        assertFalse(s.powerPending);
        assertFalse(s.restoreGaveUp);
        assertFalse(s.off());
        assertFalse(HgModuleStatus.forBoot("bootId=x;state=dormant", "x").off());
        assertTrue(HgModuleStatus.forBoot("bootId=x;state=refused", "x").off());
        assertTrue(HgModuleStatus.forBoot("bootId=x;state=not-set-up", "x").off());
    }

    /**
     * Only this boot's value counts: another boot's, one without a boot id or a state, one too
     * long to be ours, or our own boot id unreadable (a stale value can't be told apart) give no
     * status.
     */
    @Test public void onlyThisBootsValueCounts() {
        assertNull(HgModuleStatus.forBoot(ROLLED_BACK, "b-3"));
        assertNull(HgModuleStatus.forBoot(ROLLED_BACK, null));
        assertNull(HgModuleStatus.forBoot(null, "b-2"));
        assertNull(HgModuleStatus.forBoot("state=active", "b-2"));
        assertNull(HgModuleStatus.forBoot("bootId=b-2;state=", "b-2"));
        assertNull(HgModuleStatus.forBoot("", "b-2"));
        StringBuilder big = new StringBuilder("bootId=b-2;state=active;health=");
        while (big.length() <= HgLogCollector.MODULE_VALUE_MAX_CHARS) big.append('x');
        assertNull(HgModuleStatus.forBoot(big.toString(), "b-2"));
    }

    private static HgLogCollector.RunningModule running(String code, String valueBoot) {
        return HgLogCollector.runningModule("0.3.9.39-alpha (" + code + ") boot=" + valueBoot,
                "b-2", null);
    }

    /** The app older than the module running this boot: the system kept the previous APK. */
    @Test public void appBehindTheModule() {
        assertEquals("51", HgModuleStatus.appBehind(50, running("51", "b-2")));
        assertNull(HgModuleStatus.appBehind(51, running("51", "b-2")));
        assertNull(HgModuleStatus.appBehind(52, running("51", "b-2"))); // a newer app: left alone
        assertNull(HgModuleStatus.appBehind(50, running("51", "b-1"))); // an earlier boot's
        assertNull(HgModuleStatus.appBehind(50, running("", "b-2")));
        assertNull(HgModuleStatus.appBehind(50, running("x", "b-2")));
        assertNull(HgModuleStatus.appBehind(50, HgLogCollector.runningModule(
                null, "b-2", "0.3.9.39-alpha (51)"))); // the log only: not this boot's for sure
        assertNull(HgModuleStatus.appBehind(50, null));
    }
}
