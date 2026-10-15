// HyperGestures: the ActivityInfo a recents card takes its label and icon from.
//
// TaskIconCache asks for the ActivityInfo of TaskKey.getComponent(), the base intent's component.
// For a task restored from disk after a reboot that is the component saved in the task, which may
// not resolve: Messenger's task 725 stored com.facebook.messenger.neue.MainActivity (the target of
// its launcher alias), the lookup returned null, and the card had no title (2026-10-05). This tries
// that component, then the task's source component (its original or real activity), then the
// package's launcher activity for the task's user, so a card always gets the app's label and icon
// while the app is installed. Worker thread (TaskIconCache's background executor).
package com.android.quickstep;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;

import androidx.annotation.Nullable;

import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.PackageManagerWrapper;

import java.util.ArrayList;
import java.util.List;

public final class HgTaskActivityInfo {
    private HgTaskActivityInfo() {}

    /** The components to look up, in order: the task's component, then its source component. */
    static <T> List<T> candidates(@Nullable T component, @Nullable T sourceComponent) {
        List<T> out = new ArrayList<>(2);
        if (component != null) out.add(component);
        if (sourceComponent != null && !sourceComponent.equals(component)) out.add(sourceComponent);
        return out;
    }

    /** The ActivityInfo for {@code key}'s card, or null if the app is gone. */
    @Nullable
    public static ActivityInfo resolve(Context context, Task.TaskKey key) {
        PackageManagerWrapper pm = PackageManagerWrapper.getInstance();
        for (ComponentName cn : candidates(key.getComponent(), key.sourceComponent)) {
            ActivityInfo info = pm.getActivityInfo(cn, key.userId);
            if (info != null) return info;
        }
        String pkg = key.getPackageName();
        if (pkg == null) return null;
        try {
            LauncherApps apps = context.getSystemService(LauncherApps.class);
            if (apps == null) return null;
            List<LauncherActivityInfo> list = apps.getActivityList(pkg, UserHandle.of(key.userId));
            return list == null || list.isEmpty() ? null : list.get(0).getActivityInfo();
        } catch (RuntimeException e) {
            return null; // user locked or removed, or the package went away
        }
    }
}
