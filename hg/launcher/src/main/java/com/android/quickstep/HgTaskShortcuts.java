// HyperGestures: the recents card menu entries HyperOS has and AOSP doesn't (Lock/Unlock, Floating
// window), and the per-task factory list in HgTaskMenuPolicy order (Modules.kt uses it).
package com.android.quickstep;

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

import android.app.ActivityOptions;
import android.content.Context;
import android.util.Log;
import android.view.View;

import com.android.launcher3.R;
import com.android.launcher3.popup.SystemShortcut;
import com.android.quickstep.views.DesktopTaskView;
import com.android.quickstep.views.GroupedTaskView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HgTaskShortcuts {
    private HgTaskShortcuts() {}

    private static final String TAG = "HgTaskMenu";

    /** The per-task factories in HgTaskMenuPolicy.PER_TASK order (Close is appended by AOSP). */
    public static List<TaskShortcutFactory> perTaskFactories() {
        List<TaskShortcutFactory> out = new ArrayList<>();
        for (HgTaskMenuPolicy.Entry entry : HgTaskMenuPolicy.PER_TASK) {
            switch (entry) {
                case APP_INFO -> out.add(TaskShortcutFactory.APP_INFO);
                case LOCK -> out.add(LOCK);
                case SPLIT_SCREEN -> out.add(SPLIT_SCREEN);
                case FLOATING_WINDOW -> out.add(FLOATING_WINDOW);
                default -> throw new IllegalStateException("not a per-task entry: " + entry);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Lock / Unlock: a locked app's cards survive Clear all (HgTaskLockStore). */
    public static final TaskShortcutFactory LOCK = new TaskShortcutFactory() {
        @Override
        public List<SystemShortcut> getShortcuts(RecentsViewContainer container,
                TaskContainer taskContainer) {
            if (HgTaskLockStore.packageOf(taskContainer.getTask()) == null) return null;
            return Collections.singletonList(new LockShortcut(container, taskContainer));
        }

        @Override
        public boolean showForGroupedTask() {
            return true;
        }
    };

    /**
     * AOSP's SPLIT_SELECT, logging why it is left out (split screen on HyperOS needs a phone
     * check; docs/probe-runbook.md §8 M3).
     */
    public static final TaskShortcutFactory SPLIT_SCREEN = new TaskShortcutFactory() {
        @Override
        public List<SystemShortcut> getShortcuts(RecentsViewContainer container,
                TaskContainer taskContainer) {
            RecentsView<?, ?> recents = taskContainer.getTaskView().getRecentsView();
            int others = otherSplittableCards(recents, taskContainer.getTaskView());
            // No card to pair with: HyperOS's one-app split, the second app picked from the home
            // screen (HgSplitSingle), as MIUI does.
            if (!HgTaskMenuPolicy.showSplitScreen(others) && canSplitAlone(container,
                    taskContainer)) {
                return Collections.singletonList(
                        new SplitAloneShortcut(container, taskContainer));
            }
            if (!HgTaskMenuPolicy.showSplitScreen(others)) {
                Log.i(TAG, "no Split screen for task " + taskContainer.getTask().key.id
                        + ": no other card to pick as the second app");
                return null;
            }
            List<SystemShortcut> shortcuts =
                    TaskShortcutFactory.SPLIT_SELECT.getShortcuts(container, taskContainer);
            if (shortcuts == null || shortcuts.isEmpty()) {
                Task task = taskContainer.getTask();
                RecentsView<?, ?> rv = taskContainer.getTaskView().getRecentsView();
                Log.i(TAG, "no Split screen for task " + task.key.id + ": isDockable="
                        + task.isDockable + " excludeFromRecents="
                        + ((task.key.baseIntent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                                != 0)
                        + " taskViews=" + (rv == null ? -1 : rv.getTaskViewCount())
                        + " taskbar=" + container.getDeviceProfile().getDeviceProperties()
                                .getTaskbarConfiguration().isTaskbarPresent());
            }
            return shortcuts;
        }
    };

    /** A single-app card whose app can split, with HyperOS's one-app split available. */
    private static boolean canSplitAlone(RecentsViewContainer container,
            TaskContainer taskContainer) {
        TaskView taskView = taskContainer.getTaskView();
        return !(taskView instanceof GroupedTaskView) && !(taskView instanceof DesktopTaskView)
                && HgSplitSingle.canSplit(taskContainer.getTask())
                && HgSplitSingle.available(container.asContext());
    }

    /** Split screen with no other card: the app on top, the home screen below (HgSplitSingle). */
    static final class SplitAloneShortcut extends SystemShortcut<RecentsViewContainer> {
        private final TaskContainer mTaskContainer;

        SplitAloneShortcut(RecentsViewContainer target, TaskContainer taskContainer) {
            super(R.drawable.ic_split_horizontal, R.string.recent_task_option_split_screen,
                    target, taskContainer.getItemInfo(), taskContainer.getTaskView());
            mTaskContainer = taskContainer;
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView();
            RecentsView<?, ?> rv = mTarget.getOverviewPanel();
            if (rv == null) return; // recents being torn down
            HgSplitSingle.start(rv, mTaskContainer.getTask().key.id);
        }
    }

    /** Single-app cards other than {@code self} whose app can go into split screen. */
    static int otherSplittableCards(RecentsView<?, ?> recents, TaskView self) {
        if (recents == null) return 0;
        int count = 0;
        for (TaskView taskView : recents.getTaskViews()) {
            if (taskView == self || taskView instanceof GroupedTaskView
                    || taskView instanceof DesktopTaskView) {
                continue;
            }
            for (TaskContainer container : taskView.getTaskContainers()) {
                if (container.getTask().isDockable) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** Floating window: opens the task in HyperOS's floating window (HgFloatingWindow). */
    public static final TaskShortcutFactory FLOATING_WINDOW = new TaskShortcutFactory() {
        @Override
        public List<SystemShortcut> getShortcuts(RecentsViewContainer container,
                TaskContainer taskContainer) {
            TaskView taskView = taskContainer.getTaskView();
            String pkg = HgTaskLockStore.packageOf(taskContainer.getTask());
            boolean show = pkg != null && HgTaskMenuPolicy.showFloatingWindow(
                    HgFloatingWindow.systemSupports(),
                    HgFloatingWindow.appSupports(container.asContext(), pkg),
                    taskView instanceof GroupedTaskView || taskView instanceof DesktopTaskView);
            if (!show) {
                Log.i(TAG, "no Floating window for " + pkg);
                return null;
            }
            return Collections.singletonList(new FloatingWindowShortcut(container, taskContainer));
        }
    };

    static final class LockShortcut extends SystemShortcut<RecentsViewContainer> {
        private final TaskContainer mTaskContainer;

        LockShortcut(RecentsViewContainer target, TaskContainer taskContainer) {
            super(isLocked(target, taskContainer) ? R.drawable.hg_ic_task_unlock : R.drawable.ic_lock,
                    isLocked(target, taskContainer)
                            ? R.string.hg_task_menu_unlock : R.string.hg_task_menu_lock,
                    target, taskContainer.getItemInfo(), taskContainer.getTaskView());
            mTaskContainer = taskContainer;
        }

        private static boolean isLocked(RecentsViewContainer target, TaskContainer container) {
            return HgTaskLockStore.get(target.asContext()).isLocked(container.getTask());
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView();
            Task task = mTaskContainer.getTask();
            String pkg = HgTaskLockStore.packageOf(task);
            if (pkg == null) return;
            HgTaskLockStore store = HgTaskLockStore.get(mTarget.asContext());
            boolean locked = !store.isLocked(pkg, task.key.userId);
            store.setLocked(pkg, task.key.userId, locked);
            RecentsView<?, ?> rv = mTarget.getOverviewPanel();
            if (rv != null) {
                HgTaskLockBadge.refresh(rv, HgTaskLocks.key(pkg, task.key.userId), locked);
            }
        }
    }

    static final class FloatingWindowShortcut extends SystemShortcut<RecentsViewContainer> {
        private final TaskContainer mTaskContainer;

        FloatingWindowShortcut(RecentsViewContainer target, TaskContainer taskContainer) {
            super(R.drawable.hg_ic_task_floating_window, R.string.hg_task_menu_floating_window,
                    target, taskContainer.getItemInfo(), taskContainer.getTaskView());
            mTaskContainer = taskContainer;
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView();
            RecentsView<?, ?> rv = mTarget.getOverviewPanel();
            if (rv == null) return; // recents being torn down
            Task task = mTaskContainer.getTask();
            // As AOSP's freeform item: end a live-tile recents animation first, then start the
            // task in its new windowing mode.
            rv.switchToScreenshot(() -> rv.finishRecentsAnimation(true /* toHome */,
                    false /* shouldPip */, () -> rv.post(() -> launch(rv, task))));
        }

        private void launch(RecentsView<?, ?> rv, Task task) {
            Context context = mTarget.asContext();
            String pkg = HgTaskLockStore.packageOf(task);
            ActivityOptions options = HgFloatingWindow.makeOptions(context, pkg);
            if (options == null) {
                Log.w(TAG, "Floating window: HyperOS gave no options for " + pkg);
                return;
            }
            boolean started = ActivityManagerWrapper.getInstance()
                    .startActivityFromRecents(task.key.id, options);
            Log.i(TAG, "Floating window: task " + task.key.id + " (" + pkg + ") started="
                    + started);
            if (started) {
                // HyperOS leaves recents for home under the floating window.
                rv.startHome();
            }
        }
    }
}
