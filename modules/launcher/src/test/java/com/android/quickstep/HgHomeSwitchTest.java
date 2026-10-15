package com.android.quickstep;

import static com.android.quickstep.HgHomeSwitch.CANCEL;
import static com.android.quickstep.HgHomeSwitch.MIUI_HOME;
import static com.android.quickstep.HgHomeSwitch.NONE;
import static com.android.quickstep.HgHomeSwitch.NOTIFY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hypergestures.HgNotifyReceiver;

import org.junit.Test;

public class HgHomeSwitchTest {
    private static final String NOVA = "com.teslacoilsw.launcher";
    private static final String OTHER = "com.example.launcher";

    /** The first observation is the state at process start: nothing to tell. */
    @Test public void firstObservationOnlyRecords() {
        assertEquals(NONE, new HgHomeSwitch().observe(MIUI_HOME));
        HgHomeSwitch s = new HgHomeSwitch();
        assertEquals(NONE, s.observe(NOVA));
        assertEquals(NOTIFY, s.observe(MIUI_HOME));
    }

    @Test public void toMiuiHomeNotifies() {
        HgHomeSwitch s = new HgHomeSwitch();
        s.observe(NOVA);
        assertEquals(NOTIFY, s.observe(MIUI_HOME));
        assertEquals(NONE, s.observe(MIUI_HOME));
    }

    @Test public void backFromMiuiHomeCancels() {
        HgHomeSwitch s = new HgHomeSwitch();
        s.observe(NOVA);
        s.observe(MIUI_HOME);
        assertEquals(CANCEL, s.observe(OTHER));
        assertEquals(NOTIFY, s.observe(MIUI_HOME));
        assertEquals(CANCEL, s.observe(NOVA));
    }

    /** Fallback recents works with any launcher. */
    @Test public void betweenOtherLaunchersNothing() {
        HgHomeSwitch s = new HgHomeSwitch();
        s.observe(NOVA);
        assertEquals(NONE, s.observe(OTHER));
        assertEquals(NONE, s.observe(NOVA));
        assertEquals(NONE, s.observe(NOVA));
    }

    /** No resolved home (role moving, setup wizard) is transient: ignored, not recorded. */
    @Test public void nullIgnored() {
        HgHomeSwitch s = new HgHomeSwitch();
        assertEquals(NONE, s.observe(null));
        assertEquals(NONE, s.observe(NOVA));
        assertEquals(NONE, s.observe(null));
        assertEquals(NOTIFY, s.observe(MIUI_HOME));
        assertEquals(NONE, s.observe(null));
        assertEquals(CANCEL, s.observe(NOVA));
    }

    /** Only our own notice is cancelled: the module posts all its notices under the same tag. */
    @Test public void cancelsOnlyOurNotice() {
        String tag = HgHomeSwitch.NOTICE_TAG;
        String text = HgHomeSwitch.TEXT;
        int id = HgNotifyReceiver.ID;
        assertTrue(HgNotifyReceiver.isNotice(tag, id, text, tag, text));
        assertTrue(HgNotifyReceiver.isNotice(tag, id, new StringBuilder(text), tag, text));
        // A module rollback or crash notice under the same tag stays.
        assertFalse(HgNotifyReceiver.isNotice(tag, id,
                "HyperGestures rolled back and then restarted the phone", tag, text));
        assertFalse(HgNotifyReceiver.isNotice(tag, id, null, tag, text));
        assertFalse(HgNotifyReceiver.isNotice("other", id, text, tag, text));
        assertFalse(HgNotifyReceiver.isNotice(null, id, text, tag, text));
        assertFalse(HgNotifyReceiver.isNotice(tag, id + 1, text, tag, text));
    }
}
