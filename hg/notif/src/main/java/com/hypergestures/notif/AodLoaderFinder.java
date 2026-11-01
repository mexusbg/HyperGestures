/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

/**
 * Finds the ClassLoader that owns the always-on display's views.
 *
 * <p>The AOD is drawn <b>inside SystemUI</b>, from {@code com.miui.aod}'s own views, inflated
 * through a loader that is <em>not</em> the one
 * {@code Context.createPackageContextAsUser("com.miui.aod")} returns. Xposed hooks apply to the
 * {@code Class} objects of one loader, so hooking the classes from the obvious loader installs
 * without error and never fires — which is exactly what happened, and cost several rounds of
 * debugging.
 *
 * <p>So the loader is taken from a live view instead: SystemUI's window list is walked, looking
 * only at class <em>names</em>. Nothing is drawn or measured here — an earlier version rendered
 * drawables to inspect them and left its own bounds behind, which knocked MIUI's AOD icons
 * off-centre inside their plates.
 *
 * <p>{@link HgNotifModule} also hooks {@code createPackageContext} and {@code ViewGroup.addView}
 * for the same purpose; whichever sees the loader first wins, and every distinct loader gets
 * hooked. HyperGestures: once a loader found on a live view is hooked, this sweep and the
 * {@code addView} hook end ({@link HgNotifModule#stopViewDiscovery}).
 */
final class AodLoaderFinder {

    /** Sweep often enough to catch the AOD appearing, and stop so nothing is left running. */
    private static final long PERIOD_MS = 3000;
    private static final long DURATION_MS = 180_000;

    /**
     * Deep enough for SystemUI's keyguard hierarchy, bounded so a view cycle cannot hang the
     * loop.
     */
    private static final int MAX_DEPTH = 24;

    /** HyperGestures: set once a loader found on a live view is hooked; the sweep then ends. */
    private static volatile boolean sStopped;

    private AodLoaderFinder() {
    }

    /** HyperGestures: ends the sweep at its next turn (the live AOD loader is hooked). */
    static void stop() {
        sStopped = true;
    }

    static void start() {
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            long deadline = SystemClock.uptimeMillis() + DURATION_MS;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sStopped) {
                        return;
                    }
                    try {
                        sweep();
                    } catch (Throwable t) {
                        Logger.v("AOD loader sweep failed: " + t);
                    }
                    if (!sStopped && SystemClock.uptimeMillis() < deadline) {
                        handler.postDelayed(this, PERIOD_MS);
                    }
                }
            }, PERIOD_MS);
        } catch (Throwable t) {
            Logger.v("AOD loader sweep could not start: " + t);
        }
    }

    private static void sweep() throws Throwable {
        // All hidden API. getRootViews() does not exist on this build, so mViews is read directly,
        // with mRoots (ViewRootImpl.mView) as the fallback.
        Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
        Object instance = wmg.getMethod("getInstance").invoke(null);

        java.lang.reflect.Field fViews = wmg.getDeclaredField("mViews");
        fViews.setAccessible(true);
        Object views = fViews.get(instance);
        if (views instanceof java.util.List) {
            // Copied first: the list is mutated on the UI thread as windows come and go.
            for (Object v : new java.util.ArrayList<>((java.util.List<?>) views)) {
                if (sStopped) {
                    return;
                }
                if (v instanceof View) {
                    walk((View) v, 0);
                }
            }
            return;
        }

        java.lang.reflect.Field fRoots = wmg.getDeclaredField("mRoots");
        fRoots.setAccessible(true);
        Object roots = fRoots.get(instance);
        if (!(roots instanceof java.util.List)) {
            return;
        }
        for (Object root : new java.util.ArrayList<>((java.util.List<?>) roots)) {
            if (root == null) {
                continue;
            }
            java.lang.reflect.Field fView = root.getClass().getDeclaredField("mView");
            fView.setAccessible(true);
            Object v = fView.get(root);
            if (v instanceof View) {
                walk((View) v, 0);
            }
        }
    }

    private static void walk(View v, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (v.getClass().getName().startsWith("com.miui.aod.")) {
            HgNotifModule.offerAodLoader(v.getClass().getClassLoader(),
                    "live view " + v.getClass().getName(), true);
            return;   // one view from that loader is enough; its children add nothing
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1);
            }
        }
    }
}
