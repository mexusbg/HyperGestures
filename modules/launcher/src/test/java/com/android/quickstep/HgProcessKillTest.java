package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HgProcessKillTest {
    /**
     * The fallback contract: without HyperOS's miui.process classes (as on this JVM) both kills
     * return false, so the callers remove the tasks the AOSP way; the failure is logged once.
     */
    @Test public void withoutHyperOs_killsReturnFalse_andLogOnce() {
        HgProcessKill.Warn previous = HgProcessKill.sWarn;
        List<String> warnings = new ArrayList<>();
        HgProcessKill.sWarn = warnings::add;
        try {
            assertFalse(HgProcessKill.swipeUpClean("com.brave.browser", 0, 42));
            assertFalse(HgProcessKill.oneKeyClean(Arrays.asList(41, 42),
                    Collections.singletonList("com.hypergestures"), Collections.emptyList()));
            assertFalse(HgProcessKill.swipeUpClean("com.brave.browser", 0, 43));
            assertTrue("logged at most once: " + warnings, warnings.size() <= 1);
        } finally {
            HgProcessKill.sWarn = previous;
        }
    }

    private static final String NOVA = "com.teslacoilsw.launcher";
    private static final String US = "com.hypergestures";

    /** The AOSP kill after a dismiss: the card's apps, once each. */
    @Test public void dismissKillPackages_theCardsApps() {
        assertEquals(Arrays.asList("com.brave.browser", "org.telegram.messenger"),
                HgProcessKill.dismissKillPackages(Arrays.asList("com.brave.browser",
                        "org.telegram.messenger", "com.brave.browser"),
                        Collections.singletonList("com.spotify.music"), NOVA, US));
    }

    /** An app with another card left (the user's other window) is not killed. */
    @Test public void dismissKillPackages_sparesAnAppWithAnotherCard() {
        assertEquals(Collections.singletonList("org.telegram.messenger"),
                HgProcessKill.dismissKillPackages(
                        Arrays.asList("com.brave.browser", "org.telegram.messenger"),
                        Arrays.asList("com.spotify.music", "com.brave.browser"), NOVA, US));
    }

    /** Never HOME, ourselves, or a task without a package. */
    @Test public void dismissKillPackages_neverHomeOrUs() {
        assertEquals(Collections.emptyList(),
                HgProcessKill.dismissKillPackages(Arrays.asList(NOVA, US, null, ""),
                        Collections.emptyList(), NOVA, US));
    }

    /** Clear all's AOSP kill: the removed apps not on the whitelist (locked, running, HOME, us). */
    @Test public void clearKillPackages_removedMinusWhiteList() {
        assertEquals(Arrays.asList("com.brave.browser", "com.example.game"),
                HgProcessKill.clearKillPackages(Arrays.asList("com.brave.browser",
                        "com.spotify.music", "com.example.game", "com.brave.browser", null, US),
                        Arrays.asList("com.spotify.music", "org.telegram.messenger", NOVA, US)));
    }

    /** A dismissed card of our own package (the settings page) never kills us. */
    @Test public void killablePackage_neverOurOwn() {
        assertNull(HgProcessKill.killablePackage("com.hypergestures", "com.hypergestures"));
        assertNull(HgProcessKill.killablePackage(null, "com.hypergestures"));
        assertEquals("com.brave.browser",
                HgProcessKill.killablePackage("com.brave.browser", "com.hypergestures"));
    }
}
