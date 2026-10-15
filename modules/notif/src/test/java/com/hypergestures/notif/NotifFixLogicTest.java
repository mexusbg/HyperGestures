/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under CC BY-NC-SA 4.0; see LICENSE and NOTICE.
 */
package com.hypergestures.notif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The notification icon fix's pure logic (ported from HyperNotificationFix): the setting, the
 * HyperNotificationFix check, and the gates measured on the device (DEVNOTES' numbers).
 */
public class NotifFixLogicTest {
    /** hypergestures_notification_icons is opt-in: on only when exactly "1". */
    @Test public void settingOnlyOnWithOne() {
        assertEquals("hypergestures_notification_icons", NotifKeys.KEY);
        assertEquals(NotifKeys.KEY, NotifSetting.KEY);
        assertTrue(NotifKeys.isOn("1"));
        assertFalse(NotifKeys.isOn(null));
        assertFalse(NotifKeys.isOn("0"));
        assertFalse(NotifKeys.isOn(""));
        assertFalse(NotifKeys.isOn("true"));
        assertFalse(NotifKeys.isOn(" 1"));
    }

    /** SystemUI's mark counts only when it names this boot's id. */
    @Test public void hookedOnlyThisBoot() {
        String boot = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
        assertEquals("hypergestures_notif_hooked", NotifKeys.HOOKED_KEY);
        assertTrue(NotifKeys.hookedThisBoot(boot, boot));
        assertTrue(NotifKeys.hookedThisBoot(boot + "\n", boot));
        assertFalse(NotifKeys.hookedThisBoot("another-boot", boot));
        assertFalse(NotifKeys.hookedThisBoot(null, boot));
        assertFalse(NotifKeys.hookedThisBoot(boot, null));
        assertFalse(NotifKeys.hookedThisBoot("", ""));
        assertFalse(NotifKeys.hookedThisBoot(boot + "x".repeat(64), boot));
    }

    /** HyperNotificationFix's hook frames below ours mean its hook runs ahead: stand down. */
    @Test public void hnfOnStack() {
        StackTraceElement ours = new StackTraceElement(
                "com.hypergestures.notif.HgNotifModule", "lambda$install$0", null, 1);
        StackTraceElement hnf = new StackTraceElement(
                "io.github.mexus.hypernotificationfix.HyperNotificationFix"
                        + "$$ExternalSyntheticLambda2",
                "intercept", null, 1);
        StackTraceElement lookalike = new StackTraceElement(
                "io.github.mexus.hypernotificationfixer.Other", "run", null, 1);
        assertTrue(HnfCoexistence.hnfOnStack(new StackTraceElement[] {ours, hnf}));
        assertFalse(HnfCoexistence.hnfOnStack(new StackTraceElement[] {ours, lookalike}));
        assertFalse(HnfCoexistence.hnfOnStack(new StackTraceElement[0]));
        assertFalse(HnfCoexistence.hnfOnStack(null));
    }

    @Test public void aspect() {
        assertEquals(1f, AppIconShaper.aspect(10, 10), 0f);
        assertEquals(198f / 56f, AppIconShaper.aspect(198, 56), 1e-6f);
        assertEquals(198f / 56f, AppIconShaper.aspect(56, 198), 1e-6f);
        assertEquals(5f, AppIconShaper.aspect(0, 5), 0f); // a degenerate side counts as 1
    }

    /** Reach plus elongation refits; round logos and full-bleed artwork stay as they are. */
    @Test public void crowdedMeasuredOnDevice() {
        assertTrue(AppIconShaper.crowded(0.92f, 3.5f, 0.30f)); // bg.epay.app
        assertFalse(AppIconShaper.crowded(0.89f, 1.0f, 0.50f)); // amdroidalarmclock
        assertFalse(AppIconShaper.crowded(0.81f, 1.0f, 0.50f)); // com.facebook.orca
        assertFalse(AppIconShaper.crowded(0.72f, 1.0f, 0.50f)); // googlequicksearchbox
        assertFalse(AppIconShaper.crowded(0.95f, 3.5f, 0.85f)); // full-bleed
        assertFalse(AppIconShaper.crowded(0.86f, 3.5f, 0.30f)); // at the reach limit
        assertFalse(AppIconShaper.crowded(0.92f, 1.6f, 0.30f)); // at the aspect limit
    }

    @Test public void legacyBody() {
        assertTrue(AppIconShaper.hasBody(0.70f));
        assertFalse(AppIconShaper.hasBody(0.69f));
    }

    /** Small icons: only an already-broken (>= 80% opaque) icon is replaced. */
    @Test public void smallIconGateMeasuredOnDevice() {
        assertFalse(SmallIconFixer.wouldBlob(0.55f)); // Reddit
        assertFalse(SmallIconFixer.wouldBlob(0.66f)); // YT Music
        assertFalse(SmallIconFixer.wouldBlob(0.74f)); // Wallet
        assertFalse(SmallIconFixer.wouldBlob(0.35f)); // Security
        assertTrue(SmallIconFixer.wouldBlob(0.93f)); // UBB
        assertTrue(SmallIconFixer.wouldBlob(0.80f));
        assertTrue(SmallIconFixer.usableSilhouette(0.32f)); // UBB's silhouette
        assertTrue(SmallIconFixer.usableSilhouette(0.02f));
        assertTrue(SmallIconFixer.usableSilhouette(0.60f));
        assertFalse(SmallIconFixer.usableSilhouette(0.01f));
        assertFalse(SmallIconFixer.usableSilhouette(0.61f));
    }

    @Test public void wordmark() {
        assertTrue(SmallIconFixer.isWordmark(3.5f)); // "ePay.bg"
        assertFalse(SmallIconFixer.isWordmark(132f / 119f)); // stacked "ePay." over "bg"
        assertFalse(SmallIconFixer.isWordmark(2.2f));
    }

    /** Alpha from plate distance: 0 to the noise floor (48), opaque from 120, linear between. */
    @Test public void figureAlpha() {
        assertEquals(0, SmallIconFixer.figureAlpha(0, 255));
        assertEquals(0, SmallIconFixer.figureAlpha(48, 255));
        assertEquals(127, SmallIconFixer.figureAlpha(84, 255));
        assertEquals(255, SmallIconFixer.figureAlpha(120, 255));
        assertEquals(255, SmallIconFixer.figureAlpha(255, 255));
        assertEquals(128, SmallIconFixer.figureAlpha(200, 128)); // never above the source
    }

    @Test public void colorDistanceIgnoresAlpha() {
        assertEquals(255, Bitmaps.distance(0xFFFFFFFF, 0xFF000000));
        assertEquals(16, Bitmaps.distance(0xFF102030, 0xFF203020));
        assertEquals(0, Bitmaps.distance(0x00123456, 0xFF123456));
    }

    /** Ink runs split at blank gaps of at least two columns: {start, end, ink}. */
    @Test public void inkRuns() {
        assertArrayEquals(new int[][] {{1, 3, 10}, {6, 7, 7}},
                SmallIconFixer.runs(new long[] {0, 5, 5, 0, 0, 0, 7, 0}));
        // A one-column gap does not split a glyph (the waist of an "n").
        assertArrayEquals(new int[][] {{0, 3, 6}},
                SmallIconFixer.runs(new long[] {3, 0, 3}));
        assertNull(SmallIconFixer.runs(new long[] {0, 0, 0}));
    }

    /** A wordmark is split at the gap nearest its middle. */
    @Test public void splitIndex() {
        int[][] runs = {{0, 10, 1}, {12, 20, 1}, {22, 40, 1}};
        assertEquals(2, SmallIconFixer.splitIndex(runs, 40));
        assertEquals(-1, SmallIconFixer.splitIndex(new int[][] {{0, 10, 1}}, 10));
    }
}
