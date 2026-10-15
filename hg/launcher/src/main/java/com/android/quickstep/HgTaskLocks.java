// HyperGestures: the rules of locked recents tasks, extracted so they are testable.
//
// HyperOS (MiuiHome) lets you lock an app in recents: a locked card survives Clear all. It locks
// by app, not by task (a task id changes when the app restarts), so a lock is keyed by package and
// user. A task view (a split pair holds two tasks) counts as locked when any of its tasks is.
package com.android.quickstep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HgTaskLocks {
    private HgTaskLocks() {}

    private static final char SEPARATOR = ':';

    /**
     * The key of a lock: {@code "<package>:<userId>"}. Package names never contain ':', so the key
     * splits back unambiguously at the last ':'.
     */
    public static String key(String packageName, int userId) {
        return packageName + SEPARATOR + userId;
    }

    /** The package of a {@link #key}, or null if it is not a key. */
    public static String packageOf(String key) {
        int i = key == null ? -1 : key.lastIndexOf(SEPARATOR);
        return i > 0 ? key.substring(0, i) : null;
    }

    /** The user of a {@link #key}, or -1 if it is not a key. */
    public static int userOf(String key) {
        int i = key == null ? -1 : key.lastIndexOf(SEPARATOR);
        if (i <= 0 || i == key.length() - 1) return -1;
        try {
            return Integer.parseInt(key.substring(i + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True if any of a task view's task keys is locked. */
    public static boolean anyLocked(Collection<String> viewKeys, Set<String> locked) {
        for (String k : viewKeys) {
            if (locked.contains(k)) return true;
        }
        return false;
    }

    /**
     * The task views Clear all removes: the indices (in {@code viewKeys} order) of the views with
     * no locked task. The others stay (in recents and in the system).
     *
     * @param viewKeys for each task view, the {@link #key}s of its tasks
     */
    public static List<Integer> clearableViews(List<? extends Collection<String>> viewKeys,
            Set<String> locked) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < viewKeys.size(); i++) {
            if (!anyLocked(viewKeys.get(i), locked)) out.add(i);
        }
        return out;
    }

    /**
     * Our lock set after importing the apps a user locked in stock HyperOS (its system list,
     * {@code miui.process.ProcessManager.getLockedApplication(userId)}). Import only adds: a lock
     * removed on our side is not brought back by a later import, because import runs once per user.
     */
    public static Set<String> withImported(Set<String> ours, int userId,
            Collection<String> systemPackages) {
        Set<String> out = new HashSet<>(ours);
        if (systemPackages == null) return Collections.unmodifiableSet(out);
        for (String pkg : systemPackages) {
            if (pkg != null && !pkg.isEmpty()) out.add(key(pkg, userId));
        }
        return Collections.unmodifiableSet(out);
    }
}
