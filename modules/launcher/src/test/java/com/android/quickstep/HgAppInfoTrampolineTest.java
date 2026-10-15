package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.quickstep.HgAppInfoTrampoline.Candidate;
import com.android.quickstep.HgAppInfoTrampoline.Forward;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HgAppInfoTrampolineTest {
    private static final String OURS = "com.hypergestures";
    private static final String ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS";
    private static final int NEW_TASK = 0x10000000;
    private static final int FORWARD_RESULT = 0x02000000;
    private static final Candidate SETTINGS = new Candidate("com.android.settings",
            "com.android.settings.applications.InstalledAppDetails", true);

    @Test public void requestPackageNeedsTheActionAndPackageData() {
        assertEquals("com.whatsapp",
                HgAppInfoTrampoline.requestPackage(ACTION, "package", "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.requestPackage(null, "package", "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.requestPackage("android.intent.action.SEND", "package",
                "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.requestPackage("android.intent.action.VIEW", "package",
                "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.requestPackage(ACTION, "content", "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.requestPackage(ACTION, null, null));
        assertNull(HgAppInfoTrampoline.requestPackage(ACTION, "package", null));
        assertNull(HgAppInfoTrampoline.requestPackage(ACTION, "package", ""));
        assertNull(HgAppInfoTrampoline.requestPackage(ACTION, "package", "com.x/../y"));
    }

    @Test public void packageFromPackageData() {
        assertEquals("com.whatsapp", HgAppInfoTrampoline.packageName("package", "com.whatsapp"));
        assertEquals("org.my_app.x2", HgAppInfoTrampoline.packageName("package", "org.my_app.x2"));
        assertEquals("android", HgAppInfoTrampoline.packageName("package", "android"));
    }

    @Test public void packageRejectsOtherSchemesAndEmpty() {
        assertNull(HgAppInfoTrampoline.packageName("http", "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.packageName(null, "com.whatsapp"));
        assertNull(HgAppInfoTrampoline.packageName("package", null));
        assertNull(HgAppInfoTrampoline.packageName("package", ""));
    }

    @Test public void packageRejectsBadNames() {
        assertNull(HgAppInfoTrampoline.packageName("package", "com..x"));
        assertNull(HgAppInfoTrampoline.packageName("package", ".com.x"));
        assertNull(HgAppInfoTrampoline.packageName("package", "com.x."));
        assertNull(HgAppInfoTrampoline.packageName("package", "com.1x"));
        assertNull(HgAppInfoTrampoline.packageName("package", "_com.x"));
        assertNull(HgAppInfoTrampoline.packageName("package", "com.x/evil"));
        assertNull(HgAppInfoTrampoline.packageName("package", "com.x y"));
        assertNull(HgAppInfoTrampoline.packageName("package", "//com.x"));
        assertNull(HgAppInfoTrampoline.packageName("package", "com.x;y"));
        StringBuilder sb = new StringBuilder("a");
        while (sb.length() < HgAppInfoTrampoline.MAX_PACKAGE_LENGTH) sb.append('b');
        assertEquals(sb.toString(), HgAppInfoTrampoline.packageName("package", sb.toString()));
        sb.append('c');
        assertNull(HgAppInfoTrampoline.packageName("package", sb.toString()));
    }

    @Test public void hopsOnlyForAPlainRequest() {
        assertTrue(HgAppInfoTrampoline.hopsToMiui(true, false, "com.caller"));
        assertTrue(HgAppInfoTrampoline.hopsToMiui(true, false, null));
        assertFalse(HgAppInfoTrampoline.hopsToMiui(false, false, "com.caller"));
        assertFalse(HgAppInfoTrampoline.hopsToMiui(true, true, "com.caller"));
        assertFalse(HgAppInfoTrampoline.hopsToMiui(true, false, "com.android.settings"));
        assertFalse(HgAppInfoTrampoline.hopsToMiui(true, false, "com.miui.securitycenter"));
    }

    @Test public void resolverPrefersSettingsAndSkipsUsAndNonSystem() {
        Candidate ours = new Candidate(OURS, "com.android.quickstep.HgAppInfoTrampoline", true);
        Candidate other = new Candidate("com.other", "com.other.Info", true);
        Candidate thirdParty = new Candidate("com.evil", "com.evil.Info", false);
        assertEquals(2, HgAppInfoTrampoline.pickResolver(
                Arrays.asList(ours, other, SETTINGS), OURS));
        assertEquals(1, HgAppInfoTrampoline.pickResolver(
                Arrays.asList(thirdParty, other), OURS));
        assertEquals(-1, HgAppInfoTrampoline.pickResolver(Arrays.asList(ours, thirdParty), OURS));
        // A non-system app named like Settings is not Settings.
        Candidate fake = new Candidate("com.android.settings", "x.Fake", false);
        assertEquals(1, HgAppInfoTrampoline.pickResolver(Arrays.asList(fake, other), OURS));
        List<Candidate> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(SETTINGS);
        assertEquals(1, HgAppInfoTrampoline.pickResolver(withNull, OURS));
    }

    @Test public void noResolverWhenOnlyUsOrNothing() {
        assertEquals(-1, HgAppInfoTrampoline.pickResolver(Collections.emptyList(), OURS));
        assertNull(HgAppInfoTrampoline.forwardOf("com.x", false, Collections.emptyList(), OURS));
        assertNull(HgAppInfoTrampoline.forwardOf("com.x", false, Collections.singletonList(
                new Candidate(OURS, "com.android.quickstep.HgAppInfoTrampoline", true)), OURS));
    }

    /** The forwarded request is action + package data + one allowed flag + a system component. */
    @Test public void forwardIsFreshAndExplicit() {
        Forward f = HgAppInfoTrampoline.forwardOf("com.whatsapp", false,
                Arrays.asList(new Candidate(OURS, "x.Ours", true), SETTINGS), OURS);
        assertEquals(ACTION, f.action);
        assertEquals("com.whatsapp", f.pkg);
        assertEquals(NEW_TASK, f.flags);
        assertEquals("com.android.settings", f.componentPackage);
        assertEquals("com.android.settings.applications.InstalledAppDetails", f.componentClass);

        Forward r = HgAppInfoTrampoline.forwardOf("com.whatsapp", true,
                Collections.singletonList(SETTINGS), OURS);
        assertEquals(FORWARD_RESULT, r.flags);
    }

    /** Forward has no room for extras, clip data, a selector or other flags: only these fields. */
    @Test public void forwardCarriesNothingElse() {
        List<String> fields = new ArrayList<>();
        for (Field f : Forward.class.getDeclaredFields()) {
            if (!f.isSynthetic() && !Modifier.isStatic(f.getModifiers())) fields.add(f.getName());
        }
        Collections.sort(fields);
        assertEquals(Arrays.asList("action", "componentClass", "componentPackage", "flags", "pkg"),
                fields);
        assertEquals(NEW_TASK, HgAppInfoTrampoline.forwardFlags(false));
        assertEquals(FORWARD_RESULT, HgAppInfoTrampoline.forwardFlags(true));
    }

    @Test public void componentFollowsTheSwitch() {
        int dflt = 0, enabled = 1, disabled = 2, disabledUser = 3, untilUsed = 4;
        int none = HgAppInfoTrampoline.NO_CHANGE;
        assertEquals(none, HgAppInfoTrampoline.componentState(true, dflt));
        assertEquals(none, HgAppInfoTrampoline.componentState(true, enabled));
        assertEquals(dflt, HgAppInfoTrampoline.componentState(true, disabled));
        assertEquals(dflt, HgAppInfoTrampoline.componentState(true, disabledUser));
        assertEquals(dflt, HgAppInfoTrampoline.componentState(true, untilUsed));
        assertEquals(disabled, HgAppInfoTrampoline.componentState(false, dflt));
        assertEquals(disabled, HgAppInfoTrampoline.componentState(false, enabled));
        assertEquals(none, HgAppInfoTrampoline.componentState(false, disabled));
    }

    @Test public void keyIsTheOneAdbSets() {
        assertEquals("hypergestures_app_info_fix", HgRecentsSettings.APP_INFO_FIX);
        assertTrue(Arrays.asList(HgLogCollector.GLOBAL_KEYS)
                .contains(HgRecentsSettings.APP_INFO_FIX));
    }
}
