/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.content.Context;

/**
 * HyperGestures: the one question every hook asks before changing anything. The fix is off
 * unless the owner turned it on ({@link NotifSetting}), and it stands down while
 * HyperNotificationFix's hooks run ahead of ours ({@link HnfCoexistence}); either way the hook
 * returns the original result, or calls the original with the original arguments.
 */
final class Gate {

    private Gate() {
    }

    /**
     * @param ctx a context from the hook if it has one (used only until the process has an
     *     Application, and for the one-time HyperNotificationFix check), else null
     * @return true when the hook must pass through untouched
     */
    static boolean passThrough(Context ctx) {
        // Nothing may throw from here into SystemUI or the AOD: some callers run before their
        // own try block. Any failure passes through.
        try {
            return !NotifSetting.on(ctx) || HnfCoexistence.standDown(ctx);
        } catch (Throwable t) {
            return true;
        }
    }
}
