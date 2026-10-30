// HyperGestures: this boot's state as the module publishes it. service.sh (and the guard) rewrite
// Settings.Global hypergestures_module_status at each change of state (hg_publish_status):
// key=value pairs on one line, separated by ';': bootId, state (starting, active, dormant,
// rolled-back, not-set-up, refused), boot (post-fs-data's choice: active or dormant), health,
// reason (when not active), mountMode, mount and, when they apply, metamoduleMissed, staleApk,
// powerPending, restoreGaveUp: the facts action.sh shows, which the app can't run. A setting, not
// a file in our data dir: root must never write into a dir we control. Only a value whose bootId
// is this boot's counts: an earlier boot's says nothing about this one (service.sh has not
// published yet, or the module did not run). The settings page decides its sections by the state
// (HgSettingsSections.mode), its status card the mount (HgStatusCard); Save logs lists it as it is.
package com.android.quickstep;

import android.content.Context;

import java.util.Map;

final class HgModuleStatus {
    /** Its Settings.Global key. */
    static final String KEY = "hypergestures_module_status";

    /** service.sh runs, setup not finished yet (waiting for SystemUI to bind us). */
    static final String STARTING = "starting";
    /** Set up; the health check runs or passed. */
    static final String ACTIVE = "active";
    /** MiuiHome is HOME: HyperOS's gestures and recents this boot. */
    static final String DORMANT = "dormant";
    /** The health check failed (or a boot loop): stock restored, the module disabled. */
    static final String ROLLED_BACK = "rolled-back";
    /** Nothing set up this boot (the provider never bound, the app not mounted, ...). */
    static final String NOT_SET_UP = "not-set-up";
    /** Another firmware than the module was built for, under STRICT_FIRMWARE=1. */
    static final String REFUSED = "refused";

    final String state;
    /** post-fs-data's choice: "active" or "dormant". */
    final String boot;
    /** The health-status text ("ok: provider bound, ...", "FAILED: ..."); empty if none. */
    final String health;
    /** Why the state is not active (dormant, rolled-back, not-set-up, refused); else empty. */
    final String reason;
    final String mountMode;
    final String mount;
    /** The metamodule that did not mount the app ("hybrid_mount 170"); empty if none. */
    final String metamoduleMissed;
    /** The update the system did not take this boot ("app 49, module 50"); empty if none. */
    final String staleApk;
    /** Battery "No restrictions" not set yet (PowerKeeper not ready). */
    final boolean powerPending;
    /** Undoing the module's changes failed for good on earlier boots: left to the owner. */
    final boolean restoreGaveUp;

    private HgModuleStatus(Map<String, String> v) {
        state = v.get("state");
        boot = get(v, "boot");
        health = get(v, "health");
        reason = get(v, "reason");
        mountMode = get(v, "mountMode");
        mount = get(v, "mount");
        metamoduleMissed = get(v, "metamoduleMissed");
        staleApk = get(v, "staleApk");
        powerPending = "1".equals(v.get("powerPending"));
        restoreGaveUp = "1".equals(v.get("restoreGaveUp"));
    }

    private static String get(Map<String, String> v, String key) {
        String s = v.get(key);
        return s == null ? "" : s;
    }

    /**
     * This boot's status from the value's text, or null when there is none for this boot:
     * unset, too long, another boot's, no state, or our own boot id unreadable (then it can't tell
     * a stale value from a fresh one, and the page goes by HOME as before).
     */
    static HgModuleStatus forBoot(String text, String bootId) {
        if (text == null || bootId == null
                || text.length() > HgLogCollector.MODULE_VALUE_MAX_CHARS) return null;
        Map<String, String> v = HgLogCollector.parseKeyValues(text);
        if (!bootId.equals(v.get("bootId"))) return null;
        String state = v.get("state");
        return state == null || state.isEmpty() ? null : new HgModuleStatus(v);
    }

    /** This boot's status, or null: one settings read (cached by the system) and /proc. */
    static HgModuleStatus read(Context context) {
        return forBoot(HgLogCollector.moduleValue(context, KEY), HgLogCollector.bootId());
    }

    /** Whether nothing of ours runs this boot for a reason other than HOME. */
    boolean off() {
        return ROLLED_BACK.equals(state) || NOT_SET_UP.equals(state) || REFUSED.equals(state);
    }

    /**
     * The module's versionCode when the running module is newer than our own {@code appCode}:
     * the system kept the previous APK (the app's side of hg_app_version_check), so the update
     * is not applied until a reboot. Only this boot's module version counts; else null.
     */
    static String appBehind(long appCode, HgLogCollector.RunningModule running) {
        if (running == null || running.source != HgLogCollector.FROM_SETTING) return null;
        long module;
        try {
            module = Long.parseLong(running.version.code);
        } catch (NumberFormatException e) {
            return null;
        }
        return appCode < module ? running.version.code : null;
    }
}
