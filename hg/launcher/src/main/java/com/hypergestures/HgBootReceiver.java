package com.hypergestures;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.quickstep.HgAppInfoTrampoline;
import com.android.quickstep.HgHideGestureLine;

/**
 * HyperGestures: syncs the "Hide gesture line" overlay with its setting once a boot
 * (HgHideGestureLine.syncOnce). The setting works whatever HOME is (owner, 0.3.9.34), but on a
 * dormant boot (MiuiHome as HOME) SystemUI never binds our gesture service, whose start syncs it
 * otherwise; without this the overlay would only follow the setting there once the settings page
 * was opened, e.g. after restore.sh disabled it, or a setting changed through adb.
 *
 * <p>Cheap: one broadcast a boot, which on an active boot reaches the process SystemUI already
 * started (syncOnce then does nothing more), and on a dormant boot starts it once for one overlay
 * lookup (a register or unregister only when the overlay is out of step). Allowed: a priv-app
 * receives BOOT_COMPLETED like any app holding RECEIVE_BOOT_COMPLETED (Launcher3's manifest
 * declares it); HyperOS's autostart restriction targets third-party apps (unverified for ours: the
 * log line below shows it arrived). BOOT_COMPLETED skips packages in the stopped state: after a
 * force-stop of our app it does not arrive until something has started the app again; the
 * gesture service's start (active boots) or the next opening of the settings page syncs it then.
 * Not LOCKED_BOOT_COMPLETED: nothing gains from running before the first unlock, since SystemUI
 * has built its navigation bar by then anyway and a change shows from the next boot either way.
 * Not exported: the system's broadcast reaches it all the same, nobody else's can. In the main
 * process, where SystemUI's bind already runs on an active boot (the settings page's ":settings"
 * process would be one more process each boot).
 *
 * <p>After the overlay, it syncs the "Fix App info back" trampoline's component with its switch
 * (HgAppInfoTrampoline.syncComponent: a write only when they differ).
 */
public final class HgBootReceiver extends BroadcastReceiver {
    private static final String TAG = "HgBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i(TAG, "boot completed: syncing the gesture line overlay and the App info fix");
        // Binder calls off the main thread; the process stays up until they are done.
        PendingResult result = goAsync();
        HgHideGestureLine.syncOnce(context, () -> {
            // HgAppInfoTrampoline's component follows its switch (a write only on a change).
            HgAppInfoTrampoline.syncComponent(context);
            result.finish();
        });
    }
}
