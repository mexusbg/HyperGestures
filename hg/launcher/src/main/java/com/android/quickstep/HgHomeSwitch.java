// HyperGestures: an instant notice when HOME becomes MiuiHome (owner, 0.3.9.28: "No notification
// or toast when switching to system launcher"). The module's watchdog notices a HOME change from
// roles.xml only every 120 s of awake time (hg_home_watch), so after choosing MiuiHome in Settings
// nothing was shown for minutes, or never with the screen off. While our process runs (SystemUI
// binds TouchInteractionService), OverviewComponentObserver already hears
// ACTION_PREFERRED_ACTIVITY_CHANGED and hands us the new default home package; we post the
// module's own text with its tag, so the module's later notice just replaces ours. Any other
// launcher change needs nothing: fallback recents works with any launcher.
package com.android.quickstep;

import android.content.Context;
import android.util.Log;

import com.hypergestures.HgNotifyReceiver;

public final class HgHomeSwitch {
    /** MiuiHome: as HOME it drives the gestures, ours only take over again after a reboot. */
    public static final String MIUI_HOME = "com.miui.home";
    /** The module's text (hg_home_react) and tag (hg_notify_app), verbatim. */
    static final String TEXT = "HyperGestures: reboot to switch gestures to MiuiHome";
    static final String NOTICE_TAG = "hypergestures";

    public static final int NONE = 0;
    public static final int NOTIFY = 1;
    public static final int CANCEL = 2;

    private static final String TAG = "HgHomeSwitch";
    private static final HgHomeSwitch INSTANCE = new HgHomeSwitch();

    private String mHome;

    /**
     * One observation of the default home package (null: none resolved, e.g. a transient state
     * while the role moves, or the setup wizard). The first non-null one only records; a change
     * to MiuiHome from another package is {@link #NOTIFY}, a change from MiuiHome to another
     * package {@link #CANCEL} (our notice is stale), anything else {@link #NONE}.
     */
    public int observe(String home) {
        if (home == null) return NONE;
        String previous = mHome;
        mHome = home;
        if (previous == null || previous.equals(home)) return NONE;
        if (MIUI_HOME.equals(home)) return NOTIFY;
        if (MIUI_HOME.equals(previous)) return CANCEL;
        return NONE;
    }

    /**
     * From OverviewComponentObserver.updateOverviewTargets (main thread) with the resolved default
     * home package. Off with {@link HgRecentsSettings#HOME_NOTICE}. Never throws. The
     * hide-gesture-line overlay is not touched: it follows its setting with any HOME (owner,
     * 0.3.9.34), see HgHideGestureLine.
     */
    public static void onDefaultHome(Context context, String home) {
        try {
            // AOSP's getHomeActivities, mapped as the rest of the app sees HOME (HgHome): the
            // chooser and a setup wizard are no default home.
            String mapped = HgHome.map(home, null);
            int action;
            synchronized (INSTANCE) {
                action = INSTANCE.observe(mapped);
            }
            if (action == NONE || !HgRecentsSettings.isOn(context, HgRecentsSettings.HOME_NOTICE)) {
                return;
            }
            if (action == NOTIFY) {
                Log.i(TAG, "HOME is now " + mapped + ": reboot to switch gestures");
                HgNotifyReceiver.post(context, HgNotifyReceiver.DEFAULT_TITLE, TEXT, NOTICE_TAG);
            } else {
                HgNotifyReceiver.cancel(context, NOTICE_TAG, TEXT); // only if still ours
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "HOME change not handled", e);
        }
    }
}
