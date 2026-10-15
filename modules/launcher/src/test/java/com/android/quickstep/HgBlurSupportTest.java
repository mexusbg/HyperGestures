package com.android.quickstep;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgBlurSupportTest {
    private static final String OWNER_DEVICE = "nezha";

    /** The owner's phone: every term holds, so recents blurs and nothing changes visually. */
    @Test public void ownersPhoneBlurs() {
        assertTrue(available(true, true, true, "true", "1", OWNER_DEVICE));
        assertTrue(HgBlurSupport.frosted(true, true));
    }

    /** AOSP's terms: the owner's recents blur and the system's cross-window blur. */
    @Test public void aospTermsVeto() {
        assertFalse(available(false, true, true, "true", "1", OWNER_DEVICE));
        // Battery saver, critical thermal, "disable window blurs", no SurfaceFlinger blur.
        assertFalse(available(true, false, true, "true", "1", OWNER_DEVICE));
        // Even with the MIUI policy off.
        assertFalse(available(true, false, false, "true", "1", OWNER_DEVICE));
        assertFalse(available(false, true, false, "true", "1", OWNER_DEVICE));
    }

    /** MiuiHome's terms: the device property, HyperOS's blur switch, the device list. */
    @Test public void miuiTermsVeto() {
        // persist.sys.background_blur_supported missing or false: MiuiHome's default false.
        assertFalse(available(true, true, true, "", "1", OWNER_DEVICE));
        assertFalse(available(true, true, true, null, "1", OWNER_DEVICE));
        assertFalse(available(true, true, true, "false", "1", OWNER_DEVICE));
        // background_blur_enable present and not "1".
        assertFalse(available(true, true, true, "true", "0", OWNER_DEVICE));
        assertFalse(available(true, true, true, "true", "2", OWNER_DEVICE));
        // MiuiHome's isNotSupportBlurDevice list.
        for (String device : HgBlurSupport.NO_BLUR_DEVICES) {
            assertFalse(device, available(true, true, true, "true", "1", device));
        }
        assertTrue(available(true, true, true, "true", "1", null));
    }

    /**
     * HyperOS's blur switch vetoes only when present and not "1": absent or unreadable leaves
     * the other terms to decide (unlike MiuiHome, which reads absent as off).
     */
    @Test public void miuiSwitchVetoesOnlyWhenPresentAndOff() {
        assertTrue(available(true, true, true, "true", null, OWNER_DEVICE));
        assertTrue(available(true, true, true, "true", "1", OWNER_DEVICE));
        assertTrue(available(true, true, true, "true", " 1 ", OWNER_DEVICE));
        assertTrue(available(true, true, true, "true", HgBlurSupport.UNREADABLE, OWNER_DEVICE));
        assertFalse(available(true, true, true, "true", "0", OWNER_DEVICE));
        assertFalse(available(true, true, true, "true", "", OWNER_DEVICE));
        assertFalse(HgBlurSupport.miuiSwitchVetoes(null));
        assertFalse(HgBlurSupport.miuiSwitchVetoes(HgBlurSupport.UNREADABLE));
        assertFalse(HgBlurSupport.miuiSwitchVetoes("1"));
        assertTrue(HgBlurSupport.miuiSwitchVetoes("0"));
    }

    /** hypergestures_blur_fallback=0: only AOSP's terms, and the glass always (0.3.9.29). */
    @Test public void policyOffRestoresTheOldBehaviour() {
        assertTrue(available(true, true, false, "false", "0", "thor"));
        assertTrue(HgBlurSupport.frosted(false, false));
        assertTrue(HgBlurSupport.frosted(false, true));
    }

    /** Without blur the frosted elements turn solid. */
    @Test public void noBlurMeansSolid() {
        assertFalse(HgBlurSupport.frosted(true, false));
    }

    /** SystemProperties.getBoolean's parsing, as MiuiHome reads the property. */
    @Test public void parsesLikeSystemProperties() {
        for (String yes : new String[] {"1", "y", "yes", "on", "true"}) {
            assertTrue(yes, HgBlurSupport.parseBoolean(yes));
        }
        for (String no : new String[] {"0", "n", "no", "off", "false", "", "TRUE2"}) {
            assertFalse(no, HgBlurSupport.parseBoolean(no));
        }
        assertFalse(HgBlurSupport.parseBoolean(null));
    }

    private static boolean available(boolean recentsBlurOn, boolean crossWindow, boolean policyOn,
            String prop, String miuiEnable, String device) {
        return HgBlurSupport.blurAvailable(recentsBlurOn, crossWindow, policyOn, prop, miuiEnable,
                device);
    }
}
