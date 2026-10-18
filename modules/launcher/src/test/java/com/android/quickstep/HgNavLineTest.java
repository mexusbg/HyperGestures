package com.android.quickstep;

import static com.android.quickstep.HgNavLine.ACTION_CTS;
import static com.android.quickstep.HgNavLine.ACTION_NONE;
import static com.android.quickstep.HgNavLine.ACTION_OTHER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgNavLineTest {
    /** As MiuiHome's Settings.Global.getInt(hide_gesture_line, 0) != 0. */
    @Test public void hiddenWhenNonZero() {
        assertTrue(HgNavLine.isHidden("1"));
        assertTrue(HgNavLine.isHidden("2"));
        assertFalse(HgNavLine.isHidden("0"));
        assertFalse(HgNavLine.isHidden((String) null));
        assertFalse(HgNavLine.isHidden(""));
        assertFalse(HgNavLine.isHidden("true")); // getInt's default on a non-number
    }

    @Test public void longPressValues() {
        assertEquals(ACTION_CTS, HgNavLine.action("launch_google_search", null));
        assertEquals(ACTION_NONE, HgNavLine.action("none", null));
        // Another app's entity type (MiuiHome runs it; we cannot).
        assertEquals(ACTION_OTHER, HgNavLine.action("VoiceAssistant", null));
        // An explicit choice wins over the older switch.
        assertEquals(ACTION_CTS, HgNavLine.action("launch_google_search", "none"));
    }

    /** Unset: Circle to Search unless the older switch long_press_home_key_no_ui is "none". */
    @Test public void unsetFollowsTheOlderSwitch() {
        assertEquals(ACTION_CTS, HgNavLine.action(null, null));
        assertEquals(ACTION_CTS, HgNavLine.action("", "launch_google_search"));
        assertEquals(ACTION_NONE, HgNavLine.action(null, "none"));
        assertEquals(ACTION_NONE, HgNavLine.action("", "none"));
    }

    /** Circle to Search only on a visible line set to it, as MiuiHome's onLongPress. */
    @Test public void longPressStartsSearchOnlyOnAVisibleLineSetToIt() {
        assertTrue(HgNavLine.longPressStartsSearch(false, ACTION_CTS));
        assertFalse(HgNavLine.longPressStartsSearch(true, ACTION_CTS));
        assertFalse(HgNavLine.longPressStartsSearch(false, ACTION_NONE));
        assertFalse(HgNavLine.longPressStartsSearch(false, ACTION_OTHER));
        assertFalse(HgNavLine.longPressStartsSearch(true, ACTION_NONE));
    }

    /** The long press choice and the alternative hide show only while the line is not hidden. */
    @Test public void dependentsShownOnlyWhileTheLineIsVisible() {
        assertTrue(HgNavLine.dependentsShown(false));
        assertFalse(HgNavLine.dependentsShown(true));
        assertTrue(HgNavLine.dependentsShown(HgNavLine.isHidden("0")));
        assertFalse(HgNavLine.dependentsShown(HgNavLine.isHidden("1")));
    }

    @Test public void pageChoicesMapToMiuiHomeValues() {
        assertEquals("cts", HgNavLine.choice(ACTION_CTS));
        assertEquals("none", HgNavLine.choice(ACTION_NONE));
        assertNull(HgNavLine.choice(ACTION_OTHER));
        assertEquals("launch_google_search", HgNavLine.valueForChoice("cts"));
        assertEquals("none", HgNavLine.valueForChoice("none"));
        // Round trip: what the page writes reads back as the same choice.
        assertEquals("cts", HgNavLine.choice(HgNavLine.action(
                HgNavLine.valueForChoice("cts"), "none")));
        assertEquals("none", HgNavLine.choice(HgNavLine.action(
                HgNavLine.valueForChoice("none"), null)));
    }
}
