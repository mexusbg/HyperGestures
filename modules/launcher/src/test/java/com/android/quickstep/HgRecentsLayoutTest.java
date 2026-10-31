package com.android.quickstep;

import static com.android.quickstep.HgRecentsLayout.HORIZONTAL;
import static com.android.quickstep.HgRecentsLayout.STACK;
import static com.android.quickstep.HgRecentsLayout.VERTICAL;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgRecentsLayoutTest {
    /** Unset or empty override follows MIUI; MIUI unset is MiuiHome's default, vertical. */
    @Test public void followsMiuiUnlessOverridden() {
        assertEquals(STACK, HgRecentsLayout.resolve(null, "2"));
        assertEquals(HORIZONTAL, HgRecentsLayout.resolve("", "1"));
        assertEquals(VERTICAL, HgRecentsLayout.resolve(null, "0"));
        assertEquals(VERTICAL, HgRecentsLayout.resolve(null, null));
        assertEquals(VERTICAL, HgRecentsLayout.resolve("junk", "7"));
    }

    @Test public void overrideWins() {
        assertEquals(HORIZONTAL, HgRecentsLayout.resolve("1", "2"));
        assertEquals(STACK, HgRecentsLayout.resolve("2", "1"));
        assertEquals(VERTICAL, HgRecentsLayout.resolve(" 0 ", "2"));
        assertEquals(STACK, HgRecentsLayout.resolve("x", "2"));
    }

    /** All three layouts are built: each shows itself. */
    @Test public void shownIsRequested() {
        assertEquals(VERTICAL, HgRecentsLayout.shown(VERTICAL));
        assertEquals(HORIZONTAL, HgRecentsLayout.shown(HORIZONTAL));
        assertEquals(STACK, HgRecentsLayout.shown(STACK));
    }

    /** The settings page's list: "" (not migrated yet) unless the override is a layout. */
    @Test public void settingsChoice() {
        assertEquals("", HgRecentsLayout.settingsChoice(null));
        assertEquals("", HgRecentsLayout.settingsChoice(""));
        assertEquals("", HgRecentsLayout.settingsChoice("junk"));
        assertEquals("1", HgRecentsLayout.settingsChoice("1"));
        assertEquals("2", HgRecentsLayout.settingsChoice(" 2 "));
        assertEquals("0", HgRecentsLayout.settingsChoice("0"));
    }

    /** What HyperOS shows for its setting (the first-run copy); unset is vertical. */
    @Test public void followShown() {
        assertEquals(STACK, HgRecentsLayout.followShown("2"));
        assertEquals(HORIZONTAL, HgRecentsLayout.followShown("1"));
        assertEquals(VERTICAL, HgRecentsLayout.followShown("0"));
        assertEquals(VERTICAL, HgRecentsLayout.followShown(null));
    }

    /** The page shows the override, or while there is none what recents follows from MIUI. */
    @Test public void pageChoice() {
        assertEquals("1", HgRecentsLayout.pageChoice("1", "2"));
        assertEquals("0", HgRecentsLayout.pageChoice(" 0 ", "1"));
        assertEquals("2", HgRecentsLayout.pageChoice(null, "2"));
        assertEquals("1", HgRecentsLayout.pageChoice("junk", "1"));
        assertEquals("0", HgRecentsLayout.pageChoice("", null));
    }

    /** The horizontal layout centres the previous app; the stack keeps the current one in front. */
    @Test public void centresPreviousOnlyFromAnAppWithAnOlderCard() {
        assertEquals(true, HgRecentsLayout.centresPreviousOnOpen(HORIZONTAL, true, true));
        assertEquals(false, HgRecentsLayout.centresPreviousOnOpen(STACK, true, true));
        assertEquals(false, HgRecentsLayout.centresPreviousOnOpen(
                HgRecentsLayout.VERTICAL, true, true));
        assertEquals(false, HgRecentsLayout.centresPreviousOnOpen(HORIZONTAL, false, true));
        assertEquals(false, HgRecentsLayout.centresPreviousOnOpen(HORIZONTAL, true, false));
    }
}
