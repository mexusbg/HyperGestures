// HyperGestures: "Hide navigation line (alternative)" (Settings.Global
// hypergestures_hide_gesture_line; unlike our other switches it is OFF unless "1"; the page shows
// it only while the System launcher's own hide, HgNavLine, is off). The same overlay also carries
// the main "Hide navigation line" (hide_gesture_line) while our gesture service runs: HyperOS
// SystemUI applies that key only in its own full-screen gesture mode (force_fsg_nav_bar=1, where
// it removes its navigation bar), and that mode runs HyperOS's gesture handlers next to ours
// (owner's phone, 2026-10-19: glitches, Circle to Search during app switches, one-way quick
// switch), so we never turn it on; with a third-party HOME HyperOS keeps it off. See wanted(). SystemUI paints the gestural
// navigation handle with color/navigation_bar_home_handle_light_color and _dark_color, and sizes
// it (and the regions its touches are tested against) from dimen/navigation_handle_radius. A
// fabricated runtime resource overlay owned by this app sets both colours to fully transparent:
// the handle keeps the size and touch regions the system computed, so swipes and the long press
// that starts Circle to Search still work, but nothing is drawn. The radius is left alone on
// purpose (zero hides the line too, but it also feeds hit testing, and a zero handle stops that
// long press), and nothing here touches MiuiHome's NavStubView gesture area.
//
// Why the app may do this (AOSP 17 OverlayManagerService and OverlayActorEnforcer):
// - registering or unregistering a fabricated overlay only needs the calling uid to own the
//   overlay's package (ours) and not to be the shell; SELinux lets priv_app find the overlay
//   service (system_api_service);
// - enabling it goes through the actor check: SystemUI declares no <overlayable>, so the legacy
//   rule applies, CHANGE_OVERLAY_PACKAGES (signature|privileged: declared in our manifest, so the
//   module's privapp allowlist carries it);
// - idmap: a target without <overlayable> accepts overlays from preinstalled packages, and our
//   priv-app lives on /system.
// Switching off only unregisters (ownership alone): a disable in the same transaction would roll
// the unregister back if its actor check failed. These are hidden APIs (our package is on the
// module's hidden-API allowlist). Every failure is logged and swallowed: the line then simply
// stays as it is.
//
// SystemUI's NavigationHandle reads both colours once, when the navigation bar is built, and an
// overlay change does not rebuild it (only font scale and UI mode changes do), so a change shows
// after the next reboot. OMS keeps the overlay across reboots while our package is installed: the
// module mounts the APK on every boot, dormant ones (MiuiHome as HOME) included; a boot without it
// (module disabled or removed) drops it. It works whatever HOME is (owner, 0.3.9.34: "should work
// always even if system launcher is default"), and the setting is the source of truth: the app
// syncs the overlay with it from each entry point, as SystemUI only binds our gesture service
// while we are its recents provider: TouchInteractionHandler calls syncOnce at service start
// (active boots), HgBootReceiver at BOOT_COMPLETED (every boot, dormant ones too), the settings
// page whenever it opens (syncLater) and after each toggle (sync). Only the module's undo paths
// turn it off regardless (hg_hide_line_off in hg_restore_all: restore.sh, the guard, uninstall.sh,
// the rollback). The settings page shows the state (status): hidden, hidden or shown again after
// a reboot (sync changed it in this boot, recorded as CHANGED_FILE), or not applied (OverlayInfo
// says it is not enabled while on, or still registered while off).
package com.android.quickstep;

import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;

import com.android.launcher3.util.Executors;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

public final class HgHideGestureLine {
    private static final String TAG = "HgHideLine";

    /** The overlay's name; with our package it reads com.hypergestures:HgHideGestureLine. */
    public static final String OVERLAY_NAME = "HgHideGestureLine";
    static final String TARGET = "com.android.systemui";
    static final String[] COLORS = {
            TARGET + ":color/navigation_bar_home_handle_light_color",
            TARGET + ":color/navigation_bar_home_handle_dark_color"};
    static final int TRANSPARENT = 0x00000000;

    /** What sync does: nothing, register and enable, or unregister. */
    static final int KEEP = 0;
    static final int HIDE = 1;
    static final int SHOW = 2;

    /** What the settings page shows: nothing more, hidden, pending a reboot, or not applied. */
    static final int STATUS_NONE = 0;
    static final int STATUS_HIDDEN = 1;
    static final int STATUS_HIDDEN_AFTER_REBOOT = 2;
    static final int STATUS_SHOWN_AFTER_REBOOT = 3;
    static final int STATUS_NOT_APPLIED = 4;

    /**
     * The boot id of the last change sync made (our device-protected files dir; any process of
     * ours writes it, the page reads it): SystemUI only draws a change from its next start.
     */
    static final String CHANGED_FILE = "hide-line-changed";

    private static boolean sSynced;

    private HgHideGestureLine() {}

    /** The setting's value: on only when exactly "1" (unset, "0" or anything else: off). */
    public static boolean isOn(String value) {
        return "1".equals(value);
    }

    /**
     * Whether the overlay should hide the line: our alternative ({@code alternative}, our key),
     * or the System launcher's own hide ({@code main}, hide_gesture_line, read as MiuiHome reads
     * it) on a boot where our gestures run ({@code ourGestures}). On other boots (MiuiHome as
     * HOME, rolled back, status unknown) HyperOS applies hide_gesture_line itself, live, and a
     * leftover overlay would keep the line invisible after an un-hide there until a reboot.
     */
    static boolean wanted(String alternative, String main, boolean ourGestures) {
        return isOn(alternative) || (ourGestures && HgNavLine.isHidden(main));
    }

    /** Whether this boot's module status says our gestures run (post-fs-data chose active). */
    static boolean ourGestures(HgModuleStatus status) {
        return status != null && HgModuleStatus.ACTIVE.equals(status.boot) && !status.off();
    }

    private static boolean wantedNow(Context context) {
        android.content.ContentResolver cr = context.getContentResolver();
        return wanted(Settings.Global.getString(cr, HgRecentsSettings.HIDE_GESTURE_LINE),
                Settings.Global.getString(cr, HgNavLine.HIDE_LINE),
                ourGestures(HgModuleStatus.read(context)));
    }

    /**
     * What to do for the wanted state, given whether our overlay is registered and enabled for
     * this user. Off removes a registered overlay even if it is disabled, so none is left behind.
     */
    static int decide(boolean wanted, boolean registered, boolean enabled) {
        if (wanted) return registered && enabled ? KEEP : HIDE;
        return registered ? SHOW : KEEP;
    }

    /**
     * The page's status for the setting ({@code wanted}), our overlay's OverlayInfo (registered,
     * enabled) and whether sync changed it in this boot (SystemUI still draws the line as at its
     * start, i.e. as before the change).
     */
    static int status(boolean wanted, boolean registered, boolean enabled,
            boolean changedThisBoot) {
        if (wanted) {
            if (!registered || !enabled) return STATUS_NOT_APPLIED;
            return changedThisBoot ? STATUS_HIDDEN_AFTER_REBOOT : STATUS_HIDDEN;
        }
        if (registered) return STATUS_NOT_APPLIED; // still there: the removal failed
        return changedThisBoot ? STATUS_SHOWN_AFTER_REBOOT : STATUS_NONE;
    }

    /**
     * The status for the settings page: the setting, OverlayManager's OverlayInfo for our overlay
     * and the boot of the last change. A binder call: not on the main thread. STATUS_NONE if
     * anything is unreadable (the page then shows only the summary).
     */
    static int status(Context context) {
        try {
            boolean wanted = wantedNow(context);
            OverlayManager om = context.getSystemService(OverlayManager.class);
            if (om == null) return STATUS_NONE;
            OverlayInfo info = om.getOverlayInfo(
                    new OverlayIdentifier(context.getPackageName(), OVERLAY_NAME),
                    Process.myUserHandle());
            String bootId = HgLogCollector.bootId();
            boolean changed = bootId != null && bootId.equals(readChanged(context));
            return status(wanted, info != null, info != null && info.isEnabled(), changed);
        } catch (Throwable e) {
            Log.w(TAG, "gesture line overlay state unreadable: " + e);
            return STATUS_NONE;
        }
    }

    private static File changedFile(Context context) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(),
                CHANGED_FILE);
    }

    /** Records this boot as the last change's (fail-soft: the page then says "hidden"). */
    private static void markChanged(Context context) {
        String bootId = HgLogCollector.bootId();
        if (bootId == null) return;
        try {
            File f = changedFile(context);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bootId.getBytes(StandardCharsets.US_ASCII));
            }
            if (!tmp.renameTo(f)) Log.w(TAG, "change not recorded");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "change not recorded: " + e);
        }
    }

    private static String readChanged(Context context) {
        try (RandomAccessFile f = new RandomAccessFile(changedFile(context), "r")) {
            byte[] bytes = new byte[64];
            int n = f.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII).trim() : null;
        } catch (IOException | RuntimeException e) {
            return null; // never changed, or unreadable
        }
    }

    /** Once per process, from TouchInteractionHandler's creation: sync off the main thread. */
    public static void syncOnce(Context context) {
        syncOnce(context, null);
    }

    /**
     * Once per process (TouchInteractionHandler, HgBootReceiver): sync off the main thread, then
     * {@code done} (if any) on that thread; at once if this process already synced.
     */
    public static void syncOnce(Context context, Runnable done) {
        boolean first;
        synchronized (HgHideGestureLine.class) {
            first = !sSynced;
            sSynced = true;
        }
        if (!first) {
            if (done != null) done.run();
            return;
        }
        Context app = context.getApplicationContext();
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            try {
                sync(app);
            } finally {
                if (done != null) done.run();
            }
        });
    }

    /** sync off the main thread, every time: the settings page, whenever it opens. */
    public static void syncLater(Context context) {
        Context app = context.getApplicationContext();
        Executors.UI_HELPER_EXECUTOR.execute(() -> sync(app));
    }

    /**
     * Makes the overlay follow the setting (read now, so toggles queued one after another end in
     * the last one's state). Binder calls: not on the main thread. False if it failed.
     */
    public static boolean sync(Context context) {
        boolean wanted;
        try {
            wanted = wantedNow(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "setting unreadable", e);
            return false;
        }
        return apply(context, wanted);
    }

    private static boolean apply(Context context, boolean wanted) {
        try {
            OverlayManager om = context.getSystemService(OverlayManager.class);
            if (om == null) {
                Log.w(TAG, "no overlay service");
                return false;
            }
            OverlayIdentifier id = new OverlayIdentifier(context.getPackageName(), OVERLAY_NAME);
            UserHandle user = Process.myUserHandle();
            OverlayInfo info = om.getOverlayInfo(id, user);
            int action = decide(wanted, info != null, info != null && info.isEnabled());
            if (action == KEEP) return true;
            OverlayManagerTransaction.Builder t = new OverlayManagerTransaction.Builder();
            if (action == HIDE) {
                FabricatedOverlay.Builder b = new FabricatedOverlay.Builder(
                        context.getPackageName(), OVERLAY_NAME, TARGET);
                for (String color : COLORS) {
                    b.setResourceValue(color, TypedValue.TYPE_INT_COLOR_ARGB8, TRANSPARENT,
                            null /* configuration: every one */);
                }
                FabricatedOverlay overlay = b.build();
                t.registerFabricatedOverlay(overlay)
                        .setEnabled(overlay.getIdentifier(), true, user.getIdentifier());
            } else {
                // Unregistering needs ownership only, and drops an enabled overlay from SystemUI.
                t.unregisterFabricatedOverlay(id);
            }
            om.commit(t.build());
            OverlayInfo now = om.getOverlayInfo(id, user);
            if (action == HIDE && (now == null || !now.isEnabled())) {
                // E.g. an idmap the target refused: OMS registered it but cannot apply it.
                Log.w(TAG, "overlay registered but not enabled: "
                        + (now == null ? "missing" : OverlayInfo.stateToString(now.state)));
                return false;
            }
            Log.i(TAG, action == HIDE ? "gesture line hidden (from SystemUI's next start)"
                    : "gesture line shown again (from SystemUI's next start)");
            markChanged(context);
            return true;
        } catch (Throwable e) {
            // SecurityException from OMS, a hidden API missing on this firmware, anything:
            // the line stays as it is.
            Log.w(TAG, "cannot change the gesture line overlay", e);
            return false;
        }
    }
}
