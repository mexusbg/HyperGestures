// HyperGestures: one-time settings migrations, run when recents opens (HgRecentsLayout.refresh)
// and when the settings page opens (owner, 2026-10-11):
//  - Layout: the page has no "Follow HyperOS" choice (the owner cannot reach HyperOS's own page
//    while our gestures run), so an unset, empty or invalid hypergestures_recents_layout gets,
//    once, the layout HyperOS shows now.
//  - Switches: the page no longer offers switches it had (all on today). Their keys stay as
//    adb-only off switches, deleted once (back to on) in numbered steps recorded in the marker
//    hypergestures_settings_migration, so an adb override set later holds: step 1 the header,
//    card actions, split gesture and floating shrink ("1", since fa82fcd); step 2 rounded
//    icons ("2"); step 3 ("3") the back vibration switch's key, hypergestures_back_haptics: that
//    key is gone from the code (back haptics follow the system's haptic setting alone), so the
//    step only clears a stale value. A phone that already ran step n runs only the later ones.
// Fail-soft: without the permission (WRITE_SECURE_SETTINGS) or on any error nothing changes and
// the next process tries again (runOnce gives up for this one). The marker moves on only once
// every due key is gone, so a delete the settings provider refused is retried on the next run.
// The settings page runs it on its helper thread (runLater): the page shows nothing it writes
// (the layout picker shows the same layout before the copy, and the dropped switches have no
// rows), so it needs no result before it draws.
package com.android.quickstep;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.android.launcher3.util.Executors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public final class HgSettingsMigration {
    private HgSettingsMigration() {}

    private static final String TAG = "HgSettingsMigration";

    /** The last step done: "1", "2"...; unset (or invalid) before the first. */
    public static final String MARKER = "hypergestures_settings_migration";

    /**
     * The "Back vibration" switch's key, removed from the code (no reader left): back haptics
     * follow only the system's haptic setting (interaction/HgBackHaptics). Deleted by step 3.
     */
    static final String REMOVED_BACK_HAPTICS = "hypergestures_back_haptics";

    /**
     * The switches the page dropped, by step (step n is index n - 1): their keys are deleted
     * once, each step at most once. A new step goes at the end.
     */
    static final String[][] DROPPED_SWITCHES = {
            {HgRecentsSettings.HEADER, HgRecentsSettings.CARD_ACTIONS,
                    HgRecentsSettings.SPLIT_GESTURE, HgRecentsSettings.FLOATING_SHRINK},
            {HgRecentsSettings.ICON_SHAPE},
            {REMOVED_BACK_HAPTICS},
    };
    /** The marker's value once every step ran. */
    static final String DONE = Integer.toString(DROPPED_SWITCHES.length);

    private static volatile boolean sRan;

    /**
     * The value to write to hypergestures_recents_layout, or null to leave it: null when
     * {@code override} already names a layout, else what HyperOS shows for {@code miui}.
     */
    static String layoutToWrite(String override, String miui) {
        if (!HgRecentsLayout.settingsChoice(override).isEmpty()) return null;
        return Integer.toString(HgRecentsLayout.followShown(miui));
    }

    /** The last step done per the marker's value: 0 when unset, invalid or negative. */
    static int stepsDone(String marker) {
        if (marker == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(marker.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The keys to delete given the marker's value: those of every step not done yet. */
    static String[] keysToDelete(String marker) {
        List<String> keys = new ArrayList<>();
        for (int step = stepsDone(marker); step < DROPPED_SWITCHES.length; step++) {
            keys.addAll(Arrays.asList(DROPPED_SWITCHES[step]));
        }
        return keys.toArray(new String[0]);
    }

    /** Runs the migrations once per process (the settings page runs them every opening). */
    public static void runOnce(Context context) {
        if (!sRan) run(context);
    }

    /** Runs the migrations on the helper thread (the settings page, on each opening). */
    public static void runLater(Context context) {
        Context app = context.getApplicationContext();
        Executors.UI_HELPER_EXECUTOR.execute(() -> run(app));
    }

    /**
     * Deletes every key of {@code keys} that {@code isSet}, with {@code delete} (false: refused);
     * returns the keys still set. Empty: all of them are gone, so the marker may move on.
     */
    static List<String> deleteAll(String[] keys, Predicate<String> isSet,
            Predicate<String> delete) {
        List<String> left = new ArrayList<>();
        for (String key : keys) {
            if (isSet.test(key) && !delete.test(key)) left.add(key);
        }
        return left;
    }

    /** Runs the migrations; idempotent, writes only what is due. */
    public static void run(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            String layout = layoutToWrite(
                    Settings.Global.getString(cr, HgRecentsSettings.RECENTS_LAYOUT),
                    Settings.Global.getString(cr, HgRecentsLayout.MIUI_STYLE));
            if (layout != null) {
                Settings.Global.putString(cr, HgRecentsSettings.RECENTS_LAYOUT, layout);
                Log.i(TAG, "recents layout set to HyperOS's current: " + layout);
            }
            String[] keys = keysToDelete(Settings.Global.getString(cr, MARKER));
            if (keys.length > 0) {
                List<String> left = deleteAll(keys,
                        key -> Settings.Global.getString(cr, key) != null,
                        key -> Settings.Global.putString(cr, key, null));
                if (!left.isEmpty()) {
                    Log.w(TAG, "not deleted: " + String.join(", ", left)
                            + "; the next run retries");
                } else if (Settings.Global.putString(cr, MARKER, DONE)) {
                    Log.i(TAG, "dropped switch keys deleted: " + String.join(", ", keys));
                } else {
                    Log.w(TAG, "dropped switch keys deleted, marker not written");
                }
            }
            sRan = true;
        } catch (RuntimeException e) { // SecurityException without the permission, or worse
            // Not again in this process (every recents opening would retry and log): the next
            // process tries again; the page's own run still retries on each opening.
            sRan = true;
            Log.w(TAG, "settings migration skipped: " + e);
        }
    }
}
