// HyperGestures: HyperOS's recents process kills, by reflection (docs/research/
// 2026-10-09-miuihome-recents-features.md §1, §2).
//
// MiuiHome does not just remove a dismissed task: it asks HyperOS's ProcessManager service to kill
// the app too, through the static miui.process.ProcessManager.kill(miui.process.ProcessConfig)
// (miui-framework.jar, on the boot classpath; binder service "ProcessManager"):
//   - a card swiped away: ProcessConfig(7 = POLICY_SWIPE_UP_CLEAN, pkg, userId, taskId) with
//     setRemoveTaskNeeded(true), per task of the card;
//   - Clear all: ProcessConfig(1 = POLICY_ONE_KEY_CLEAN) with the task ids to remove, the
//     packages and task ids to keep (setWhiteList / setWhiteListTaskId), setRemoveTaskNeeded(true).
// The service allows callers with uid < 10000 or FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP, which our
// priv-app has (the check HgMiuiProcessLocks relies on too); otherwise kill() returns false.
// kill() returns true once HyperOS queued the work (ProcessSceneCleaner runs it on its handler).
// Anything missing or failing returns false (logged once) and the caller removes the tasks the
// AOSP way. Call from a background thread: kill() is a binder call.
//
// On HyperOS 4 it is refused (0.3.9.26 phone log, "SystemPressureControl: Permission Denial:
// ProcessManager.kill()"): SystemPressureController.checkKillProcPermission only lets hard-coded
// callers through (com.miui.home, com.miui.securitycenter, com.miui.cleaner, powerkeeper...), and
// no permission gets us in. A refusal is remembered for the process lifetime (each call would log
// another Permission Denial in system_server), and the kill falls back to AOSP's: the tasks are
// removed as before, then ActivityManager.killBackgroundProcesses for their packages
// (killBackground; KILL_BACKGROUND_PROCESSES, and KILL_ALL_BACKGROUND_PROCESSES to kill other
// packages' processes on Android 14+). That kills only processes that are in the background by
// then: an app with a foreground service (music, navigation) lives on, as with HyperOS's clean.
package com.android.quickstep;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.util.Executors;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.Task;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

public final class HgProcessKill {
    private HgProcessKill() {}

    private static final String TAG = "HgProcessKill";
    static final int POLICY_ONE_KEY_CLEAN = 1;
    static final int POLICY_SWIPE_UP_CLEAN = 7;

    /** Where failures are logged (Log.w; a JVM test swaps it, android.util.Log is a stub there). */
    interface Warn {
        void warn(String msg);
    }

    static Warn sWarn = msg -> Log.w(TAG, msg);

    private static boolean sResolved;
    // The swipe-up kill and Clear all's kill are resolved apart, so a missing Clear all setter
    // does not cost the swipe-up kill.
    @Nullable private static Method sKill;
    @Nullable private static Method sSetRemoveTaskNeeded;
    @Nullable private static Constructor<?> sNewForTask;
    private static boolean sSwipeUpOk;
    @Nullable private static Constructor<?> sNewForPolicy;
    @Nullable private static Method sSetRemovingTaskIdList;
    @Nullable private static Method sSetWhiteList;
    @Nullable private static Method sSetWhiteListTaskId;
    private static boolean sOneKeyOk;
    /** HyperOS refused a kill (or it threw): not asked again in this process. */
    private static volatile boolean sRefused;
    /**
     * How long after the task removal the AOSP kill runs: the removed app's process must have
     * dropped to background importance first, or killBackgroundProcesses leaves it alone.
     */
    static final long KILL_DELAY_MS = 500;
    /** After a kill, until the system counts its memory as free again. */
    static final long RAM_SETTLE_MS = 400;
    /** The failure kinds already logged (each once per process). */
    private static final Set<String> sLogged = new HashSet<>();

    private static synchronized void resolve() {
        if (sResolved) return;
        sResolved = true;
        Class<?> config;
        try {
            config = Class.forName("miui.process.ProcessConfig");
            Class<?> manager = Class.forName("miui.process.ProcessManager");
            sKill = manager.getMethod("kill", config);
            sSetRemoveTaskNeeded = config.getMethod("setRemoveTaskNeeded", boolean.class);
        } catch (Throwable t) {
            logOnce("api", "HyperOS ProcessManager.kill not available, removing tasks only", t);
            return;
        }
        try {
            sNewForTask = config.getConstructor(int.class, String.class, int.class, int.class);
            sSwipeUpOk = true;
        } catch (Throwable t) {
            logOnce("swipe-api", "ProcessConfig(policy, pkg, userId, taskId) not available", t);
        }
        try {
            sNewForPolicy = config.getConstructor(int.class);
            sSetRemovingTaskIdList = config.getMethod("setRemovingTaskIdList", List.class);
            sSetWhiteList = config.getMethod("setWhiteList", List.class);
            sSetWhiteListTaskId = config.getMethod("setWhiteListTaskId", List.class);
            sOneKeyOk = true;
        } catch (Throwable t) {
            logOnce("clean-api", "ProcessConfig one-key clean setters not available", t);
        }
    }

    private static void logOnce(String kind, String msg, @Nullable Throwable t) {
        synchronized (sLogged) {
            if (!sLogged.add(kind)) return;
        }
        sWarn.warn(t == null ? msg : msg + ": " + t);
    }

    /**
     * A card dismissed by the user (swiped up, its menu's Close, the delete key), on a background
     * thread in place of ActivityManager's removeTask for each of its {@code tasks}: also kills the
     * app, unless {@link HgRecentsSettings#DISMISS_KILLS} is off. HyperOS's policy-7 kill when it
     * takes it, otherwise {@code removeTask} for the task and, KILL_DELAY_MS later,
     * killBackgroundProcesses for its package, unless another card ({@code otherCardPackages})
     * is of the same package (it may be the user's other window), or it is HOME's or ours.
     * Locked cards are killed too, as in HyperOS (a lock only protects from Clear all and
     * HyperOS's cleaners). Unlike MiuiHome there is no "relaunched meanwhile" guard (it compares
     * the task's last active time); the kill follows the dismiss, as AOSP's removeTask does.
     *
     * Our own package is never killed (its card is our settings page): that would kill recents
     * and the gesture provider with it.
     */
    public static void dismissed(Context context, List<Task> tasks,
            Collection<String> otherCardPackages, IntConsumer removeTask) {
        boolean kill = HgRecentsSettings.isOn(context, HgRecentsSettings.DISMISS_KILLS);
        String own = context.getPackageName();
        List<String> removedPackages = new ArrayList<>();
        for (Task task : tasks) {
            String pkg = killablePackage(HgTaskLockStore.packageOf(task), own);
            if (kill && pkg != null && swipeUpClean(pkg, task.key.userId, task.key.id)) continue;
            removeTask.accept(task.key.id);
            if (kill && pkg != null) removedPackages.add(pkg);
        }
        if (!removedPackages.isEmpty()) {
            killBackground(context, dismissKillPackages(removedPackages, otherCardPackages,
                    HgHome.packageName(context), own), "dismiss");
        }
        // The memory line shows what the kill freed (it runs KILL_DELAY_MS later at most).
        HgRamIndicator.memoryChanged(KILL_DELAY_MS + RAM_SETTLE_MS);
    }

    /**
     * Main thread, before the dismissed card's removal is queued: the packages of the other cards
     * in recents (every card's tasks but {@code dismissed}).
     */
    public static List<String> otherCardPackages(RecentsView<?, ?> recentsView,
            List<Task> dismissed) {
        Set<Integer> dismissedIds = new HashSet<>();
        for (Task task : dismissed) dismissedIds.add(task.key.id);
        List<String> out = new ArrayList<>();
        for (TaskView taskView : recentsView.getTaskViews()) {
            for (TaskContainer container : taskView.getTaskContainers()) {
                Task task = container.getTask();
                if (dismissedIds.contains(task.key.id)) continue;
                String pkg = HgTaskLockStore.packageOf(task);
                if (pkg != null) out.add(pkg);
            }
        }
        return out;
    }

    /**
     * The packages a card dismiss kills the AOSP way: those of its removed tasks, without
     * duplicates, except one another card still shows, HOME's and ours.
     */
    static List<String> dismissKillPackages(Collection<String> removedPackages,
            Collection<String> otherCardPackages, @Nullable String homePackage,
            @Nullable String ownPackage) {
        Set<String> out = new LinkedHashSet<>();
        for (String pkg : removedPackages) {
            if (pkg == null || pkg.isEmpty() || otherCardPackages.contains(pkg)
                    || pkg.equals(homePackage) || pkg.equals(ownPackage)) {
                continue;
            }
            out.add(pkg);
        }
        return new ArrayList<>(out);
    }

    /**
     * The packages Clear all kills the AOSP way: those of the removed tasks, without duplicates,
     * except the whitelisted ones (the locked and running cards' apps, HOME, ours).
     */
    static List<String> clearKillPackages(Collection<String> removedPackages,
            Collection<String> whiteList) {
        Set<String> out = new LinkedHashSet<>();
        for (String pkg : removedPackages) {
            if (pkg != null && !pkg.isEmpty() && !whiteList.contains(pkg)) out.add(pkg);
        }
        return new ArrayList<>(out);
    }

    /**
     * The AOSP kill: KILL_DELAY_MS from now, on UI_HELPER_EXECUTOR,
     * ActivityManager.killBackgroundProcesses for each of {@code packages} (their processes that
     * are in the background by then), logged in one line.
     */
    static void killBackground(Context context, List<String> packages, String what) {
        if (packages.isEmpty()) {
            Log.i(TAG, what + ": no package to kill");
            return;
        }
        Context app = context.getApplicationContext();
        Executors.UI_HELPER_EXECUTOR.getHandler().postDelayed(() -> {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            if (am == null) return;
            List<String> killed = new ArrayList<>();
            for (String pkg : packages) {
                try {
                    am.killBackgroundProcesses(pkg);
                    killed.add(pkg);
                } catch (RuntimeException e) {
                    logOnce("kill-background", "killBackgroundProcesses failed", e);
                }
            }
            Log.i(TAG, what + ": killBackgroundProcesses " + killed);
        }, KILL_DELAY_MS);
    }

    /** {@code pkg}, or null if there is none or it is ours ({@code ownPackage}): not killed. */
    @Nullable
    static String killablePackage(@Nullable String pkg, String ownPackage) {
        return pkg == null || pkg.equals(ownPackage) ? null : pkg;
    }

    /**
     * A swiped-away card's task: removes it and kills its app (policy 7), as MiuiHome's
     * doSwapUPClean. @return false if HyperOS did not take it (the caller removes the task).
     */
    static boolean swipeUpClean(String packageName, int userId, int taskId) {
        resolve();
        if (!sSwipeUpOk || sRefused) return false;
        try {
            Object config = sNewForTask.newInstance(POLICY_SWIPE_UP_CLEAN, packageName, userId,
                    taskId);
            sSetRemoveTaskNeeded.invoke(config, true);
            return kill(config, "swipe up " + packageName + " task " + taskId);
        } catch (Throwable t) {
            logOnce("swipe-config", "ProcessConfig for swipe-up clean failed", t);
            return false;
        }
    }

    /**
     * Clear all: removes the tasks and kills every background app that is not kept (policy 1), as
     * MiuiHome's doClean. HyperOS itself also keeps its locked apps, perceptible apps (music,
     * navigation), the foreground app and its own lists. @return false if HyperOS did not take it.
     */
    static boolean oneKeyClean(List<Integer> removingTaskIds, List<String> keepPackages,
            List<Integer> keepTaskIds) {
        resolve();
        if (!sOneKeyOk || sRefused) return false;
        try {
            Object config = sNewForPolicy.newInstance(POLICY_ONE_KEY_CLEAN);
            sSetRemoveTaskNeeded.invoke(config, true);
            sSetRemovingTaskIdList.invoke(config, new ArrayList<>(removingTaskIds));
            sSetWhiteList.invoke(config, new ArrayList<>(keepPackages));
            sSetWhiteListTaskId.invoke(config, new ArrayList<>(keepTaskIds));
            return kill(config, "clear all: removing " + removingTaskIds + ", keeping "
                    + keepPackages + " / tasks " + keepTaskIds);
        } catch (Throwable t) {
            logOnce("clean-config", "ProcessConfig for one-key clean failed", t);
            return false;
        }
    }

    private static boolean kill(Object config, String what) {
        try {
            boolean ok = Boolean.TRUE.equals(sKill.invoke(null, config));
            if (ok) {
                Log.i(TAG, what);
            } else {
                // Permission denial (logged by the service): not asked again, AOSP kill instead.
                sRefused = true;
                logOnce("refused", "ProcessManager.kill refused (" + what + "), using AOSP's"
                        + " killBackgroundProcesses from now on", null);
            }
            return ok;
        } catch (Throwable t) {
            sRefused = true;
            logOnce("kill", "ProcessManager.kill failed", t);
            return false;
        }
    }
}
