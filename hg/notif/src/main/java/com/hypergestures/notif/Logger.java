/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Logging + shared constants.
 *
 * <p>HyperOS silences most SystemUI logcat output, so everything goes through the framework's
 * {@code XposedInterface.log} as well — that lands in LSPosed's modules_*.log where we can
 * always read it, even when logcat drops it. Before the module instance is attached (or if it
 * never is) only logcat is written.
 */
public final class Logger {
    // HyperGestures: our own tag (HyperNotificationFix logs as "HNF"); Save logs keeps Hg* tags.
    public static final String TAG = "HgNotif";

    /**
     * Flip to false once tuning is finished; verbose logging is per-notification.
     *
     * <p>HyperGestures: off, and a compile-time constant (HyperNotificationFix ships it on): this
     * runs on the owner's daily phone, and per-notification lines in SystemUI's and LSPosed's
     * logs are only wanted while tuning.
     */
    public static final boolean VERBOSE = false;

    /**
     * When set, generated bitmaps are dumped here for visual inspection.
     * Null disables dumping. Set from {@link HgNotifModule} only when the dir is writable.
     */
    public static String dumpDir = null;

    private Logger() {
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        XposedModule m = Xp.module;
        if (m != null) {
            m.log(Log.INFO, TAG, msg);
        }
    }

    public static void v(String msg) {
        if (!VERBOSE) {
            return;
        }
        Log.d(TAG, msg);
        XposedModule m = Xp.module;
        if (m != null) {
            m.log(Log.DEBUG, TAG, msg);
        }
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        XposedModule m = Xp.module;
        if (m != null) {
            m.log(Log.WARN, TAG, msg, t);
        }
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedModule m = Xp.module;
        if (m != null) {
            m.log(Log.ERROR, TAG, msg, t);
        }
    }

    public static String hex(int color) {
        return String.format("#%08X", color);
    }
}
