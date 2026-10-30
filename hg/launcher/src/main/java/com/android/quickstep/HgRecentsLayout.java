// HyperGestures: which recents layout to show (plan ruling R3). MIUI's own setting, Settings.Global
// task_stack_view_layout_style (0 vertical, 1 horizontal, 2 stack; MiuiHome's default 0) is only
// the starting point: our own Settings.Global hypergestures_recents_layout ("0", "1" or "2") is
// what the settings page offers (Vertical, Horizontal, Stack; no "Follow HyperOS", owner
// 2026-10-11) and what wins. Unset, empty or invalid, the first run copies what HyperOS shows now
// into it (HgSettingsMigration, from refresh and the page); until that write, resolve() follows
// MIUI's value. Vertical and stack apply to phones held upright (HgVerticalLayout,
// HgStackLayout); elsewhere recents shows the horizontal layout.
//   adb shell settings put global hypergestures_recents_layout 1    (always horizontal)
//   adb shell settings delete global hypergestures_recents_layout   (copied from MIUI again)
//
// Read when recents opens (RecentsActivity created or started, a swipe up starts), not polled:
// the value is cached here for the task size (BaseContainerInterface) and RecentsView.
package com.android.quickstep;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.android.launcher3.DeviceProfile;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;

public final class HgRecentsLayout {
    private HgRecentsLayout() {}

    private static final String TAG = "HgRecentsLayout";

    /** MiuiHome's setting (RecentsAndFSGestureUtils.getTaskStackViewLayoutStyle). */
    public static final String MIUI_STYLE = "task_stack_view_layout_style";

    public static final int VERTICAL = 0;
    public static final int HORIZONTAL = 1;
    public static final int STACK = 2;
    /** MiuiHome's default when its setting is unset. */
    static final int MIUI_DEFAULT = VERTICAL;

    /** The layout asked for (VERTICAL, HORIZONTAL or STACK). */
    private static volatile int sRequested = HORIZONTAL;

    /**
     * The layout asked for: {@code override} (hypergestures_recents_layout) when it is 0, 1 or 2,
     * else {@code miui} (task_stack_view_layout_style) when it is, else MiuiHome's default.
     */
    static int resolve(String override, String miui) {
        int style = parse(override);
        if (style < 0) style = parse(miui);
        return style < 0 ? MIUI_DEFAULT : style;
    }

    /** The layout recents shows for a requested one (all three are built). */
    static int shown(int requested) {
        return requested == STACK || requested == VERTICAL ? requested : HORIZONTAL;
    }

    /**
     * The settings page's choice (HgSettingsFragment) for an override value: "0" vertical, "1"
     * horizontal, "2" stack, "" when it is unset, empty or not a layout (not yet migrated).
     */
    public static String settingsChoice(String override) {
        int style = parse(override);
        return style < 0 ? "" : Integer.toString(style);
    }

    /**
     * The page's choice as recents shows it: settingsChoice of {@code override}, or, while that
     * is "" (not migrated, e.g. without the permission), what following MIUI's {@code miui} shows.
     */
    public static String pageChoice(String override, String miui) {
        String choice = settingsChoice(override);
        return choice.isEmpty() ? Integer.toString(followShown(miui)) : choice;
    }

    /** What following MIUI shows for its setting {@code miui}: VERTICAL, HORIZONTAL or STACK. */
    public static int followShown(String miui) {
        return shown(resolve(null, miui));
    }

    private static int parse(String value) {
        if (value == null) return -1;
        switch (value.trim()) {
            case "0": return VERTICAL;
            case "1": return HORIZONTAL;
            case "2": return STACK;
            default: return -1;
        }
    }

    /** Reads the settings again; cheap (Settings' local cache), called as recents opens. */
    public static void refresh(Context context) {
        HgSettingsMigration.runOnce(context); // first run: copy HyperOS's layout, drop old keys
        int requested;
        try {
            requested = resolve(
                    Settings.Global.getString(context.getContentResolver(),
                            HgRecentsSettings.RECENTS_LAYOUT),
                    Settings.Global.getString(context.getContentResolver(), MIUI_STYLE));
        } catch (RuntimeException e) {
            Log.w(TAG, "layout setting unreadable, horizontal", e);
            requested = HORIZONTAL;
        }
        if (requested != sRequested) {
            Log.i(TAG, "recents layout " + requested + " (shown: " + shown(requested) + ")");
        }
        sRequested = requested;
    }

    /**
     * Whether recents, opened by a swipe up from an app, moves on to the previous app: MiuiHome's
     * getDefaultCenterTaskViewIndex() = 1 for the horizontal and the stack layouts (docs/research/
     * 2026-10-09-miuihome-recents-features.md 6d, 6e: the previous app in the middle, the current
     * one peeking in on the newer side), so switching back is one tap. Not in the vertical layout
     * (it opens at the top of its grid), not from the home app ({@code fromApp} false), and only
     * with an older card to move to ({@code hasOlder}).
     */
    public static boolean centresPreviousOnOpen(int style, boolean fromApp, boolean hasOlder) {
        return style != VERTICAL && fromApp && hasOlder;
    }

    /** The layout recents shows now: VERTICAL, HORIZONTAL or STACK. */
    public static int style() {
        return shown(sRequested);
    }

    /**
     * Whether the stack layout applies: asked for, on a phone held upright with recents upright.
     * Elsewhere MiuiHome's stack has other constants we have not read; horizontal is shown.
     */
    public static boolean stackApplies(DeviceProfile dp, RecentsPagedOrientationHandler handler) {
        return style() == STACK && uprightPhone(dp, handler);
    }

    /**
     * Whether the vertical layout applies: asked for, on a phone held upright with recents
     * upright. Elsewhere MiuiHome uses other vertical configs (landscape, fold) we have not read;
     * horizontal is shown.
     */
    public static boolean verticalApplies(DeviceProfile dp,
            RecentsPagedOrientationHandler handler) {
        return style() == VERTICAL && uprightPhone(dp, handler);
    }

    private static boolean uprightPhone(DeviceProfile dp, RecentsPagedOrientationHandler handler) {
        return !dp.getDeviceProperties().isLargeScreen()
                && !dp.getDeviceProperties().isLandscape()
                && handler == RecentsPagedOrientationHandler.PORTRAIT;
    }
}
