// HyperGestures: lock or unlock a recents card's app (HgTaskLockStore), shared by the card menu's
// Lock entry, the swipe-down gesture (HgTaskLockSwipeController) and the hold buttons
// (HgTaskActionsView). A card of a split pair holds two apps: they are locked and unlocked together,
// following the first one's state.
package com.android.quickstep;

import android.content.Context;

import androidx.annotation.Nullable;

import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.Task;

public final class HgTaskLocking {
    private HgTaskLocking() {}

    /** The card's first app with a package, or null (nothing on the card can be locked). */
    @Nullable
    static Task firstLockable(TaskView taskView) {
        for (TaskContainer container : taskView.getTaskContainers()) {
            if (HgTaskLockStore.packageOf(container.getTask()) != null) return container.getTask();
        }
        return null;
    }

    /** Whether the card's app is locked (the first lockable app's state). */
    public static boolean isLocked(Context context, TaskView taskView) {
        Task task = firstLockable(taskView);
        return task != null && HgTaskLockStore.get(context).isLocked(task);
    }

    /** Whether anything on the card can be locked. */
    public static boolean canLock(TaskView taskView) {
        return firstLockable(taskView) != null;
    }

    /** Locks or unlocks every app on the card, and updates the lock badges. */
    public static void setLocked(Context context, TaskView taskView, boolean locked) {
        HgTaskLockStore store = HgTaskLockStore.get(context);
        RecentsView<?, ?> recentsView = taskView.getRecentsView();
        for (TaskContainer container : taskView.getTaskContainers()) {
            Task task = container.getTask();
            String pkg = HgTaskLockStore.packageOf(task);
            if (pkg == null) continue;
            store.setLocked(pkg, task.key.userId, locked);
            if (recentsView != null) {
                HgTaskLockBadge.refresh(recentsView, HgTaskLocks.key(pkg, task.key.userId), locked);
            }
        }
    }

    /** Flips the card's lock state; returns the new state. */
    public static boolean toggle(Context context, TaskView taskView) {
        boolean locked = !isLocked(context, taskView);
        setLocked(context, taskView, locked);
        return locked;
    }
}
