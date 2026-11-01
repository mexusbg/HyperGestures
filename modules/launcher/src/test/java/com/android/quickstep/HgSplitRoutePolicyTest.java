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

    private static final String DESC = "com.android.wm.shell.sosc.ISoScSplitScreen";

    /** The phone's Shell confirmed every SoSc call, and the binder is ISoScSplitScreen. */
    @Test public void confirmedCallsAndDescriptor_soscUsable() {
        assertTrue(HgSplitRoutePolicy.soscBinderUsable(true, DESC, DESC));
    }

    /** Any firmware works; only calls the phone's Shell did not confirm turn SoSc off. */
    @Test public void unconfirmedCalls_soscNotUsable() {
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(false, DESC, DESC));
    }

    /** The binder under the bundle key is some other interface, or its descriptor is unreadable. */
    @Test public void otherOrUnknownDescriptor_soscNotUsable() {
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(true,
                "com.android.wm.shell.splitscreen.ISplitScreen", DESC));
        assertFalse(HgSplitRoutePolicy.soscBinderUsable(true, null, DESC));
    }
}
