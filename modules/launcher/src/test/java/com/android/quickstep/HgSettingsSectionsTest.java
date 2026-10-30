package com.android.quickstep;

import static com.android.quickstep.HgSettingsSections.ABOUT;
import static com.android.quickstep.HgSettingsSections.GESTURES;
import static com.android.quickstep.HgSettingsSections.LOGS;
import static com.android.quickstep.HgSettingsSections.MODE_ACTIVE;
import static com.android.quickstep.HgSettingsSections.MODE_ACTIVE_REBOOT;
import static com.android.quickstep.HgSettingsSections.MODE_OFF;
import static com.android.quickstep.HgSettingsSections.MODE_STOCK;
import static com.android.quickstep.HgSettingsSections.MODE_STOCK_REBOOT;
import static com.android.quickstep.HgSettingsSections.STATUS;
import static com.android.quickstep.HgSettingsSections.RECENTS;
import static com.android.quickstep.HgSettingsSections.SYSTEM;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgSettingsSectionsTest {
    private static final String MIUI = "com.miui.home";
    private static final String NOVA = "com.teslacoilsw.launcher";

    private static String shown(String state, String home) {
        StringBuilder sb = new StringBuilder();
        String[] names = {"status", "recents", "gestures", "system", "logs", "about"};
        int mode = HgSettingsSections.mode(state, home);
        for (int s : new int[] {STATUS, RECENTS, GESTURES, SYSTEM, LOGS, ABOUT}) {
            if (HgSettingsSections.shows(s, mode)) sb.append(names[s]).append(' ');
        }
        return sb.toString().trim();
    }

    /** No status for this boot: the live HOME decides, as before. */
    @Test public void withoutAStatusHomeDecides() {
        assertEquals("status system logs about", shown(null, MIUI));
        assertEquals("status recents gestures system logs about", shown(null, NOVA));
        assertEquals("status recents gestures system logs about", shown(null, "com.hypergestures"));
        // No HOME resolved (or the chooser): as the module, treated as ours being active.
        assertEquals("status recents gestures system logs about", shown(null, null));
        assertEquals("status recents gestures system logs about", shown(null, ""));
        assertEquals(MODE_STOCK, HgSettingsSections.mode(null, MIUI));
        assertEquals(MODE_ACTIVE, HgSettingsSections.mode(null, NOVA));
    }

    /** An active boot keeps ours until the reboot, with a note when MiuiHome is HOME now. */
    @Test public void activeBootKeepsOursUntilTheReboot() {
        for (String state : new String[] {HgModuleStatus.ACTIVE, HgModuleStatus.STARTING}) {
            assertEquals(MODE_ACTIVE, HgSettingsSections.mode(state, NOVA));
            assertEquals(MODE_ACTIVE, HgSettingsSections.mode(state, null));
            assertEquals(MODE_ACTIVE_REBOOT, HgSettingsSections.mode(state, MIUI));
            assertEquals("status recents gestures system logs about", shown(state, NOVA));
            assertEquals("status recents gestures system logs about", shown(state, MIUI));
        }
    }

    /** A dormant boot runs HyperOS's until the reboot, with a note when another HOME is set. */
    @Test public void dormantBootHidesOursUntilTheReboot() {
        String d = HgModuleStatus.DORMANT;
        assertEquals(MODE_STOCK, HgSettingsSections.mode(d, MIUI));
        assertEquals(MODE_STOCK, HgSettingsSections.mode(d, null));
        assertEquals(MODE_STOCK, HgSettingsSections.mode(d, ""));
        assertEquals(MODE_STOCK_REBOOT, HgSettingsSections.mode(d, NOVA));
        assertEquals("status system logs about", shown(d, MIUI));
        assertEquals("status system logs about", shown(d, NOVA));
    }

    /** Still starting on a boot post-fs-data made dormant: HyperOS's run, so it is dormant. */
    @Test public void startingOnADormantBootIsDormant() {
        String s = HgModuleStatus.STARTING;
        assertEquals(MODE_STOCK, HgSettingsSections.mode(s, HgModuleStatus.DORMANT, MIUI));
        assertEquals(MODE_STOCK_REBOOT, HgSettingsSections.mode(s, HgModuleStatus.DORMANT, NOVA));
        assertEquals(MODE_ACTIVE, HgSettingsSections.mode(s, HgModuleStatus.ACTIVE, NOVA));
        assertEquals(MODE_ACTIVE, HgSettingsSections.mode(s, null, NOVA));
        assertEquals(MODE_STOCK, HgSettingsSections.mode(HgModuleStatus.DORMANT,
                HgModuleStatus.DORMANT, MIUI));
    }

    /** Rolled back, not set up or refused: ours hidden whatever HOME is, the note says why. */
    @Test public void offBootsHideOurs() {
        for (String state : new String[] {HgModuleStatus.ROLLED_BACK, HgModuleStatus.NOT_SET_UP,
                HgModuleStatus.REFUSED}) {
            for (String home : new String[] {MIUI, NOVA, null}) {
                assertEquals(MODE_OFF, HgSettingsSections.mode(state, home));
                assertEquals("status system logs about", shown(state, home));
            }
        }
    }

    /** A state this app does not know (a newer module): as without a status. */
    @Test public void unknownStateGoesByHome() {
        assertEquals(MODE_STOCK, HgSettingsSections.mode("paused", MIUI));
        assertEquals(MODE_ACTIVE, HgSettingsSections.mode("paused", NOVA));
    }

    /** The status card at the top: on, standing by (HyperOS's run) or off, by the page's mode. */
    @Test public void statusKindFollowsTheMode() {
        assertEquals(HgSettingsSections.STATUS_ON, HgSettingsSections.statusKind(MODE_ACTIVE));
        assertEquals(HgSettingsSections.STATUS_ON,
                HgSettingsSections.statusKind(MODE_ACTIVE_REBOOT));
        assertEquals(HgSettingsSections.STATUS_STANDBY,
                HgSettingsSections.statusKind(MODE_STOCK));
        assertEquals(HgSettingsSections.STATUS_STANDBY,
                HgSettingsSections.statusKind(MODE_STOCK_REBOOT));
        assertEquals(HgSettingsSections.STATUS_OFF, HgSettingsSections.statusKind(MODE_OFF));
    }
}
