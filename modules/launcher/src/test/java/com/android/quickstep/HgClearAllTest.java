package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class HgClearAllTest {
    private static final String NOTHING = "Nothing to clear in cache";
    private static final String MEGA = "%1$sMB cleared";
    private static final String GIGA = "%1$sGB cleared";

    private static String toast(long beforeKb, long afterKb) {
        return HgClearAll.toastText(beforeKb, afterKb, NOTHING, MEGA, GIGA, Locale.US);
    }

    private static final String NOVA = "com.teslacoilsw.launcher";
    private static final String US = "com.hypergestures";
    private static final List<Integer> NONE = Collections.emptyList();
    private static final List<String> NO_PACKAGES = Collections.emptyList();

    /** Plan ruling R1: the kept cards' apps, then HOME (Nova) and ourselves, once each. */
    @Test public void plan_withoutRunningTask_keepsLockedCardsHomeAndUs() {
        HgClearAll.Plan plan = HgClearAll.plan(Arrays.asList(11, 12, 13), Arrays.asList(20, 21),
                Arrays.asList("com.brave.browser", "com.spotify.music", "com.brave.browser"),
                NONE, NO_PACKAGES, NOVA, US);
        assertEquals(Arrays.asList("com.brave.browser", "com.spotify.music", NOVA, US),
                plan.whiteList);
        assertEquals(Arrays.asList(20, 21), plan.whiteListTaskIds);
        assertEquals(Arrays.asList(11, 12, 13), plan.removingTaskIds);
    }

    /** As MiuiHome: the app recents was opened over keeps its process and task. */
    @Test public void plan_withRunningTask_keepsItAndDoesNotRemoveIt() {
        HgClearAll.Plan plan = HgClearAll.plan(Arrays.asList(11, 12, 13), Arrays.asList(20),
                Arrays.asList("com.brave.browser"), Arrays.asList(12),
                Arrays.asList("org.telegram.messenger"), NOVA, US);
        assertEquals(Arrays.asList("com.brave.browser", "org.telegram.messenger", NOVA, US),
                plan.whiteList);
        assertEquals(Arrays.asList(20, 12), plan.whiteListTaskIds);
        assertEquals(Arrays.asList(11, 13), plan.removingTaskIds);
    }

    /** A running split card keeps both halves; a locked running card is listed once. */
    @Test public void plan_runningSplitPair_keepsBothHalves() {
        HgClearAll.Plan plan = HgClearAll.plan(Arrays.asList(11, 12, 13), Arrays.asList(13),
                Arrays.asList("c.app"), Arrays.asList(11, 13), Arrays.asList("a.app", "c.app"),
                NOVA, US);
        assertEquals(Arrays.asList("c.app", "a.app", NOVA, US), plan.whiteList);
        assertEquals(Arrays.asList(13, 11), plan.whiteListTaskIds);
        assertEquals(Collections.singletonList(12), plan.removingTaskIds);
    }

    /** Opened from HOME: HOME is never a card to keep, nothing changes. */
    @Test public void plan_runningHome_isIgnored() {
        HgClearAll.Plan plan = HgClearAll.plan(Arrays.asList(11, 12), NONE, NO_PACKAGES,
                Arrays.asList(5), Arrays.asList(NOVA), NOVA, US);
        assertEquals(Arrays.asList(NOVA, US), plan.whiteList);
        assertEquals(NONE, plan.whiteListTaskIds);
        assertEquals(Arrays.asList(11, 12), plan.removingTaskIds);
    }

    @Test public void plan_skipsMissingHomeAndEmptyNames() {
        HgClearAll.Plan plan = HgClearAll.plan(Arrays.asList(11), Arrays.asList(20),
                Arrays.asList("", null), NONE, NO_PACKAGES, null, US);
        assertEquals(Collections.singletonList(US), plan.whiteList);
    }

    /** MiuiHome counts up to 10 MB freed (or less memory than before) as nothing. */
    @Test public void toast_nothingUpToTenMegabytes() {
        assertEquals(NOTHING, toast(4_000_000, 4_000_000 + 10 * 1024));
        assertEquals(NOTHING, toast(4_000_000, 3_000_000));
        assertEquals("10MB cleared", toast(4_000_000, 4_000_000 + 10 * 1024 + 1));
    }

    /** Whole MB below 1024 MB, no space before the unit. */
    @Test public void toast_megabytes() {
        assertEquals("512MB cleared", toast(1_000_000, 1_000_000 + 512 * 1024 + 900));
        assertEquals("1023MB cleared", toast(0, 1023 * 1024 + 1023));
    }

    /** From 1024 MB, GB with one decimal, in the locale's format. */
    @Test public void toast_gigabytes() {
        assertEquals("1.0GB cleared", toast(0, 1024 * 1024));
        assertEquals("1.5GB cleared", toast(2_000_000, 2_000_000 + 1536 * 1024));
        assertEquals("1,5GB cleared",
                HgClearAll.toastText(0, 1536 * 1024, NOTHING, MEGA, GIGA, Locale.GERMANY));
    }
}
