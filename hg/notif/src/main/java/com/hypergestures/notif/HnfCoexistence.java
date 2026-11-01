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
 * HyperGestures: stands down when HyperNotificationFix (the standalone module this was ported
 * from) hooks the same process. The settings summary asks the owner to turn it off; this covers
 * the time until then.
 *
 * <p>Both modules hook the same methods, and LSPosed chains the hooks: one module's hook runs
 * inside the other's {@code proceed()}. The check is made once per process, from the first of our
 * hooks that runs while the fix is on, and costs one stack trace:
 *
 * <ul>
 *   <li>HyperNotificationFix's hook frames are on the stack (its hook runs ahead of ours): it
 *       already fixes the icons, so ours pass through from then on (logged once).
 *   <li>They are not: ours runs first or alone. A HyperNotificationFix hook inside ours then sees
 *       an icon that is already fixed and leaves it (its gates measure the artwork, and a fixed
 *       icon passes them), or its result is replaced by ours, so nothing is fixed twice; it only
 *       repeats the measuring. If its package is installed, that is logged once.
 * </ul>
 *
 * <p>The decision is taken once per process and kept. The AOD hooks are installed later, per
 * ClassLoader, by each module's own discovery, so their chain order may differ from the SystemUI
 * hooks' the decision was taken on. Either order only duplicates work (see above), never a fix.
 *
 * <p>Nothing of HyperNotificationFix's is loaded or called: LSPosed gives each module its own
 * ClassLoader, and only class names on the stack are compared.
 */
final class HnfCoexistence {

    /** HyperNotificationFix's package, which is also its Java package. */
    static final String HNF_PACKAGE = "io.github.mexus.hypernotificationfix";

    private static final int UNKNOWN = 0;
    private static final int ALONE = 1;
    private static final int STAND_DOWN = 2;

    private static volatile int sState = UNKNOWN;

    private HnfCoexistence() {
    }

    /**
     * True when HyperNotificationFix's hooks run ahead of ours in this process.
     *
     * @param ctx the hook's context, or null; only read while the answer is unknown
     */
    static boolean standDown(Context ctx) {
        int state = sState;
        if (state == UNKNOWN) {
            state = check(ctx);
        }
        return state == STAND_DOWN;
    }

    private static synchronized int check(Context ctx) {
        if (sState != UNKNOWN) {
            return sState;
        }
        boolean ahead = hnfOnStack(Thread.currentThread().getStackTrace());
        if (ctx == null) {
            ctx = Hidden.currentApplication();
        }
        if (ahead) {
            Logger.i("HyperNotificationFix hooks this process too, ahead of us: standing down."
                    + " Turn it off in LSPosed to use HyperGestures' fix.");
        } else if (installed(ctx)) {
            Logger.i("HyperNotificationFix is installed; if LSPosed loads it too, both measure"
                    + " the same icons (ours runs first). Turn it off in LSPosed.");
        }
        sState = ahead ? STAND_DOWN : ALONE;
        return sState;
    }

    /** True when any frame belongs to HyperNotificationFix's classes. */
    static boolean hnfOnStack(StackTraceElement[] stack) {
        if (stack == null) {
            return false;
        }
        String prefix = HNF_PACKAGE + ".";
        for (StackTraceElement frame : stack) {
            String cls = frame == null ? null : frame.getClassName();
            if (cls != null && cls.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean installed(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            ctx.getPackageManager().getApplicationInfo(HNF_PACKAGE, 0);
            return true;
        } catch (Throwable t) {
            return false; // not installed, or not visible to this process
        }
    }
}
