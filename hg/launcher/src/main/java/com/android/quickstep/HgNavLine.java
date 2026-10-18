// HyperGestures: the System launcher's own navigation line settings, shared with it (one setting,
// not a copy): "Hide navigation line" and what a long press on the line does. Researched in
// MiuiHome (decompiled, read only):
// - Hide: NavigationBarTypePreferenceFragment.setHideGestureLine (its checkbox
//   navigation_hide_gesture_line) writes Settings.Global hide_gesture_line with putInt 1 or 0 and
//   nothing else (no broadcast; only its own guide pictures change). Readers take getInt(..., 0)
//   != 0 (recents Utilities.isHideGestureLine, GestureLineUtils, BaseRecentsImpl's observer).
//   SystemUI hides the handle for it, and MiuiHome's NavStubView then tells its gesture listener
//   the line is invisible, so NavStubGestureEventManager.onLongPress does nothing: no Circle to
//   Search while it is hidden.
// - Long press: NavShortcutConfigManager.writeSetting puts the chosen entity type, a string, into
//   Settings.Secure NavLongPress (PersistenceStoreKey KEY_LONG_PRESS_GESTURE_BAR).
//   "launch_google_search" is Circle to Search (MiuiHome's own entry, launchType Invoke), "none"
//   is nothing. Unset, global builds select Circle to Search unless Settings.System
//   long_press_home_key_no_ui, the older Circle to Search switch, is "none"
//   (NavShortcutCompat.isCircleToSearchSwitchEnabledInOldVersion). Other values are actions
//   other apps register with MiuiHome (voice assistants); we cannot run those, so they start
//   nothing here.
// Our gesture service (InputConsumerUtils) asks longPressStartsSearch at each touch-down before it
// adds the long-press consumer, so a change applies to the next touch without an observer: the
// settings provider's in-process cache answers most reads without a binder call. The settings
// page (HgSettingsFragment) shows the long press choice and our own overlay-based "Hide
// navigation line (alternative)" only while this hide is off (dependentsShown).
package com.android.quickstep;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

public final class HgNavLine {
    private static final String TAG = "HgNavLine";

    /** Settings.Global, an int: non-zero hides the line (MiuiHome's and SystemUI's). */
    public static final String HIDE_LINE = "hide_gesture_line";
    /** Settings.Secure, a string: the entity type a long press on the line runs (MiuiHome). */
    public static final String LONG_PRESS = "NavLongPress";
    /** Settings.System, a string: HyperOS's older Circle to Search switch ("none" is off). */
    public static final String OLD_CTS_SWITCH = "long_press_home_key_no_ui";
    /** NavLongPress for Circle to Search. */
    public static final String CTS = "launch_google_search";
    /** NavLongPress for nothing. */
    public static final String NONE = "none";

    /** What a long press does: Circle to Search, nothing, or another app's action (not ours). */
    static final int ACTION_CTS = 0;
    static final int ACTION_NONE = 1;
    static final int ACTION_OTHER = 2;

    private HgNavLine() {}

    /** Whether hide_gesture_line's value hides the line: as MiuiHome's getInt(..., 0) != 0. */
    public static boolean isHidden(String value) {
        if (value == null) return false;
        try {
            return Integer.parseInt(value.trim()) != 0;
        } catch (NumberFormatException e) {
            return false; // getInt's default
        }
    }

    /**
     * The long press action for NavLongPress ({@code value}) and the older switch
     * ({@code oldSwitch}), as MiuiHome's NavShortcutConfigManager.isConfigItemSelected decides
     * on a global build.
     */
    static int action(String value, String oldSwitch) {
        if (value == null || value.isEmpty()) {
            return NONE.equals(oldSwitch) ? ACTION_NONE : ACTION_CTS;
        }
        if (CTS.equals(value)) return ACTION_CTS;
        if (NONE.equals(value)) return ACTION_NONE;
        return ACTION_OTHER;
    }

    /** A long press starts Circle to Search only on a visible line set to Circle to Search. */
    static boolean longPressStartsSearch(boolean lineHidden, int action) {
        return !lineHidden && action == ACTION_CTS;
    }

    /**
     * Whether the page shows the items that only matter on a visible line: the long press choice
     * and "Hide navigation line (alternative)" (owner, 2026-10-18).
     */
    static boolean dependentsShown(boolean lineHidden) {
        return !lineHidden;
    }

    /** The page's list value for an action: "cts", "none", or null (another app's action). */
    static String choice(int action) {
        switch (action) {
            case ACTION_CTS: return "cts";
            case ACTION_NONE: return "none";
            default: return null;
        }
    }

    /** NavLongPress's value for a list value ("cts" or "none"). */
    static String valueForChoice(String choice) {
        return "cts".equals(choice) ? CTS : NONE;
    }

    /** The settings as they are now: hidden line, and the long press action. */
    static boolean hiddenNow(ContentResolver cr) {
        return isHidden(Settings.Global.getString(cr, HIDE_LINE));
    }

    static int actionNow(ContentResolver cr) {
        return action(Settings.Secure.getString(cr, LONG_PRESS),
                Settings.System.getString(cr, OLD_CTS_SWITCH));
    }

    /**
     * Whether a long press on the line may start Circle to Search now (the gesture service, at
     * each touch-down). If the settings are unreadable: true, AOSP's behaviour.
     */
    public static boolean longPressStartsSearch(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            return longPressStartsSearch(hiddenNow(cr), actionNow(cr));
        } catch (RuntimeException e) {
            Log.w(TAG, "navigation line settings unreadable: " + e);
            return true;
        }
    }
}
