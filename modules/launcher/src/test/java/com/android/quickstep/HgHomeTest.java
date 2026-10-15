package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HgHomeTest {
    private static final String NOVA = "com.teslacoilsw.launcher";

    @Test public void aLauncherIsItself() {
        assertEquals(NOVA, HgHome.map(NOVA, NOVA + ".Launcher"));
        assertEquals(NOVA, HgHome.map(NOVA, null));
        assertEquals(HgHomeSwitch.MIUI_HOME, HgHome.map(HgHomeSwitch.MIUI_HOME, null));
    }

    /** info.txt said "HOME: android" while no default was set: the chooser is no HOME. */
    @Test public void theChooserIsNone() {
        assertNull(HgHome.map("android", null));
        assertNull(HgHome.map("android", HgHome.RESOLVER_ACTIVITY));
        assertNull(HgHome.map("com.example.resolverhost", HgHome.RESOLVER_ACTIVITY));
    }

    @Test public void aSetupWizardIsNone() {
        assertNull(HgHome.map("com.google.android.setupwizard", null));
        assertNull(HgHome.map("com.android.provision", "com.android.provision.DefaultActivity"));
    }

    @Test public void nothingIsNone() {
        assertNull(HgHome.map(null, null));
        assertNull(HgHome.map("", null));
    }

    /** HgHomeSwitch maps AOSP's getHomeActivities the same way: no notice for the chooser. */
    @Test public void homeSwitchIgnoresTheChooser() {
        HgHomeSwitch s = new HgHomeSwitch();
        assertEquals(HgHomeSwitch.NONE, s.observe(HgHome.map(NOVA, null)));
        assertEquals(HgHomeSwitch.NONE, s.observe(HgHome.map("android", null)));
        assertEquals(HgHomeSwitch.NOTIFY, s.observe(HgHome.map(HgHomeSwitch.MIUI_HOME, null)));
    }
}
