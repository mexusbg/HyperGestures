// HyperGestures: the default HOME app's package, one way for the whole app. Clear all's and the
// swipe-up kill's whitelists (HgClearAll, HgProcessKill), the settings page's sections
// (HgSettingsFragment) and Save logs' info.txt (HgLogCollector) each resolved HOME on their own,
// and info.txt said "HOME: android" while no default was set (the resolver is what HOME then
// resolves to). Here: the HOME role holder from RoleManager when we may read it, else the activity
// HOME resolves to (the role holder too, since Android 10); the chooser (package "android",
// ResolverActivity) and a setup wizard holding HOME during first setup mean "no default HOME"
// (null), as OverviewComponentObserver treats the setup wizard. OverviewComponentObserver keeps
// AOSP's getHomeActivities and HgHomeSwitch maps its result with map().
package com.android.quickstep;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;

public final class HgHome {
    private static final String TAG = "HgHome";

    /** The HOME role (RoleManager.ROLE_HOME). */
    static final String ROLE_HOME = "android.app.role.HOME";
    /** The chooser's package: HOME resolves to it while no default is set. */
    static final String CHOOSER_PACKAGE = "android";
    /** The chooser's activity, in case a firmware hosts it in another package. */
    static final String RESOLVER_ACTIVITY = "com.android.internal.app.ResolverActivity";
    /**
     * Setup wizards that hold HOME during first setup: Google's and MIUI's (Provision). Before
     * the owner picks a launcher they are not a default HOME either.
     */
    static final String[] SETUP_WIZARDS = {
            "com.google.android.setupwizard", "com.android.provision"};

    /**
     * RoleManager.getRoleHolders needs MANAGE_ROLE_HOLDERS (not grantable to a priv-app on
     * AOSP 17): tried once per process, then skipped, so a refusal costs one exception.
     */
    private static volatile boolean sRoleUnreadable;

    private HgHome() {}

    /** The default HOME app's package (Nova on the owner's phone), or null if there is none. */
    @Nullable
    public static String packageName(Context context) {
        String role = roleHolder(context);
        if (role != null) return map(role, null);
        try {
            ResolveInfo home = context.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo ai = home == null ? null : home.activityInfo;
            return ai == null ? null : map(ai.packageName, ai.name);
        } catch (RuntimeException e) {
            Log.w(TAG, "HOME lookup failed: " + e);
            return null;
        }
    }

    /**
     * {@code pkg} (with its activity {@code className} if known) as a default HOME: null for none,
     * the chooser and a setup wizard; else {@code pkg}.
     */
    @Nullable
    public static String map(@Nullable String pkg, @Nullable String className) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (CHOOSER_PACKAGE.equals(pkg) || RESOLVER_ACTIVITY.equals(className)) return null;
        for (String wizard : SETUP_WIZARDS) {
            if (wizard.equals(pkg)) return null;
        }
        return pkg;
    }

    /** The HOME role's holder if RoleManager lets us read it, else null. */
    @Nullable
    private static String roleHolder(Context context) {
        if (sRoleUnreadable) return null;
        try {
            Object rm = context.getSystemService(Context.ROLE_SERVICE);
            if (rm == null) {
                sRoleUnreadable = true;
                return null;
            }
            // A @SystemApi: reflection, so the SDK we compile against need not have it.
            Object holders = rm.getClass().getMethod("getRoleHolders", String.class)
                    .invoke(rm, ROLE_HOME);
            if (!(holders instanceof List)) return null;
            List<?> list = (List<?>) holders;
            return list.isEmpty() || !(list.get(0) instanceof String) ? null
                    : (String) list.get(0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // SecurityException (wrapped in InvocationTargetException) without the permission.
            sRoleUnreadable = true;
            Log.i(TAG, "HOME role holder not readable, resolving HOME instead: "
                    + (e.getCause() != null ? e.getCause() : e));
            return null;
        }
    }
}
