// HyperGestures: the status list on the settings page's status card (HgSettingsFragment), one row
// per fact, each from a runtime check (owner, 2026-10-30, design canvas "Running card"). A row is
// a value and a level: OK (green dot), PENDING (amber: wait, or something to do), FAILED (red) or
// NEUTRAL (grey: nothing to say, the feature is off). The page reads the inputs on a helper
// thread; this decides from them:
// - Module: the module's state this boot (HgModuleStatus.state), with the health check's verdict
//   while it is active (its health text: "ok...", "running..." or "FAILED...").
// - Mount: we run from the system image (our ApplicationInfo is a system app's: the module's
//   mount put us there), and that mount did not report FAILED (HgModuleStatus.mount); the
//   metamodule that mounts the app, when one does (HgModuleStatus.mountMode, "mixed (<id>)").
// - Recents provider and Gestures & back: our own health facts (HgHealthFacts: SystemUI bound us;
//   our swipe-up input monitor is registered), counted as the module's hg_app_fact counts them:
//   this boot's value, its pid still our main process (by ActivityManager's list of our own
//   processes; /proc of another process is not readable here). Without them it is the system's,
//   which is expected while HyperOS's run (the page's stock modes) and a failure while ours
//   should run.
// - LSPosed hooks: the scopes our notification hooks were installed in this boot (NotifKeys'
//   mark, written from SystemUI).
// - Reboot: what waits for one: a HOME changed since boot, the hide-line overlay's state
//   (HgHideGestureLine), an installed module update, an app update the system did not take.
// - Then a row per problem the module reports, only while it stands (owner, 2026-10-30: the
//   module's status lives in this card, not in About): a metamodule that did not mount the app,
//   battery "No restrictions" not set yet, an undo that failed for good.
// - Then "Firmware compatibility", only when something fell back after an OTA: a HyperOS call the
//   phone's Shell no longer confirms (HgShellCalls), or a compiled-in Shell code that differs
//   from the phone's (HgShellInterfaces.aospMismatches). Silent while everything matches.
package com.android.quickstep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class HgStatusCard {
    static final int OK = 0;
    static final int PENDING = 1;
    static final int FAILED = 2;
    static final int NEUTRAL = 3;

    /** Mount: by the module itself. */
    static final int MOUNTED = 0;
    static final int NOT_MOUNTED = 1;
    /** Mount: by a metamodule (detail: its id). */
    static final int MOUNTED_VIA = 2;
    static final int STATE_ACTIVE = 11;
    /** Active, the health check still watching. */
    static final int STATE_CHECKING = 12;
    /** Active, but the health check failed (the rollback follows). */
    static final int STATE_HEALTH_FAILED = 13;
    static final int STATE_STARTING = 14;
    /** Dormant: HyperOS's own gestures and recents (MiuiHome is HOME). */
    static final int STATE_STANDBY = 15;
    static final int STATE_ROLLED_BACK = 16;
    static final int STATE_NOT_SET_UP = 17;
    static final int STATE_REFUSED = 18;
    /** No status for this boot. */
    static final int STATE_NONE = 19;
    /** A state a newer module names (detail: as it is). */
    static final int STATE_OTHER = 20;
    /** A metamodule did not mount the app (detail: its id). */
    static final int PROBLEM_METAMODULE = 21;
    /** Battery "No restrictions" not set yet. */
    static final int PROBLEM_BATTERY = 22;
    /** Undoing the module's changes failed for good. */
    static final int PROBLEM_RESTORE = 23;
    /** Some HyperOS features fall back on this firmware (detail: which, and why). */
    static final int FIRMWARE_FALLBACK = 24;
    /** Recents provider, Gestures & back: HyperGestures. */
    static final int BY_US = 4;
    /** Recents provider, Gestures & back: the system's (MiuiHome). */
    static final int BY_SYSTEM = 5;
    /** Recents provider, Gestures & back: the facts are switched off. */
    static final int UNKNOWN = 6;
    static final int HOOKS_LOADED = 7;
    static final int HOOKS_NOT_LOADED = 8;
    static final int REBOOT_NOT_NEEDED = 9;
    static final int REBOOT_PENDING = 10;

    /** HOME changed since boot (the page's *_REBOOT modes). */
    static final int REASON_HOME = 0;
    /** The hide-line overlay changes at the next boot. */
    static final int REASON_NAV_LINE = 1;
    /** A module update is installed. */
    static final int REASON_MODULE_UPDATE = 2;
    /** The system kept the previous APK. */
    static final int REASON_APP_UPDATE = 3;

    /** The longest value hg_app_fact accepts. */
    private static final int FACT_MAX_CHARS = 200;

    static final class Row {
        final int value;
        final int level;
        /** HOOKS_LOADED: NotifKeys' scope names, in order. */
        final List<String> scopes;
        /** REBOOT_PENDING: the REASON_* values, in order. */
        final List<Integer> reasons;
        /** MOUNTED_VIA, PROBLEM_METAMODULE: the metamodule; STATE_OTHER: the state; else "". */
        final String detail;

        private Row(int value, int level, List<String> scopes, List<Integer> reasons,
                String detail) {
            this.value = value;
            this.level = level;
            this.scopes = scopes;
            this.reasons = reasons;
            this.detail = detail;
        }

        private Row(int value, int level, String detail) {
            this(value, level, Collections.emptyList(), Collections.emptyList(), detail);
        }

        private Row(int value, int level) {
            this(value, level, "");
        }
    }

    private HgStatusCard() {}

    /**
     * The pid of a health fact's value ("<bootId> <pid> <uptimeMs>") when it is this boot's;
     * else 0. Parsed as the module's hg_app_fact does.
     */
    static int factPid(String value, String bootId) {
        if (value == null || bootId == null || bootId.isEmpty()
                || value.length() > FACT_MAX_CHARS) {
            return 0;
        }
        String[] parts = value.split(" ", -1);
        if (parts.length != 3 || !bootId.equals(parts[0])) return 0;
        String pid = parts[1];
        if (pid.isEmpty() || pid.length() > 9 || pid.charAt(0) == '0' || !digits(pid)) return 0;
        if (parts[2].isEmpty() || !digits(parts[2])) return 0;
        return Integer.parseInt(pid);
    }

    private static boolean digits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Whether a process name ({@code cmdline}: ActivityManager's, or /proc/<pid>/cmdline's text)
     * is our main process ({@code pkg}).
     */
    static boolean isMainProcess(String cmdline, String pkg) {
        if (cmdline == null) return false;
        int end = cmdline.indexOf('\0');
        return pkg.equals(end < 0 ? cmdline : cmdline.substring(0, end));
    }

    /**
     * Module: {@code state}, HgModuleStatus.state (null: no status for this boot);
     * {@code health}, its health text (empty if none).
     */
    static Row module(String state, String health) {
        if (state == null) return new Row(STATE_NONE, PENDING);
        switch (state) {
            case HgModuleStatus.ACTIVE:
                if (health.startsWith("FAILED")) return new Row(STATE_HEALTH_FAILED, FAILED);
                if (health.startsWith("running")) return new Row(STATE_CHECKING, PENDING);
                return new Row(STATE_ACTIVE, OK);
            case HgModuleStatus.STARTING: return new Row(STATE_STARTING, PENDING);
            case HgModuleStatus.DORMANT: return new Row(STATE_STANDBY, OK);
            case HgModuleStatus.ROLLED_BACK: return new Row(STATE_ROLLED_BACK, FAILED);
            case HgModuleStatus.NOT_SET_UP: return new Row(STATE_NOT_SET_UP, FAILED);
            case HgModuleStatus.REFUSED: return new Row(STATE_REFUSED, FAILED);
            default: return new Row(STATE_OTHER, NEUTRAL, state);
        }
    }

    /**
     * Mount: {@code fromSystemImage}, we run as a system app; {@code mount}, the module's mount
     * outcome (HgModuleStatus.mount; empty if none); {@code mountMode}, its mode's description
     * ("auto \u2192 mixed (hybrid_mount)", "self (MOUNT=self)"; empty if none).
     */
    static Row mount(boolean fromSystemImage, String mount, String mountMode) {
        boolean failed = mount != null && mount.contains("FAILED");
        if (!fromSystemImage || failed) return new Row(NOT_MOUNTED, FAILED);
        String via = metamodule(mountMode);
        return via.isEmpty() ? new Row(MOUNTED, OK) : new Row(MOUNTED_VIA, OK, via);
    }

    /** The metamodule in a mixed mount mode's description ("... mixed (<id>)"); else "". */
    private static String metamodule(String mountMode) {
        if (mountMode == null) return "";
        int at = mountMode.indexOf("mixed (");
        if (at < 0) return "";
        int start = at + "mixed (".length();
        int end = mountMode.indexOf(')', start);
        return end < 0 ? "" : mountMode.substring(start, end).trim();
    }

    /**
     * The module's problems, a row each, only those that stand: {@code metamoduleMissed}
     * ("hybrid_mount 170"; empty if none), {@code powerPending}, {@code restoreGaveUp}.
     */
    static List<Row> problems(String metamoduleMissed, boolean powerPending,
            boolean restoreGaveUp) {
        List<Row> rows = new ArrayList<>();
        if (metamoduleMissed != null && !metamoduleMissed.isEmpty()) {
            int space = metamoduleMissed.indexOf(' ');
            rows.add(new Row(PROBLEM_METAMODULE, PENDING,
                    space < 0 ? metamoduleMissed : metamoduleMissed.substring(0, space)));
        }
        if (powerPending) rows.add(new Row(PROBLEM_BATTERY, PENDING));
        if (restoreGaveUp) rows.add(new Row(PROBLEM_RESTORE, FAILED));
        return rows;
    }

    /**
     * Recents provider or Gestures & back: {@code ours}, its fact holds; {@code mode}, the page's
     * (HgSettingsSections); {@code starting}, setup still runs; {@code factsOn}, the facts are
     * published at all (HgRecentsSettings.HEALTH_FACTS).
     */
    static Row provider(boolean ours, int mode, boolean starting, boolean factsOn) {
        if (ours) return new Row(BY_US, OK);
        if (!factsOn) return new Row(UNKNOWN, PENDING);
        switch (mode) {
            case HgSettingsSections.MODE_STOCK:
            case HgSettingsSections.MODE_STOCK_REBOOT:
                return new Row(BY_SYSTEM, OK);
            case HgSettingsSections.MODE_OFF:
                return new Row(BY_SYSTEM, FAILED);
            default:
                return new Row(BY_SYSTEM, starting ? PENDING : FAILED);
        }
    }

    /** LSPosed hooks: {@code scopes}, where they loaded this boot; {@code fixOn}, the switch. */
    static Row hooks(List<String> scopes, boolean fixOn) {
        if (!scopes.isEmpty()) {
            return new Row(HOOKS_LOADED, OK, scopes, Collections.emptyList(), "");
        }
        return new Row(HOOKS_NOT_LOADED, fixOn ? FAILED : NEUTRAL);
    }

    /**
     * What waits for a reboot, in a fixed order: {@code mode}, the page's; {@code hideLine},
     * HgHideGestureLine.status; {@code moduleUpdate}, an installed module update;
     * {@code appUpdate}, an app update the system did not take.
     */
    static List<Integer> rebootReasons(int mode, int hideLine, boolean moduleUpdate,
            boolean appUpdate) {
        List<Integer> reasons = new ArrayList<>();
        if (mode == HgSettingsSections.MODE_ACTIVE_REBOOT
                || mode == HgSettingsSections.MODE_STOCK_REBOOT) {
            reasons.add(REASON_HOME);
        }
        if (hideLine == HgHideGestureLine.STATUS_HIDDEN_AFTER_REBOOT
                || hideLine == HgHideGestureLine.STATUS_SHOWN_AFTER_REBOOT) {
            reasons.add(REASON_NAV_LINE);
        }
        if (moduleUpdate) reasons.add(REASON_MODULE_UPDATE);
        if (appUpdate) reasons.add(REASON_APP_UPDATE);
        return reasons;
    }

    static Row reboot(List<Integer> reasons) {
        return reasons.isEmpty() ? new Row(REBOOT_NOT_NEEDED, OK)
                : new Row(REBOOT_PENDING, PENDING, Collections.emptyList(), reasons, "");
    }
    /**
     * The "Firmware compatibility" row: null while every HyperOS call is confirmed and every
     * compiled-in code matches; else amber, naming the features that fell back, then the
     * mismatches.
     */
    static Row firmware(List<String> unusableFeatures, List<String> aospMismatches) {
        if (unusableFeatures.isEmpty() && aospMismatches.isEmpty()) return null;
        List<String> all = new ArrayList<>(unusableFeatures);
        all.addAll(aospMismatches);
        return new Row(FIRMWARE_FALLBACK, PENDING, String.join(", ", all));
    }
}
