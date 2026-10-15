package com.android.quickstep;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HgRecentsTaskPolicyTest {
    @Test public void standardApp_isShown() {
        assertTrue(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false));
    }

    @Test public void homeType_isHidden() {
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_HOME, true));
    }

    /** Shell can leave the type unset and still send Nova's HOME intent (task 735). */
    @Test public void homeCategory_isHiddenEvenWhenTypeIsStandard() {
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, true));
    }

    @Test public void recentsType_isHidden() {
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_RECENTS, false));
    }

    /**
     * A task restored from disk after a reboot (undefined type, no activities; Messenger task
     * 725, and 14 apps after the 2026-10-05 18:03 boot) keeps its card, as in AOSP.
     */
    @Test public void restoredTask_isShown() {
        assertTrue(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_UNDEFINED, false));
    }

    /** The task's own component first, then its source component, each once, nulls skipped. */
    @Test public void activityInfoCandidates_inOrderWithoutDuplicates() {
        String main = "com.facebook.orca/com.facebook.messenger.neue.MainActivity";
        String alias = "com.facebook.orca/com.facebook.orca.auth.StartScreenActivity";
        assertEquals(List.of(main, alias), HgTaskActivityInfo.candidates(main, alias));
        assertEquals(List.of(main), HgTaskActivityInfo.candidates(main, main));
        assertEquals(List.of(alias), HgTaskActivityInfo.candidates(null, alias));
        assertEquals(List.of(), HgTaskActivityInfo.<String>candidates(null, null));
    }

    /** App info opened from a recents card (AOSP action or HyperOS's activity) has no card. */
    @Test public void appDetails_isHidden() {
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false,
                "android.settings.APPLICATION_DETAILS_SETTINGS", null));
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false, null,
                "com.miui.securitycenter/com.miui.appmanager.ApplicationsDetailsActivity"));
        assertFalse(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false,
                "miui.intent.action.APP_MANAGER_APPLICATION_DETAIL", null));
    }

    /** The Settings app itself (App info reached inside it) keeps its card. */
    @Test public void settingsApp_isShown() {
        assertTrue(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false,
                "android.intent.action.MAIN", "com.android.settings/.MainSettings"));
        assertTrue(HgRecentsTaskPolicy.showInOverview(ACTIVITY_TYPE_STANDARD, false, null, null));
    }
}
