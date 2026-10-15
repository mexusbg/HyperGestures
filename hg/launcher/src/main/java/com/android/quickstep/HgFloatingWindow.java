// HyperGestures: HyperOS's floating ("small") window for a recents task, by reflection.
//
// HyperOS builds its floating-window launch options in android.util.MiuiMultiWindowUtils
// (miui-framework.jar, boot classpath), all public static:
//   - supportFreeform(): the device and policy allow floating windows (enterprise restriction,
//     flip outer screen, supportMultiWindowDevice);
//   - packageSupportFreeform(Context, String pkg): the app may float (not blacklisted, resizeable,
//     white-listed, or a supported landscape game);
//   - getActivityOptions(Context, String pkg): null when unsupported, else ActivityOptions with
//     setLaunchWindowingMode(WINDOWING_MODE_FREEFORM), MIUI's own launch bounds
//     (getCustomFreeformRect), setMiuiConfigFlag(2) and the ActivityOptionsInjector freeform
//     scale / normal-freeform flag, i.e. what the framework needs for a HyperOS floating window
//     (checkAuthority passes for system apps). MiuiHome's recents menu builds the same options
//     (set_launch_windowing_mode, set_launch_bounds, set_miui_config_flag).
// The task itself is then started with ActivityManagerWrapper.startActivityFromRecents(taskId,
// options), as AOSP's own freeform task-menu item does. Without the MIUI class the entry is hidden.
package com.android.quickstep;

import android.app.ActivityOptions;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

final class HgFloatingWindow {
    private HgFloatingWindow() {}

    private static final String TAG = "HgTaskMenu";
    private static final String CLASS = "android.util.MiuiMultiWindowUtils";
    /** HyperOS's "no launch position" value for the left/top arguments. */
    private static final int UNSET_BOUND = 0xC0000000;

    private static boolean sResolved;
    @Nullable private static Method sSupportFreeform;
    @Nullable private static Method sPackageSupportFreeform;
    @Nullable private static Method sGetActivityOptions;
    private static boolean sLoggedFailure;
    /** supportFreeform(), asked once per process (null until then). */
    @Nullable private static Boolean sSystemSupports;
    /** Packages whose refusal was already explained (logWhyNot) in this process. */
    private static final Set<String> sExplained = new HashSet<>();

    private static synchronized void resolve() {
        if (sResolved) return;
        sResolved = true;
        try {
            Class<?> c = Class.forName(CLASS);
            sSupportFreeform = c.getMethod("supportFreeform");
            sPackageSupportFreeform = c.getMethod("packageSupportFreeform",
                    Context.class, String.class);
            // The (Context, String, boolean noCheck, int left, int top) overload: the 2-argument one
            // passes noCheck=false, which only answers while quick reply or screen projection is on
            // ("pkg name: ... unsupported freeform mode" otherwise). With noCheck the app only has to
            // pass packageSupportFreeform; left/top take HyperOS's "unset" value (0xC0000000).
            sGetActivityOptions = c.getMethod("getActivityOptions", Context.class, String.class,
                    boolean.class, int.class, int.class);
        } catch (Throwable t) {
            sSupportFreeform = null;
            sPackageSupportFreeform = null;
            sGetActivityOptions = null;
            logFailureOnce("HyperOS floating-window API not available, entry hidden", t);
        }
    }

    private static synchronized void logFailureOnce(String msg, Throwable t) {
        if (sLoggedFailure) return;
        sLoggedFailure = true;
        Log.w(TAG, msg + ": " + t);
    }

    /**
     * HyperOS allows floating windows at all. Asked once per process: it depends on the device
     * and on enterprise policy, not on the app, and every card-menu open asks.
     */
    static synchronized boolean systemSupports() {
        if (sSystemSupports != null) return sSystemSupports;
        resolve();
        Method m = sSupportFreeform;
        boolean supports = false;
        if (m != null) {
            try {
                supports = Boolean.TRUE.equals(m.invoke(null));
            } catch (Throwable t) {
                logFailureOnce("MiuiMultiWindowUtils.supportFreeform failed", t);
            }
        }
        sSystemSupports = supports;
        return supports;
    }

    /** HyperOS allows {@code packageName} in a floating window. */
    static boolean appSupports(Context context, String packageName) {
        resolve();
        Method m = sPackageSupportFreeform;
        if (m == null || packageName == null) return false;
        try {
            boolean ok = Boolean.TRUE.equals(m.invoke(null, context, packageName));
            if (!ok && explainOnce(packageName)) logWhyNot(context, packageName);
            return ok;
        } catch (Throwable t) {
            logFailureOnce("MiuiMultiWindowUtils.packageSupportFreeform failed", t);
            return false;
        }
    }

    private static synchronized boolean explainOnce(String packageName) {
        return sExplained.add(packageName);
    }

    /**
     * Logs HyperOS's individual floating-window checks for a package it refused: 5 reflective
     * calls, one of them a black-list build, so once per package per process.
     */
    private static void logWhyNot(Context context, String pkg) {
        StringBuilder sb = new StringBuilder("floating refused for ").append(pkg).append(':');
        sb.append(" supportFreeform=").append(call(CLASS, "supportFreeform", new Class<?>[0]));
        sb.append(" forceResizeable=").append(call(CLASS, "isForceResizeable", new Class<?>[0]));
        sb.append(" pkgActivityResizeable=").append(call(CLASS, "isPkgActivityResizeable",
                new Class<?>[] {Context.class, String.class}, context, pkg));
        sb.append(" whiteListInSystem=").append(call("android.util.MiuiMultiWindowAdapter",
                "inFreeformWhiteListInSystem", new Class<?>[] {String.class}, pkg));
        Object black = call(CLASS, "getFreeformBlackList",
                new Class<?>[] {Context.class, java.util.HashMap.class, boolean.class},
                context, new java.util.HashMap<>(), false);
        sb.append(" inBlackList=").append(black instanceof java.util.List
                ? ((java.util.List<?>) black).contains(pkg) : black);
        Log.i(TAG, sb.toString());
    }

    private static Object call(String cls, String name, Class<?>[] types, Object... args) {
        try {
            Method mm = Class.forName(cls).getDeclaredMethod(name, types);
            mm.setAccessible(true);
            return mm.invoke(null, args);
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + "(" + t.getMessage() + ")";
        }
    }

    /** HyperOS's floating-window launch options for {@code packageName}, or null. */
    @Nullable
    static ActivityOptions makeOptions(Context context, String packageName) {
        resolve();
        Method m = sGetActivityOptions;
        if (m == null || packageName == null) return null;
        try {
            Object r = m.invoke(null, context, packageName, true, UNSET_BOUND, UNSET_BOUND);
            return r instanceof ActivityOptions ? (ActivityOptions) r : null;
        } catch (Throwable t) {
            logFailureOnce("MiuiMultiWindowUtils.getActivityOptions failed", t);
            return null;
        }
    }
}
