// HyperGestures: the locked recents apps (HgTaskLocks), persisted in our own SharedPreferences.
//
// Our list is the source of truth for recents (lock badge, Clear all). Each lock change is also
// mirrored into HyperOS's own list (HgMiuiProcessLocks) so its memory cleaning keeps the app
// alive, and, once per user, the apps locked in stock HyperOS are imported into our list so they
// stay locked here. Main-thread use; the mirror write runs on UI_HELPER_EXECUTOR.
//
// Reading HyperOS's list is a binder call (ProcessManager.getLockedApplication). It is prefetched
// for our own user on UI_HELPER_EXECUTOR when the store is first created, so isLocked (called
// from every card bind) only reads local state; a user whose import has not finished simply has
// no imported badges until the next bind. Clear all and Lock/Unlock still import synchronously
// (outside the store's lock) if it has not happened yet, waiting for one already in flight, as
// they must not miss a HyperOS lock (HgLockImports).
package com.android.quickstep;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.util.Executors;
import com.android.systemui.shared.recents.model.Task;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HgTaskLockStore {
    private static final String TAG = "HgTaskMenu";
    private static final String PREFS = "hg_task_locks";
    private static final String KEY_LOCKED = "locked";
    /** Users whose HyperOS locked list was imported ("<userId>" strings). */
    private static final String KEY_IMPORTED_USERS = "imported_users";

    @Nullable private static HgTaskLockStore sInstance;

    public static synchronized HgTaskLockStore get(Context context) {
        if (sInstance == null) {
            sInstance = new HgTaskLockStore(context.getApplicationContext());
            sInstance.mImports.importInBackground(Process.myUserHandle().getIdentifier());
        }
        return sInstance;
    }

    private final SharedPreferences mPrefs;
    private final Set<String> mLocked;
    private final Set<String> mImportedUsers;
    private final HgLockImports mImports;

    private HgTaskLockStore(Context appContext) {
        mPrefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mLocked = new HashSet<>(mPrefs.getStringSet(KEY_LOCKED, new HashSet<>()));
        mImportedUsers = new HashSet<>(mPrefs.getStringSet(KEY_IMPORTED_USERS, new HashSet<>()));
        mImports = new HgLockImports(HgMiuiProcessLocks::getLocked, this::merge,
                this::isImported, Executors.UI_HELPER_EXECUTOR);
    }

    /** The package a task is locked by (its base intent's, as HyperOS locks apps). */
    @Nullable
    public static String packageOf(Task task) {
        String pkg = task.key.getPackageName();
        if (pkg == null && task.getTopComponent() != null) {
            pkg = task.getTopComponent().getPackageName();
        }
        return pkg;
    }

    /** The {@link HgTaskLocks#key} of a task, or null if it has no package. */
    @Nullable
    public static String keyOf(Task task) {
        String pkg = packageOf(task);
        return pkg == null ? null : HgTaskLocks.key(pkg, task.key.userId);
    }

    /** Local state only (no binder call); starts {@code userId}'s import if it is due. */
    public boolean isLocked(String packageName, int userId) {
        mImports.importInBackground(userId);
        synchronized (this) {
            return mLocked.contains(HgTaskLocks.key(packageName, userId));
        }
    }

    public boolean isLocked(Task task) {
        String pkg = packageOf(task);
        return pkg != null && isLocked(pkg, task.key.userId);
    }

    /**
     * The task views Clear all removes (HgTaskLocks.clearableViews), as indices into
     * {@code viewKeys}: for each task view, the {@link #keyOf} keys of its tasks.
     */
    public List<Integer> clearableViews(List<? extends List<String>> viewKeys) {
        for (List<String> keys : viewKeys) {
            for (String key : keys) mImports.importNow(HgTaskLocks.userOf(key));
        }
        synchronized (this) {
            return HgTaskLocks.clearableViews(viewKeys, mLocked);
        }
    }

    public void setLocked(String packageName, int userId, boolean locked) {
        mImports.importNow(userId);
        synchronized (this) {
            String key = HgTaskLocks.key(packageName, userId);
            boolean changed = locked ? mLocked.add(key) : mLocked.remove(key);
            if (changed) {
                mPrefs.edit().putStringSet(KEY_LOCKED, new HashSet<>(mLocked)).apply();
            }
        }
        Log.i(TAG, (locked ? "lock " : "unlock ") + packageName + " user " + userId);
        Executors.UI_HELPER_EXECUTOR.execute(() -> {
            if (HgMiuiProcessLocks.setLocked(packageName, userId, locked)) {
                Log.i(TAG, "mirrored to HyperOS ProcessManager: " + packageName + " locked="
                        + locked);
            }
        });
    }

    private synchronized boolean isImported(int userId) {
        return mImportedUsers.contains(String.valueOf(userId));
    }

    private synchronized void merge(int userId, List<String> system) {
        String user = String.valueOf(userId);
        Set<String> merged = HgTaskLocks.withImported(mLocked, userId, system);
        mLocked.clear();
        mLocked.addAll(merged);
        mImportedUsers.add(user);
        mPrefs.edit()
                .putStringSet(KEY_LOCKED, new HashSet<>(mLocked))
                .putStringSet(KEY_IMPORTED_USERS, new HashSet<>(mImportedUsers))
                .apply();
        Log.i(TAG, "imported HyperOS locked apps for user " + userId + ": " + system);
    }
}
