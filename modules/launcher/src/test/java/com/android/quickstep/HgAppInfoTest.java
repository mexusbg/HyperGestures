package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgAppInfoTest {
    @Test public void actionAndExtraKeysMatchMiuiHome() {
        assertEquals("miui.intent.action.APP_MANAGER_APPLICATION_DETAIL", HgAppInfo.ACTION);
        assertEquals("miui.intent.extra.USER_ID", HgAppInfo.EXTRA_USER_ID);
        assertEquals("package_name", HgAppInfo.EXTRA_PACKAGE);
        assertEquals("com.miui.securitycenter", HgAppInfo.SECURITY_PACKAGE);
    }

    /** NEW_TASK | EXCLUDE_FROM_RECENTS, so Back from the permission screen stays in App info. */
    @Test public void flagsAreNewTaskAndExcludeFromRecents() {
        assertEquals(0x10000000 | 0x00800000, HgAppInfo.FLAGS);
        assertEquals(0x10800000, HgAppInfo.FLAGS);
    }
}
