/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

/**
 * HyperGestures: drops the cached icons of an app when it is installed, updated, changed or
 * removed, so the next notification is built from its current artwork.
 *
 * <p>One dynamic receiver per process, registered from the first hook call that fills a cache
 * (SystemUI has no Application when the hooks are installed). Event-driven, nothing polls: the
 * package broadcasts are protected ones only the system sends, which a not-exported receiver
 * still gets. It listens for every user where the process may (SystemUI holds
 * INTERACT_ACROSS_USERS_FULL), so a work-profile app's update reaches it too; otherwise for the
 * process's own user. Fails soft: without it the caches are still bounded, only not refreshed.
 */
final class PackageWatcher {

    private static volatile boolean sRegistered;

    private PackageWatcher() {
    }

    /**
     * Registers the receiver once.
     *
     * @param hookContext a context from the hook, used only while the process has no Application
     */
    static void ensure(Context hookContext) {
        if (!sRegistered) {
            register(hookContext);
        }
    }

    private static synchronized void register(Context hookContext) {
        if (sRegistered) {
            return;
        }
        Context c = NotifSetting.processContext(hookContext);
        if (c == null) {
            return; // tried again on the next call
        }
        // Set first: whatever happens below is not retried per notification.
        sRegistered = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    Uri data = intent.getData();
                    String pkg = data == null ? null : data.getSchemeSpecificPart();
                    if (pkg != null) {
                        forget(pkg);
                    }
                } catch (Throwable t) {
                    Logger.w("package change not applied", t);
                }
            }
        };
        try {
            c.registerReceiverForAllUsers(receiver, filter, null, null,
                    Context.RECEIVER_NOT_EXPORTED);
            Logger.i("watching package changes (all users)");
            return;
        } catch (Throwable t) {
            Logger.v("all-users package receiver unavailable: " + t);
        }
        try {
            c.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Logger.i("watching package changes (this user)");
        } catch (Throwable t) {
            Logger.w("cannot watch package changes; cached icons stay until evicted", t);
        }
    }

    private static void forget(String pkg) {
        AppIconShaper.forget(pkg);
        HgNotifModule.forgetPackage(pkg);
        Logger.v("package " + pkg + " changed: its cached icons dropped");
    }
}
