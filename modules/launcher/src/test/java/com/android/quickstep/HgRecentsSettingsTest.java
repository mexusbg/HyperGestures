package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgRecentsSettingsTest {
    /** Every MIUI-style recents feature is on unless its setting is exactly "0". */
    @Test public void onUnlessZero() {
        assertTrue(HgRecentsSettings.isOn((String) null));
        assertTrue(HgRecentsSettings.isOn("1"));
        assertTrue(HgRecentsSettings.isOn(""));
        assertFalse(HgRecentsSettings.isOn("0"));
    }
}
