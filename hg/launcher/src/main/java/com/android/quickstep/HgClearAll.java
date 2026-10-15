// HyperGestures: Clear all as in HyperOS recents (docs/research/
// 2026-10-09-miuihome-recents-features.md §1): besides removing the cards' tasks, it kills the
// cleared apps through HyperOS's one-key clean (HgProcessKill.oneKeyClean, policy 1) and then
// toasts how much memory that freed.
//
// HyperOS's one-key clean removes every recent task and kills every background app except its
// own locked apps and the whitelist it is given. Our card locks (HgTaskLockStore) are ours, so,
// as plan ruling R1: the whitelist holds the packages of the cards Clear all keeps, the HOME app
// (Nova here; HyperOS only knows its own launchers) and ourselves, and the kept cards' task ids.
// As MiuiHome (Task.isProtected = locked or launch target; plan ruling R1 as amended), the app
// recents was opened over (the running task, unless that is HOME) keeps its card like a locked
// one, is whitelisted (package and task) and recents returns to it at the end
// (RecentsDismissUtils.dismissAndReturnToRunning); opened from HOME, Clear all ends at HOME. This
// runs in place of the cards' task removal, which stays the fallback when HyperOS does not take
// the kill. The whitelist is by package, as MiuiHome's: another, unlocked task of a kept app
// survives too and comes back as a card next time, as do the tasks of apps HyperOS deems
// perceptible (music, navigation).
//
// The toast follows MiuiHome's RecentsContainer: available memory at the tap and again AFTER_MS
// after the clean; up to NOTHING_FREED_KB freed is "Nothing to clear in cache", otherwise MB or
// GB, in our own overlay window (HgToastOverlay): by then recents is in the background, and
// HyperOS drops background apps' toasts. With HyperOS's clean refused (HyperOS 4: HgProcessKill),
// the tasks are removed the AOSP way and the removed apps killed with killBackgroundProcesses;
// the memory is then read AFTER_MS after those kills.
// Off (Settings.Global hypergestures_clear_kills=0) Clear all is as before: no kill, no toast, all
// unlocked cards fly out and recents goes home.
package com.android.quickstep;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.systemui.shared.recents.model.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HgClearAll {
    private static final String TAG = "HgClearAll";
    /**
     * When the memory is read again. MiuiHome reads it 300 ms after its clear animation, about a
     * second after it sent the clean; HyperOS runs the clean later on its own handler, so reading
     * 300 ms after ours would mostly miss the kills.
     */
    static final long AFTER_MS = 1000;
    /** Freed memory up to this (10 MB) counts as nothing. */
    static final long NOTHING_FREED_KB = 10 * 1024;

    private final Context mContext;
    private final long mAvailBeforeKb;

    private HgClearAll(Context appContext, long availBeforeKb) {
        mContext = appContext;
        mAvailBeforeKb = availBeforeKb;
    }

    /**
     * At the Clear all tap (main thread): notes the available memory for the toast. Null when
     * {@link HgRecentsSettings#CLEAR_KILLS} is off.
     */
    @Nullable
    public static HgClearAll begin(Context context) {
        if (!HgRecentsSettings.isOn(context, HgRecentsSettings.CLEAR_KILLS)) return null;
        Context app = context.getApplicationContext();
        return new HgClearAll(app, availKb(app));
    }

    /**
     * On a background thread, once the cards flew out, in place of {@code removeTasks} (the AOSP
     * removal of {@code removingTasks}): HyperOS's one-key clean keeping {@code keptTasks} (the
     * locked cards' tasks) and {@code runningTasks} (the card of the app recents was opened over,
     * if any, taken at the tap). When HyperOS does not take it (HgProcessKill: refused on
     * HyperOS 4), {@code removeTasks} runs and then the AOSP kill of the removed, not
     * whitelisted packages. The message follows either way.
     */
    public void clean(List<Task> removingTasks, List<Task> keptTasks, List<Task> runningTasks,
            Runnable removeTasks) {
        Plan plan = plan(ids(removingTasks), ids(keptTasks), packages(keptTasks),
                ids(runningTasks), packages(runningTasks), HgHome.packageName(mContext),
                mContext.getPackageName());
        long messageDelay = AFTER_MS;
        if (plan.removingTaskIds.isEmpty()) {
            // Nothing but kept cards: MiuiHome skips the clean ("removing task is empty").
        } else if (!HgProcessKill.oneKeyClean(plan.removingTaskIds, plan.whiteList,
                plan.whiteListTaskIds)) {
            removeTasks.run();
            List<String> removed = new ArrayList<>();
            for (Task task : removingTasks) {
                if (plan.removingTaskIds.contains(task.key.id)) {
                    removed.add(HgTaskLockStore.packageOf(task));
                }
            }
            HgProcessKill.killBackground(mContext,
                    HgProcessKill.clearKillPackages(removed, plan.whiteList), "clear all");
            messageDelay += HgProcessKill.KILL_DELAY_MS;
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::showToast, messageDelay);
    }

    /** Whether a card's tasks are HOME's (main thread: one package lookup). */
    public static boolean isHome(Context context, List<Task> tasks) {
        String home = HgHome.packageName(context);
        if (home == null) return false;
        for (Task task : tasks) {
            if (home.equals(HgTaskLockStore.packageOf(task))) return true;
        }
        return false;
    }

    private static List<Integer> ids(List<Task> tasks) {
        List<Integer> out = new ArrayList<>();
        for (Task task : tasks) out.add(task.key.id);
        return out;
    }

    /** Each task's package (null if it has none), in the order of {@code tasks}. */
    private static List<String> packages(List<Task> tasks) {
        List<String> out = new ArrayList<>();
        for (Task task : tasks) out.add(HgTaskLockStore.packageOf(task));
        return out;
    }

    private void showToast() {
        long afterKb = availKb(mContext);
        String text = toastText(mAvailBeforeKb, afterKb,
                mContext.getString(R.string.hg_memory_clear_nothing_result),
                mContext.getString(R.string.hg_memory_clear_result_mega),
                mContext.getString(R.string.hg_memory_clear_result_giga), Locale.getDefault());
        Log.i(TAG, "cleared: " + text + " (before " + mAvailBeforeKb / 1024 + " MB, after "
                + afterKb / 1024 + " MB)");
        HgToastOverlay.show(mContext, text);
    }

    /** Available memory in KB (ActivityManager's; MiuiHome asks HyperOS's perfshielder). */
    private static long availKb(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return 0;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem / 1024;
    }

    /** What the one-key clean is given. */
    static final class Plan {
        final List<Integer> removingTaskIds;
        final List<String> whiteList;
        final List<Integer> whiteListTaskIds;

        Plan(List<Integer> removingTaskIds, List<String> whiteList,
                List<Integer> whiteListTaskIds) {
            this.removingTaskIds = removingTaskIds;
            this.whiteList = whiteList;
            this.whiteListTaskIds = whiteListTaskIds;
        }
    }

    /**
     * The one-key clean's lists, without duplicates or empty names. Whitelist: the kept (locked)
     * cards' apps, the running app, HOME, our own package. Whitelist task ids: the kept cards'
     * tasks, then the running ones. Removing: {@code removing} without the whitelisted task ids.
     * The running tasks ({@code runningIds} with the parallel {@code runningPackages}: one task,
     * or both halves of a split card) are skipped when they are HOME's, which is never a card to
     * keep; one without a package keeps only its task id.
     */
    static Plan plan(List<Integer> removing, List<Integer> keptIds,
            Collection<String> keptPackages, List<Integer> runningIds,
            List<String> runningPackages, @Nullable String homePackage,
            @Nullable String ownPackage) {
        Set<String> packages = new LinkedHashSet<>();
        Set<Integer> taskIds = new LinkedHashSet<>(keptIds);
        addPackages(packages, keptPackages);
        for (int i = 0; i < runningIds.size(); i++) {
            String pkg = i < runningPackages.size() ? runningPackages.get(i) : null;
            if (pkg != null && pkg.equals(homePackage)) continue;
            taskIds.add(runningIds.get(i));
            addPackages(packages, Collections.singletonList(pkg));
        }
        addPackages(packages, Arrays.asList(homePackage, ownPackage));
        List<Integer> removingOut = new ArrayList<>();
        for (Integer id : removing) {
            if (!taskIds.contains(id)) removingOut.add(id);
        }
        return new Plan(removingOut, new ArrayList<>(packages), new ArrayList<>(taskIds));
    }

    private static void addPackages(Set<String> out, Collection<String> packages) {
        for (String pkg : packages) {
            if (pkg != null && !pkg.isEmpty()) out.add(pkg);
        }
    }

    /**
     * MiuiHome's toast text (RecentsContainer.getToastMsg): freed = after - before (not below 0);
     * up to {@link #NOTHING_FREED_KB} it is {@code nothing}, under 1024 MB whole MB in
     * {@code megaTemplate} ("%1$sMB cleared"), else GB to one decimal in {@code gigaTemplate}.
     */
    static String toastText(long beforeKb, long afterKb, String nothing, String megaTemplate,
            String gigaTemplate, Locale locale) {
        long freedKb = Math.max(0, afterKb - beforeKb);
        if (freedKb <= NOTHING_FREED_KB) return nothing;
        long mb = freedKb / 1024;
        if (mb < 1024) {
            return String.format(locale, megaTemplate, String.format(locale, "%d", mb));
        }
        return String.format(locale, gigaTemplate, String.format(locale, "%.1f", mb / 1024f));
    }
}
