package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HgTaskLocksTest {
    private static Set<String> set(String... keys) {
        return new HashSet<>(Arrays.asList(keys));
    }

    @Test public void key_isPackageColonUser_andSplitsBack() {
        String key = HgTaskLocks.key("com.brave.browser", 10);
        assertEquals("com.brave.browser:10", key);
        assertEquals("com.brave.browser", HgTaskLocks.packageOf(key));
        assertEquals(10, HgTaskLocks.userOf(key));
    }

    /** A lock is per app and user, not per task: same package, other user, is another lock. */
    @Test public void key_differsByUser() {
        assertFalse(HgTaskLocks.key("a.b", 0).equals(HgTaskLocks.key("a.b", 10)));
    }

    @Test public void malformedKeys_parseAsNothing() {
        assertNull(HgTaskLocks.packageOf("nocolon"));
        assertNull(HgTaskLocks.packageOf(":0"));
        assertNull(HgTaskLocks.packageOf(null));
        assertEquals(-1, HgTaskLocks.userOf("a.b:"));
        assertEquals(-1, HgTaskLocks.userOf("a.b:x"));
        assertEquals(-1, HgTaskLocks.userOf(null));
    }

    @Test public void clearAll_removesOnlyUnlockedViews() {
        List<List<String>> views = Arrays.asList(
                Collections.singletonList("a:0"),
                Collections.singletonList("b:0"),
                Collections.singletonList("c:0"));
        assertEquals(Arrays.asList(0, 2), HgTaskLocks.clearableViews(views, set("b:0")));
    }

    @Test public void clearAll_nothingLocked_removesEverything() {
        List<List<String>> views = Arrays.asList(
                Collections.singletonList("a:0"), Collections.singletonList("b:0"));
        assertEquals(Arrays.asList(0, 1), HgTaskLocks.clearableViews(views, set()));
    }

    @Test public void clearAll_everythingLocked_removesNothing() {
        List<List<String>> views = Arrays.asList(
                Collections.singletonList("a:0"), Collections.singletonList("b:0"));
        assertTrue(HgTaskLocks.clearableViews(views, set("a:0", "b:0")).isEmpty());
    }

    /** A split pair stays when either of its apps is locked. */
    @Test public void clearAll_splitPairWithOneLockedApp_stays() {
        List<List<String>> views = Arrays.asList(
                Arrays.asList("a:0", "b:0"), Collections.singletonList("c:0"));
        assertEquals(Collections.singletonList(1),
                HgTaskLocks.clearableViews(views, set("b:0")));
    }

    /** The lock of another user's copy of the app does not keep this user's card. */
    @Test public void clearAll_lockIsPerUser() {
        List<List<String>> views = Collections.singletonList(Collections.singletonList("a:0"));
        assertEquals(Collections.singletonList(0),
                HgTaskLocks.clearableViews(views, set("a:10")));
    }

    @Test public void import_addsSystemLocksForThatUser() {
        Set<String> merged = HgTaskLocks.withImported(set("x:0"), 0,
                Arrays.asList("com.openai.chatgpt", "com.brave.browser", "", null));
        assertEquals(set("x:0", "com.openai.chatgpt:0", "com.brave.browser:0"), merged);
    }

    @Test public void import_withoutSystemList_keepsOurs() {
        assertEquals(set("x:0"), HgTaskLocks.withImported(set("x:0"), 0, null));
    }

    /**
     * A locked card is never dismissed by a swipe (owner, 2026-11-09: it was, by a drag past half
     * the way, the one case AOSP's no-detach mode still lets through), whatever the release.
     */
    @Test public void dismissOnRelease_lockedNeverDismisses() {
        assertFalse(HgTaskLocks.dismissOnRelease(true, true, true, false, true));
        assertFalse(HgTaskLocks.dismissOnRelease(true, false, false, false, true));
        assertFalse(HgTaskLocks.dismissOnRelease(true, true, true, false, false));
    }

    /** An unlocked card: AOSP's rule (a fling up, or past the threshold unless flung back). */
    @Test public void dismissOnRelease_unlockedAsAosp() {
        assertTrue(HgTaskLocks.dismissOnRelease(false, true, true, false, false));
        assertFalse(HgTaskLocks.dismissOnRelease(false, false, true, false, false));
        assertTrue(HgTaskLocks.dismissOnRelease(false, true, false, false, true));
        assertFalse(HgTaskLocks.dismissOnRelease(false, true, false, true, true));
        assertFalse(HgTaskLocks.dismissOnRelease(false, true, false, false, false));
    }
}
