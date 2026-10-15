// Replaces Soong's generated SysUiStatsLog (stats-log-api-gen --module sysui). Logging is a no-op
// in HyperGestures. Values are the real ones, from frameworks/proto_logging @ android-17.0.0_r1:
// atom ids from stats/atoms.proto; LAUNCHER_UICHANGED__* from the LauncherUIChanged atom
// (stats/atoms/sysui/sysui_atoms.proto) and the enums it uses (stats/enums/stats/launcher/
// launcher.proto). Within each field they are distinct, which the switch/when sites rely on.
package com.android.systemui.shared.system;

public final class SysUiStatsLog {
    private SysUiStatsLog() {}

    public static final int LAUNCHER_EVENT = 19;
    public static final int LAUNCHER_SNAPSHOT = 262;
    public static final int LAUNCHER_LATENCY = 426;
    public static final int LAUNCHER_IMPRESSION_EVENT_V2 = 716;
    public static final int LAUNCHER_LAYOUT_SNAPSHOT = 10108;

    // action: android.stats.launcher.LauncherAction
    public static final int LAUNCHER_UICHANGED__ACTION__DEFAULT_ACTION = 0;

    // dst_state: android.stats.launcher.LauncherState
    public static final int LAUNCHER_UICHANGED__DST_STATE__BACKGROUND = 1;
    public static final int LAUNCHER_UICHANGED__DST_STATE__HOME = 2;
    public static final int LAUNCHER_UICHANGED__DST_STATE__OVERVIEW = 3;
    public static final int LAUNCHER_UICHANGED__DST_STATE__ALLAPPS = 4;

    // input_type: LauncherUIChanged.InputType
    public static final int LAUNCHER_UICHANGED__INPUT_TYPE__UNKNOWN = 0;
    public static final int LAUNCHER_UICHANGED__INPUT_TYPE__TOUCH = 1;
    public static final int LAUNCHER_UICHANGED__INPUT_TYPE__TRACKPAD = 2;

    // user_type: LauncherUIChanged.UserType
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_UNKNOWN = 0;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_MAIN = 1;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_WORK = 2;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_CLONED = 3;
    public static final int LAUNCHER_UICHANGED__USER_TYPE__TYPE_PRIVATE = 4;

    // display_rotation: LauncherUIChanged.DisplayRotation
    public static final int LAUNCHER_UICHANGED__DISPLAY_ROTATION__ROTATION_0 = 0;
    public static final int LAUNCHER_UICHANGED__DISPLAY_ROTATION__ROTATION_90 = 1;
    public static final int LAUNCHER_UICHANGED__DISPLAY_ROTATION__ROTATION_180 = 2;
    public static final int LAUNCHER_UICHANGED__DISPLAY_ROTATION__ROTATION_270 = 3;

    // recents_orientation_handler: LauncherUIChanged.RecentsOrientationHandler
    public static final int LAUNCHER_UICHANGED__RECENTS_ORIENTATION_HANDLER__PORTRAIT = 0;
    public static final int LAUNCHER_UICHANGED__RECENTS_ORIENTATION_HANDLER__LANDSCAPE = 1;
    public static final int LAUNCHER_UICHANGED__RECENTS_ORIENTATION_HANDLER__SEASCAPE = 2;

    public static void write(int atomId, Object... args) {}

    public static android.util.StatsEvent buildStatsEvent(int atomId, Object... args) {
        return android.util.StatsEvent.newBuilder().setAtomId(atomId).build();
    }
}
