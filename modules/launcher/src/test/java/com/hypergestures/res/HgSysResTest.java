package com.hypergestures.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

public class HgSysResTest {
    private final List<String> calls = new ArrayList<>();

    /** Fake framework: bool/known = 0x01110001, color/known = 0x01060002, nothing else. */
    private void installFake() {
        HgSysRes.setLookupForTest((name, type, pkg) -> {
            calls.add(type + "/" + name + "@" + pkg);
            if (name.equals("known") && type.equals("bool")) return 0x01110001;
            if (name.equals("known") && type.equals("color")) return 0x01060002;
            return 0;
        });
    }

    @After public void reset() {
        HgSysRes.setLookupForTest(null);
    }

    @Test public void knownName_resolvesInAndroidPackage() {
        installFake();
        assertEquals(0x01110001, HgSysRes.id("bool", "known", 7));
        assertEquals(List.of("bool/known@android"), calls);
    }

    @Test public void missingName_returnsFallback() {
        installFake();
        assertEquals(7, HgSysRes.id("bool", "gone_after_ota", 7));
        assertEquals(0, HgSysRes.id("bool", "gone_after_ota", 0));
    }

    @Test public void typeIsPartOfTheKey() {
        installFake();
        assertEquals(0x01110001, HgSysRes.id("bool", "known", 0));
        assertEquals(0x01060002, HgSysRes.id("color", "known", 0));
        assertEquals(0, HgSysRes.id("dimen", "known", 0));
    }

    @Test public void lookupsAreCached_includingMisses() {
        installFake();
        for (int i = 0; i < 3; i++) {
            HgSysRes.id("bool", "known", 0);
            HgSysRes.id("bool", "gone_after_ota", 5);
        }
        assertEquals(List.of("bool/known@android", "bool/gone_after_ota@android"), calls);
        // A cached miss still honours each caller's own fallback.
        assertEquals(9, HgSysRes.id("bool", "gone_after_ota", 9));
        assertEquals(2, calls.size());
    }

    @Test public void lookupThatThrows_failsSoftToFallback() {
        HgSysRes.setLookupForTest((name, type, pkg) -> {
            throw new RuntimeException("no framework in this process");
        });
        assertEquals(3, HgSysRes.id("bool", "known", 3));
    }

    @Test public void typedHelpers_missingName_returnDefaultWithoutTouchingResources() {
        installFake();
        // null Resources/Context: a miss must never reach them.
        assertTrue(HgSysRes.getBoolean(null, "gone_after_ota", true));
        assertFalse(HgSysRes.getBoolean(null, "gone_after_ota", false));
        assertEquals("", HgSysRes.getString(null, "gone_after_ota", ""));
        assertEquals(0xFF123456, HgSysRes.getColor(null, "gone_after_ota", 0xFF123456));
    }

    @Test public void setLookupForTest_clearsCache() {
        installFake();
        HgSysRes.id("bool", "known", 0);
        installFake();
        HgSysRes.id("bool", "known", 0);
        assertEquals(2, calls.size());
    }
}
