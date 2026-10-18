// HyperGestures: which parts of the settings page (HgSettingsFragment) show (owner, 0.3.9.34: "If
// system launcher is default hide the recent and gesture settings"). Decided by what this boot
// really runs, from the module's status setting (HgModuleStatus: active or dormant, or nothing of
// ours because of a rollback, a setup that did not happen or a refused firmware), with the live
// HOME holder only to say a reboot would change it: HOME is evaluated at boot, so a HOME changed
// since then takes effect at the next one. Recents and Gestures (everything that only works while
// ours run) show while this boot runs ours, with a note to reboot when HOME is MiuiHome now; they
// are hidden while it runs HyperOS's (dormant: a note says why, or to reboot when another HOME is
// set now) and while ours are off (the note gives the reason). System (works with any home app),
// Logs and About always show. Without a status for this boot (an older module, service.sh not
// there yet) it goes by the live HOME as before: MiuiHome hides them, any other or an unknown
// HOME shows everything, as the module treats an unknown holder as an active boot.
package com.android.quickstep;

final class HgSettingsSections {
    /** The note at the top (its text follows the mode). */
    static final int NOTE = 0;
    static final int RECENTS = 1;
    static final int GESTURES = 2;
    /** Works with any home app ("Hide navigation line", "Fix notification icons"). */
    static final int SYSTEM = 3;
    static final int LOGS = 4;
    static final int ABOUT = 5;

    /** Ours run and HOME agrees: everything, no note. */
    static final int MODE_ACTIVE = 0;
    /** Ours run this boot, but MiuiHome is HOME now: everything, and "reboot to switch". */
    static final int MODE_ACTIVE_REBOOT = 1;
    /** HyperOS's gestures and recents run (MiuiHome is HOME): ours hidden behind the note. */
    static final int MODE_STOCK = 2;
    /** HyperOS's run this boot, but another HOME is set now: ours hidden, "reboot to use". */
    static final int MODE_STOCK_REBOOT = 3;
    /** Nothing of ours runs this boot (rolled back, not set up, refused): hidden, the reason. */
    static final int MODE_OFF = 4;

    private HgSettingsSections() {}

    /** True when the system launcher is the home app: HyperOS's gestures and recents run. */
    static boolean systemLauncherHome(String home) {
        return HgHomeSwitch.MIUI_HOME.equals(home);
    }

    /**
     * The page's mode for this boot's module state ({@code state}, HgModuleStatus.state; null
     * when there is no status for this boot) and the live HOME holder ({@code home}, a package
     * or null when unknown).
     */
    static int mode(String state, String home) {
        return mode(state, null, home);
    }

    /**
     * As {@link #mode(String, String)}, with post-fs-data's choice ({@code boot},
     * HgModuleStatus.boot): while service.sh is still starting on a boot post-fs-data made
     * dormant, HyperOS's gestures and recents run, so it counts as dormant.
     */
    static int mode(String state, String boot, String home) {
        if (HgModuleStatus.STARTING.equals(state) && HgModuleStatus.DORMANT.equals(boot)) {
            state = HgModuleStatus.DORMANT;
        }
        boolean stockHome = systemLauncherHome(home);
        if (state == null) return stockHome ? MODE_STOCK : MODE_ACTIVE;
        switch (state) {
            case HgModuleStatus.STARTING:
            case HgModuleStatus.ACTIVE:
                return stockHome ? MODE_ACTIVE_REBOOT : MODE_ACTIVE;
            case HgModuleStatus.DORMANT:
                return home == null || home.isEmpty() || stockHome
                        ? MODE_STOCK : MODE_STOCK_REBOOT;
            case HgModuleStatus.ROLLED_BACK:
            case HgModuleStatus.NOT_SET_UP:
            case HgModuleStatus.REFUSED:
                return MODE_OFF;
            default: // a state a newer module names: as without a status
                return stockHome ? MODE_STOCK : MODE_ACTIVE;
        }
    }

    /** Whether {@code section} shows in {@code mode}. */
    static boolean shows(int section, int mode) {
        boolean ours = mode == MODE_ACTIVE || mode == MODE_ACTIVE_REBOOT;
        switch (section) {
            case NOTE:
                return mode != MODE_ACTIVE;
            case RECENTS:
            case GESTURES:
                return ours;
            default:
                return true;
        }
    }
}
