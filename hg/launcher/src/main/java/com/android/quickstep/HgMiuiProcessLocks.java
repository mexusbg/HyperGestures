// HyperGestures: HyperOS's own list of locked recents apps, by reflection.
//
// MiuiHome locks an app through miui.process.ProcessManager (miui-framework.jar, on the boot
// classpath), whose static methods call the "ProcessManager" system service (miui.IProcessManager,
// ProcessManagerService in miui-services.jar):
//   - List<String> getLockedApplication(int userId): no permission check; the user's list plus
//     the -100 ("all users") default list;
//   - void updateApplicationLockedState(String pkg, int userId, boolean locked): allowed for
//     uid < 10000 or a caller whose ApplicationInfo has FLAG_SYSTEM or FLAG_UPDATED_SYSTEM_APP
//     (ProcessManagerService.checkPermission → ProcessUtils.isSystemApp), which our priv-app has;
//     otherwise a SecurityException. The service persists it in Settings.System "locked_apps".
// HyperOS's memory cleaning and process policy respect this list (ProcessPolicy.promoteLockedApp),
// so our locks are mirrored into it. Everything is guarded: without the class or the service, we
// log once and keep only our own list (HgTaskLockStore).
package com.android.quickstep;

import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class HgMiuiProcessLocks {
    private HgMiuiProcessLocks() {}

    private static final String TAG = "HgTaskMenu";
    private static final String CLASS = "miui.process.ProcessManager";

    private static boolean sResolved;
    @Nullable private static Method sGetLocked;
    @Nullable private static Method sUpdateLocked;
    private static boolean sLoggedFailure;

    private static synchronized void resolve() {
        if (sResolved) return;
        sResolved = true;
        try {
            Class<?> c = Class.forName(CLASS);
            sGetLocked = c.getMethod("getLockedApplication", int.class);
            sUpdateLocked = c.getMethod("updateApplicationLockedState",
                    String.class, int.class, boolean.class);
        } catch (Throwable t) {
            sGetLocked = null;
            sUpdateLocked = null;
            logFailureOnce("HyperOS locked-app API not available, using our own list only", t);
        }
    }

    private static synchronized void logFailureOnce(String msg, Throwable t) {
        if (sLoggedFailure) return;
        sLoggedFailure = true;
        Log.w(TAG, msg + ": " + t);
    }

    /** HyperOS's locked packages for {@code userId}, or null if its API is unavailable. */
    @Nullable
    static List<String> getLocked(int userId) {
        resolve();
        Method m = sGetLocked;
        if (m == null) return null;
        try {
            Object r = m.invoke(null, userId);
            if (!(r instanceof List)) return null;
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) r) {
                if (o instanceof String) out.add((String) o);
            }
            return out;
        } catch (Throwable t) {
            logFailureOnce("ProcessManager.getLockedApplication failed", t);
            return null;
        }
    }

    /** Mirrors a lock into HyperOS's list. @return false if that failed (logged once). */
    static boolean setLocked(String packageName, int userId, boolean locked) {
        resolve();
        Method m = sUpdateLocked;
        if (m == null) return false;
        try {
            m.invoke(null, packageName, userId, locked);
            return true;
        } catch (Throwable t) {
            logFailureOnce("ProcessManager.updateApplicationLockedState failed", t);
            return false;
        }
    }
}
