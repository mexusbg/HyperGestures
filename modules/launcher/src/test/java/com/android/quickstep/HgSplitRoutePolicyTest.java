package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgSplitRoutePolicyTest {
    /** The phone: SystemUI passes the SoSc binder and wmshell.sosc_enable is 1. */
    @Test public void soscBinderAndEnabled_useSoSc() {
        assertTrue(HgSplitRoutePolicy.useSoSc(true, 1));
    }

    @Test public void soscDisabled_useAospSplit() {
        assertFalse(HgSplitRoutePolicy.useSoSc(true, 0));
    }

    @Test public void noSoscBinder_useAospSplit() {
        assertFalse(HgSplitRoutePolicy.useSoSc(false, 1));
    }

    /** Unset or unreadable setting: Shell's own default, SoSc on. */
    @Test public void defaultIsShellsDefault() {
        assertEquals(1, HgSplitRoutePolicy.SOSC_ENABLE_DEFAULT);
        assertTrue(HgSplitRoutePolicy.useSoSc(true, HgSplitRoutePolicy.SOSC_ENABLE_DEFAULT));
    }

    private static final String FP = "Xiaomi/nezha_eea/nezha:17/CP2A.260605.016/OS4.0.0.11.XPAEUXM:user/release-keys";
    private static final String DESC = "com.android.wm.shell.sosc.ISoScSplitScreen";

    /** The build the codes were taken from, and the binder is ISoScSplitScreen. */
    @Test public void matchingFirmwareAndDescriptor_soscUsable() {
        assertTrue(HgSplitRoutePolicy.soscBinderUsable(FP, FP, DESC, DESC));
    }

    /** An OTA: the pinned transaction codes may now name other Shell methods. */
    @Test public void otherFirmware_soscNotUsable() {
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(
                "Xiaomi/nezha_eea/nezha:17/CP2A.260705.020/OS4.0.1.0.XPAEUXM:user/release-keys",
                FP, DESC, DESC));
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(null, FP, DESC, DESC));
    }

    @Test public void noExpectedFingerprint_soscNotUsable() {
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(FP, "", DESC, DESC));
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(FP, null, DESC, DESC));
        assertFalse(HgSplitRoutePolicy.soscBinderUsable("", "", DESC, DESC));
    }

    /** The binder under the bundle key is some other interface, or its descriptor is unreadable. */
    @Test public void otherOrUnknownDescriptor_soscNotUsable() {
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(FP, FP,
                "com.android.wm.shell.splitscreen.ISplitScreen", DESC));
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(FP, FP, null, DESC));
    }
}
