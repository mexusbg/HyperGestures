package com.android.quickstep;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HgSettingsMigrationTest {
    /** Unset, empty or invalid: write what HyperOS shows (MIUI's unset default is vertical). */
    @Test public void layoutCopiedFromMiuiWhenUnset() {
        assertEquals("2", HgSettingsMigration.layoutToWrite(null, "2"));
        assertEquals("1", HgSettingsMigration.layoutToWrite("", "1"));
        assertEquals("0", HgSettingsMigration.layoutToWrite("junk", "0"));
        assertEquals("0", HgSettingsMigration.layoutToWrite(null, null));
        assertEquals("0", HgSettingsMigration.layoutToWrite(" ", "7"));
    }

    /** A valid override is never touched, whatever MIUI says. */
    @Test public void layoutKeptWhenSet() {
        assertNull(HgSettingsMigration.layoutToWrite("0", "2"));
        assertNull(HgSettingsMigration.layoutToWrite("1", null));
        assertNull(HgSettingsMigration.layoutToWrite(" 2 ", "1"));
    }

    private static final String[] STEP1 = {"hypergestures_recents_header",
            "hypergestures_card_actions", "hypergestures_split_gesture",
            "hypergestures_floating_shrink"};
    private static final String[] STEP2 = {"hypergestures_icon_shape"};
    private static final String[] STEP3 = {"hypergestures_back_haptics"};

    /** Before any step (unset, empty, invalid, "0"): every step's keys. */
    @Test public void allStepsWhenNoneDone() {
        String[] all = {STEP1[0], STEP1[1], STEP1[2], STEP1[3], STEP2[0], STEP3[0]};
        assertArrayEquals(all, HgSettingsMigration.keysToDelete(null));
        assertArrayEquals(all, HgSettingsMigration.keysToDelete(""));
        assertArrayEquals(all, HgSettingsMigration.keysToDelete("0"));
        assertArrayEquals(all, HgSettingsMigration.keysToDelete("junk"));
        assertArrayEquals(all, HgSettingsMigration.keysToDelete("-1"));
        assertEquals("hypergestures_settings_migration", HgSettingsMigration.MARKER);
    }

    /** A phone that ran 0.3.9.42's step 1 ("1") deletes the rounded icons and back keys. */
    @Test public void stepsTwoAndThreeAfterStepOne() {
        String[] later = {STEP2[0], STEP3[0]};
        assertArrayEquals(later, HgSettingsMigration.keysToDelete("1"));
        assertArrayEquals(later, HgSettingsMigration.keysToDelete(" 1 "));
    }

    /** A phone that ran 0.3.9.43's steps ("2") deletes only the back vibration key, once. */
    @Test public void onlyStepThreeAfterStepTwo() {
        assertArrayEquals(STEP3, HgSettingsMigration.keysToDelete("2"));
        assertArrayEquals(STEP3, HgSettingsMigration.keysToDelete(" 2 "));
        assertEquals("hypergestures_back_haptics", HgSettingsMigration.REMOVED_BACK_HAPTICS);
    }

    /** Every step done ("3"), or a later version's marker: nothing to delete. */
    @Test public void nothingWhenAllDone() {
        assertEquals("3", HgSettingsMigration.DONE);
        assertArrayEquals(new String[0], HgSettingsMigration.keysToDelete("3"));
        assertArrayEquals(new String[0], HgSettingsMigration.keysToDelete("4"));
    }

    @Test public void stepsDoneParsesTheMarker() {
        assertEquals(0, HgSettingsMigration.stepsDone(null));
        assertEquals(0, HgSettingsMigration.stepsDone("x"));
        assertEquals(1, HgSettingsMigration.stepsDone("1"));
        assertEquals(2, HgSettingsMigration.stepsDone("2 "));
    }

    /** The marker may move on only once every due key is gone; a refused delete is reported. */
    @Test public void deleteAllReportsKeysStillSet() {
        Set<String> set = new HashSet<>(Arrays.asList("a", "b", "c"));
        List<String> deleted = new ArrayList<>();
        // "b" is refused; "d" is not set, so it is not deleted at all.
        List<String> left = HgSettingsMigration.deleteAll(new String[] {"a", "b", "c", "d"},
                set::contains, key -> !"b".equals(key) && deleted.add(key));
        assertEquals(Arrays.asList("b"), left);
        assertEquals(Arrays.asList("a", "c"), deleted);
        assertEquals(Collections.emptyList(), HgSettingsMigration.deleteAll(
                new String[] {"a", "d"}, set::contains, key -> true));
    }
}
