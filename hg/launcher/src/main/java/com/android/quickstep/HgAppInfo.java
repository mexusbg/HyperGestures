package com.android.quickstep;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.quickstep.views.TaskContainer;

/**
 * App info the way MiuiHome opens it (PackageManagerHelper.createAppDetailActivityIntent).
 * Settings' APPLICATION_DETAILS_SETTINGS is redirected by HyperOS to Security's App info, which
 * then closes itself on "App permissions"; this action opens it as its own task instead.
 */
public final class HgAppInfo {
    private static final String TAG = "HgAppInfo";

    static final String ACTION = "miui.intent.action.APP_MANAGER_APPLICATION_DETAIL";
    /**
     * Security, which owns the screen. MiuiHome's intent is implicit; ours names the package so
     * no other app declaring the action can receive the start (and learn the package name).
     */
    static final String SECURITY_PACKAGE = "com.miui.securitycenter";
    static final String EXTRA_USER_ID = "miui.intent.extra.USER_ID";
    static final String EXTRA_PACKAGE = "package_name";
    /** FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS. */
    static final int FLAGS = 0x10800000;

    private HgAppInfo() {}

    /** MiuiHome's App info intent for {@code packageName} of {@code userId}. */
    static Intent buildIntent(String packageName, int userId) {
        Intent intent = new Intent();
        intent.setAction(ACTION);
        intent.setPackage(SECURITY_PACKAGE);
        intent.putExtra(EXTRA_USER_ID, userId);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        intent.setFlags(FLAGS);
        return intent;
    }

    /**
     * Opens App info for the card; false (nothing started) when this device has no such screen or
     * the card has no package, so the caller falls back to the AOSP shortcut.
     */
    public static boolean open(Context context, TaskContainer container) {
        try {
            String pkg = HgTaskLockStore.packageOf(container.getTask());
            if (pkg == null) return false;
            Intent intent = buildIntent(pkg, container.getTask().key.userId);
            if (context.getPackageManager().resolveActivity(intent, 0) == null) return false;
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Security App info unavailable, using the AOSP one", e);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "App info intent failed, using the AOSP one", e);
            return false;
        }
    }
}
