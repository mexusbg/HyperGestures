// HyperGestures: "Fix App info back" (Settings.Global hypergestures_app_info_fix, on unless "0").
// Any app opens App info the standard way: action android.settings.APPLICATION_DETAILS_SETTINGS,
// data package:<pkg>. That resolves to Settings' InstalledAppDetails, which HyperOS redirects to
// Security's App info (com.miui.appmanager.ApplicationsDetailsActivity); opened that way, Security
// finishes it when the user taps "App permissions", so Back leaves the task (owner's phone, adb).
// Opened MiuiHome's way (HgAppInfo: its own action, NEW_TASK | EXCLUDE_FROM_RECENTS) it stays and
// Back returns to it. This invisible activity takes the standard request and opens MiuiHome's.
//
// It wins the resolution by intent-filter priority (manifest): AOSP Settings' filter for the
// action is android:priority="1" (Settings AndroidManifest.xml, activity-alias
// .applications.InstalledAppDetails), ours is 100. PackageManager keeps a priority above 0 only
// for privileged apps (ComponentResolver.adjustPriority clamps everyone else's to 0), which we
// are; the action is not one of its protected ones (SEND, SENDTO, SEND_MULTIPLE, VIEW).
//
// Security: the activity is exported, so any app can also start it explicitly with any action,
// data, extras, flags or clip data, and whatever it starts is started as our privileged uid. So
// it reads only the action and the data of the request, never its extras (not even hasExtra:
// that unparcels them), and never starts the request it got: a request that is not
// APPLICATION_DETAILS_SETTINGS with a valid package:<name> starts nothing. What it starts is
// built fresh from the validated package name alone: MiuiHome's intent for our own user, or the
// standard App info request (action + package data, no extras, no clip data, no selector, flags
// NEW_TASK or FORWARD_RESULT only) sent explicitly to a FLAG_SYSTEM resolver other than us,
// preferably com.android.settings; none: nothing. A user extra (another user's App info, a work
// profile, Second Space) is therefore ignored, never forwarded: the request opens our own user's
// App info of that package. Forwarded with such an extra, Settings would check the launching
// package, us, instead of the real caller.
//
// It forwards (stock App info) instead of the MiuiHome hop when the switch is off (and disables
// itself, in case adb changed the key since the last sync), the caller wants a result (MiuiHome's
// NEW_TASK would cancel it at once; FORWARD_RESULT without NEW_TASK hands it back), Settings or
// Security asked (a UX hint only: their own flows stay stock; the caller is the result caller's
// package or getLaunchedFromPackage, both set by the system, so mostly unknown and never trusted
// for anything else), MIUI's screen does not resolve, or the hop throws.
//
// The switch enables or disables this component (syncComponent, DONT_KILL_APP): off, Android
// resolves exactly as stock, without the hop. The settings page applies it on each toggle and
// opening, and the gesture service's start and BOOT_COMPLETED apply it too, so an adb change of
// the key is honoured from the next start. A write only happens when the state differs, since each
// one broadcasts PACKAGE_CHANGED for our package.
//
// It runs in the settings page's ":settings" process: an App info request never cold-starts the
// gesture provider's process, and the module's crash watch ignores that process
// (HG_CRASH_IGNORED), so a crash here can never trigger a rollback.
package com.android.quickstep;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import com.android.launcher3.util.Executors;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class HgAppInfoTrampoline extends Activity {
    private static final String TAG = "HgAppInfoHop";

    static final String ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS";
    static final String SCHEME = "package";
    /** Settings' App info: the preferred stock resolver, and a caller left as stock. */
    static final String SETTINGS_PKG = "com.android.settings";
    /** Security's App info: a caller left as stock. */
    static final String SECURITY_PKG = "com.miui.securitycenter";

    /** Android's package-name rule (letters first in each segment); 255 chars at most. */
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
    static final int MAX_PACKAGE_LENGTH = 255;

    /** componentState: the component is already as wanted. */
    static final int NO_CHANGE = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            route();
        } catch (RuntimeException e) {
            Log.w(TAG, "App info request dropped", e);
        }
        // Theme.NoDisplay: finish before onResume, without a close animation.
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        finish();
    }

    private void route() {
        Intent in = getIntent();
        Uri data = in.getData(); // the action and data only: never the extras
        String pkg = requestPackage(in.getAction(),
                data == null ? null : data.getScheme(),
                data == null ? null : data.getSchemeSpecificPart());
        if (pkg == null) {
            Log.w(TAG, "not an App info request for a package; nothing started");
            return;
        }
        boolean wantsResult = getCallingActivity() != null;
        boolean on = true;
        try {
            on = HgRecentsSettings.isOn(this, HgRecentsSettings.APP_INFO_FIX);
            if (!on) syncComponent(this); // adb turned it off since the last sync
            String caller = callerHint();
            if (hopsToMiui(on, wantsResult, caller)) {
                Intent miui = HgAppInfo.buildIntent(pkg, Process.myUserHandle().getIdentifier());
                if (getPackageManager().resolveActivity(miui, 0) != null) {
                    Log.i(TAG, "App info of " + pkg + " opened the MiuiHome way");
                    startActivity(miui);
                    return;
                }
                Log.i(TAG, "no MIUI App info here, forwarding");
            } else {
                Log.i(TAG, "forwarding App info of " + pkg + " (on=" + on + " result="
                        + wantsResult + " caller=" + caller + ")");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "App info hop failed, forwarding", e);
        }
        forward(pkg, wantsResult);
    }

    /**
     * The caller, as a UX hint only: the result caller's package, else the launching package when
     * the system tells us (it mostly does not). Set by the system, never from the request.
     */
    private String callerHint() {
        String calling = getCallingPackage();
        return calling != null ? calling : getLaunchedFromPackage();
    }

    /** The stock App info of {@code pkg}, built fresh, to a system resolver; none: nothing. */
    private void forward(String pkg, boolean wantsResult) {
        Intent query = new Intent(ACTION, Uri.fromParts(SCHEME, pkg, null));
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(query,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY);
        List<Candidate> candidates = new ArrayList<>(found.size());
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            boolean system = ai.applicationInfo != null
                    && (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            candidates.add(new Candidate(ai.packageName, ai.name, system));
        }
        Forward f = forwardOf(pkg, wantsResult, candidates, getPackageName());
        if (f == null) {
            Log.w(TAG, "no system App info besides ours; nothing started");
            return;
        }
        Intent out = new Intent(f.action, Uri.fromParts(SCHEME, f.pkg, null));
        out.setComponent(new ComponentName(f.componentPackage, f.componentClass));
        out.setFlags(f.flags);
        startActivity(out);
    }

    /** One resolver of the stock App info request. */
    static final class Candidate {
        final String pkg;
        final String cls;
        final boolean system;

        Candidate(String pkg, String cls, boolean system) {
            this.pkg = pkg;
            this.cls = cls;
            this.system = system;
        }
    }

    /**
     * The whole forwarded request: these fields and nothing else become the started intent
     * (action, package data, flags, explicit component).
     */
    static final class Forward {
        final String action;
        final String pkg;
        final int flags;
        final String componentPackage;
        final String componentClass;

        Forward(String action, String pkg, int flags, String componentPackage,
                String componentClass) {
            this.action = action;
            this.pkg = pkg;
            this.flags = flags;
            this.componentPackage = componentPackage;
            this.componentClass = componentClass;
        }
    }

    /**
     * The validated package of an App info request, or null (start nothing) if the action is not
     * APPLICATION_DETAILS_SETTINGS, the scheme is not "package", or the name is empty, too long
     * or not a package name.
     */
    static String requestPackage(String action, String scheme, String schemeSpecificPart) {
        if (!ACTION.equals(action)) return null;
        return packageName(scheme, schemeSpecificPart);
    }

    /** The package name of {@code package:<name>} data, or null if it is not a valid one. */
    static String packageName(String scheme, String schemeSpecificPart) {
        if (!SCHEME.equals(scheme) || schemeSpecificPart == null) return null;
        String name = schemeSpecificPart;
        if (name.isEmpty() || name.length() > MAX_PACKAGE_LENGTH) return null;
        return PACKAGE_NAME.matcher(name).matches() ? name : null;
    }

    /** Whether the request is opened the MiuiHome way rather than as stock App info. */
    static boolean hopsToMiui(boolean on, boolean wantsResult, String caller) {
        return on && !wantsResult && !SETTINGS_PKG.equals(caller) && !SECURITY_PKG.equals(caller);
    }

    /**
     * The resolver to forward to: com.android.settings if it is a system resolver, else the
     * first (best, as PackageManager orders them) system resolver that is not us; -1 none.
     */
    static int pickResolver(List<Candidate> candidates, String ownPackage) {
        int first = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c == null || !c.system || c.pkg == null || c.cls == null
                    || c.pkg.equals(ownPackage)) {
                continue;
            }
            if (SETTINGS_PKG.equals(c.pkg)) return i;
            if (first < 0) first = i;
        }
        return first;
    }

    /**
     * The forwarded request's flags: FORWARD_RESULT alone for a result caller (with NEW_TASK
     * the system would cancel the result at once), else NEW_TASK.
     */
    static int forwardFlags(boolean wantsResult) {
        return wantsResult ? Intent.FLAG_ACTIVITY_FORWARD_RESULT : Intent.FLAG_ACTIVITY_NEW_TASK;
    }

    /** The forwarded request for {@code pkg}, or null if no system resolver but us exists. */
    static Forward forwardOf(String pkg, boolean wantsResult, List<Candidate> candidates,
            String ownPackage) {
        int i = pickResolver(candidates, ownPackage);
        if (i < 0) return null;
        Candidate c = candidates.get(i);
        return new Forward(ACTION, pkg, forwardFlags(wantsResult), c.pkg, c.cls);
    }

    /**
     * The enabled setting to write for the switch's state {@code on}, given the component's
     * {@code current} one; NO_CHANGE if it already matches. On is the manifest default (enabled).
     */
    static int componentState(boolean on, int current) {
        boolean enabled = current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                && current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                && current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        if (on == enabled) return NO_CHANGE;
        return on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /**
     * Enables or disables this component for the switch (DONT_KILL_APP: the gesture provider
     * keeps running), writing only when the state differs (each write broadcasts PACKAGE_CHANGED
     * for our package). A binder call or two. False if it failed.
     */
    public static boolean syncComponent(Context context) {
        try {
            boolean on = HgRecentsSettings.isOn(Settings.Global.getString(
                    context.getContentResolver(), HgRecentsSettings.APP_INFO_FIX));
            PackageManager pm = context.getPackageManager();
            ComponentName self = new ComponentName(context, HgAppInfoTrampoline.class);
            int state = componentState(on, pm.getComponentEnabledSetting(self));
            if (state == NO_CHANGE) return true;
            pm.setComponentEnabledSetting(self, state, PackageManager.DONT_KILL_APP);
            Log.i(TAG, "App info fix " + (on ? "enabled" : "disabled") + " (user "
                    + Process.myUserHandle().getIdentifier() + ")");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "App info fix component not synced", e);
            return false;
        }
    }

    /** syncComponent on the helper thread (service start, the settings page's opening). */
    public static void syncComponentLater(Context context) {
        Context app = context.getApplicationContext();
        Executors.UI_HELPER_EXECUTOR.execute(() -> syncComponent(app));
    }
}
