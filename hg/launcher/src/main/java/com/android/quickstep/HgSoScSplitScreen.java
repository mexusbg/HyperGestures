// HyperGestures: client of HyperOS Shell's ISoScSplitScreen (com.android.wm.shell.sosc), which
// SystemUI passes in the onInitialize bundle under "extra_shell_sosc_split_screen" next to AOSP's
// ISplitScreen. See HgSplitRoutePolicy for why split pairs must go through it on HyperOS 4.
//
// There is no AIDL for it in the vendored sources, so the four pair-start calls are written the way
// the device's generated ISoScSplitScreen$Stub$Proxy writes them (Miui-WindowManager-Shell.jar,
// dexdump): interface token, then the arguments in order, "in" Parcelables with
// writeTypedObject(p, 0), all oneway. Codes on OS4.0.0.11 and .13: startTasks 11,
// startIntentAndTask 17, startShortcutAndTask 18, startIntents 20. They take the
// same arguments as AOSP's ISplitScreen calls minus the trailing InstanceId. startTask 8
// (taskId, position, options) is HyperOS's own: SoSc's "single" mode, the task in one half and
// the home screen showing in the other until an app is launched from it (HgSplitSingle). Shell checks
// MANAGE_ACTIVITY_TASKS (ExternalInterfaceBinder), which we hold as for ISplitScreen.
// The codes are not pinned to one HyperOS build: each comes from the phone's own Shell code
// (HgShellInterfaces), and the binder is used only when every call's code and parameters were
// confirmed there (HgShellCalls) and it names DESCRIPTOR; otherwise split pairs go through AOSP's
// ISplitScreen, which is safe on any build (logged once per process).
package com.android.quickstep;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.window.RemoteTransition;

import androidx.annotation.Nullable;

public final class HgSoScSplitScreen {
    private static final String TAG = "HgSplit";

    /** The onInitialize bundle key HyperOS SystemUI puts the ISoScSplitScreen binder under. */
    public static final String BUNDLE_KEY = "extra_shell_sosc_split_screen";
    private static final String DESCRIPTOR = "com.android.wm.shell.sosc.ISoScSplitScreen";
    private static final String SETTING_SOSC_ENABLE = "wmshell.sosc_enable";

    private static boolean sLoggedUnusable;

    private final IBinder mRemote;

    private HgSoScSplitScreen(IBinder remote) {
        mRemote = remote;
    }

    /** The SoSc client for this onInitialize bundle, or null if SystemUI passed none. */
    @Nullable
    public static HgSoScSplitScreen fromBundle(@Nullable Bundle params) {
        IBinder b = params == null ? null : params.getBinder(BUNDLE_KEY);
        Log.i(TAG, "ISoScSplitScreen binder " + (b != null ? "present" : "absent"));
        if (b == null) return null;
        HgShellCalls.Result calls = HgShellInterfaces.get();
        String descriptor = descriptorOf(b);
        if (!HgSplitRoutePolicy.soscBinderUsable(
                calls.usable(HgShellCalls.Feature.SOSC_SPLIT), descriptor, DESCRIPTOR)) {
            String why = calls.usable(HgShellCalls.Feature.SOSC_SPLIT)
                    ? "binder " + descriptor : calls.reason(HgShellCalls.Feature.SOSC_SPLIT);
            logUnusableOnce("ISoScSplitScreen not used, split pairs via ISplitScreen: " + why);
            return null;
        }
        return new HgSoScSplitScreen(b);
    }

    @Nullable
    private static String descriptorOf(IBinder b) {
        try {
            return b.getInterfaceDescriptor();
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "ISoScSplitScreen descriptor unreadable: " + e);
            return null;
        }
    }

    private static synchronized void logUnusableOnce(String msg) {
        if (sLoggedUnusable) return;
        sLoggedUnusable = true;
        Log.w(TAG, msg);
    }

    /**
     * Whether split pairs should go through {@code sosc} (HgSplitRoutePolicy), logged with the
     * reason so a phone log shows which path each split took.
     */
    public static boolean use(@Nullable HgSoScSplitScreen sosc, ContentResolver resolver,
            String call) {
        int enable = HgSplitRoutePolicy.SOSC_ENABLE_DEFAULT;
        try {
            enable = Settings.System.getInt(resolver, SETTING_SOSC_ENABLE,
                    HgSplitRoutePolicy.SOSC_ENABLE_DEFAULT);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read " + SETTING_SOSC_ENABLE + ", assuming default: " + e);
        }
        boolean use = HgSplitRoutePolicy.useSoSc(sosc != null, enable);
        Log.i(TAG, call + " via " + (use ? "ISoScSplitScreen" : "ISplitScreen")
                + " (sosc binder " + (sosc != null) + ", " + SETTING_SOSC_ENABLE + "=" + enable
                + ")");
        return use;
    }

    /** Whether HyperOS's split screen is switched on (wmshell.sosc_enable; no log). */
    public static boolean enabled(ContentResolver resolver) {
        try {
            return HgSplitRoutePolicy.useSoSc(true, Settings.System.getInt(resolver,
                    SETTING_SOSC_ENABLE, HgSplitRoutePolicy.SOSC_ENABLE_DEFAULT));
        } catch (RuntimeException e) {
            return HgSplitRoutePolicy.useSoSc(true, HgSplitRoutePolicy.SOSC_ENABLE_DEFAULT);
        }
    }

    /**
     * SoSc single mode: {@code taskId} in the half at {@code position} (0 top/left, 1
     * bottom/right), home in the other. Device proxy: token, writeInt(taskId), writeInt(position),
     * writeTypedObject(options, 0); oneway. Shell aborts (logcat only) if the task is already in
     * a visible split half or SoSc is off.
     */
    public void startTask(int taskId, int position, @Nullable Bundle options)
            throws RemoteException {
        Parcel data = Parcel.obtain(mRemote);
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId);
            data.writeInt(position);
            data.writeTypedObject(options, 0);
            transact(HgShellCalls.Call.SOSC_START_TASK, data);
        } finally {
            data.recycle();
        }
    }

    public void startTasks(int taskId1, @Nullable Bundle options1, int taskId2,
            @Nullable Bundle options2, int splitPosition, int snapPosition,
            @Nullable RemoteTransition remoteTransition) throws RemoteException {
        Parcel data = Parcel.obtain(mRemote);
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(taskId1);
            data.writeTypedObject(options1, 0);
            data.writeInt(taskId2);
            data.writeTypedObject(options2, 0);
            data.writeInt(splitPosition);
            data.writeInt(snapPosition);
            data.writeTypedObject(remoteTransition, 0);
            transact(HgShellCalls.Call.SOSC_START_TASKS, data);
        } finally {
            data.recycle();
        }
    }

    public void startIntentAndTask(@Nullable PendingIntent pendingIntent, int userId1,
            @Nullable Bundle options1, int taskId, @Nullable Bundle options2, int splitPosition,
            int snapPosition, @Nullable RemoteTransition remoteTransition)
            throws RemoteException {
        Parcel data = Parcel.obtain(mRemote);
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeTypedObject(pendingIntent, 0);
            data.writeInt(userId1);
            data.writeTypedObject(options1, 0);
            data.writeInt(taskId);
            data.writeTypedObject(options2, 0);
            data.writeInt(splitPosition);
            data.writeInt(snapPosition);
            data.writeTypedObject(remoteTransition, 0);
            transact(HgShellCalls.Call.SOSC_START_INTENT_AND_TASK, data);
        } finally {
            data.recycle();
        }
    }

    public void startShortcutAndTask(@Nullable ShortcutInfo shortcutInfo,
            @Nullable Bundle options1, int taskId, @Nullable Bundle options2, int splitPosition,
            int snapPosition, @Nullable RemoteTransition remoteTransition)
            throws RemoteException {
        Parcel data = Parcel.obtain(mRemote);
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeTypedObject(shortcutInfo, 0);
            data.writeTypedObject(options1, 0);
            data.writeInt(taskId);
            data.writeTypedObject(options2, 0);
            data.writeInt(splitPosition);
            data.writeInt(snapPosition);
            data.writeTypedObject(remoteTransition, 0);
            transact(HgShellCalls.Call.SOSC_START_SHORTCUT_AND_TASK, data);
        } finally {
            data.recycle();
        }
    }

    public void startIntents(@Nullable PendingIntent pendingIntent1, int userId1,
            @Nullable ShortcutInfo shortcutInfo1, @Nullable Bundle options1,
            @Nullable PendingIntent pendingIntent2, int userId2,
            @Nullable ShortcutInfo shortcutInfo2, @Nullable Bundle options2, int splitPosition,
            int snapPosition, @Nullable RemoteTransition remoteTransition)
            throws RemoteException {
        Parcel data = Parcel.obtain(mRemote);
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeTypedObject(pendingIntent1, 0);
            data.writeInt(userId1);
            data.writeTypedObject(shortcutInfo1, 0);
            data.writeTypedObject(options1, 0);
            data.writeTypedObject(pendingIntent2, 0);
            data.writeInt(userId2);
            data.writeTypedObject(shortcutInfo2, 0);
            data.writeTypedObject(options2, 0);
            data.writeInt(splitPosition);
            data.writeInt(snapPosition);
            data.writeTypedObject(remoteTransition, 0);
            transact(HgShellCalls.Call.SOSC_START_INTENTS, data);
        } finally {
            data.recycle();
        }
    }

    /** The code the phone's Shell confirmed for {@code call} (fromBundle checked them all). */
    private void transact(HgShellCalls.Call call, Parcel data) throws RemoteException {
        int code = HgShellInterfaces.get().code(call);
        boolean ok = mRemote.transact(code, data, null, IBinder.FLAG_ONEWAY);
        Log.i(TAG, "ISoScSplitScreen." + call.method + " sent (code " + code + ", transact=" + ok
                + ")");
    }
}
