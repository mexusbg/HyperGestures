package com.android.quickstep;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** App info by AOSP's own component (no action) has no card either (HgRecentsTaskPolicy). */
public class HgRecentsTaskPolicyAppDetailsTest {
    @Test public void aospAppDetailsComponent_isHidden() {
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false, null,
                HgRecentsTaskPolicy.AOSP_APP_DETAILS));
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false, null,
                "com.android.settings/.applications.InstalledAppDetails"));
    }
}
