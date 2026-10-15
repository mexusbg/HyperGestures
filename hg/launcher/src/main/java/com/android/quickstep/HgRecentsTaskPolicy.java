// HyperGestures: which recent tasks get an overview card. Extracted so the rules are testable.
//
// HyperOS Shell's getRecentTasks also returns the home task (task 735, Nova, type=home on
// 2026-10-05), which AOSP's Shell leaves out: a home task is not a recents card. Tasks restored
// from disk after a reboot (type undefined, no activities, no base activity, e.g. Messenger task
// 725) are real recents and keep their cards, as in AOSP; they are drawn from their saved snapshot,
// and their label and icon come from HgTaskActivityInfo when the stored component does not resolve.
// A task whose root is an app's App info screen gets no card either (owner, 2026-10-10: "we
// shouldn't keep app info in recents"): the header tap and the hold button open it, and it stayed
// as a card. App info reached inside the Settings app has Settings' own root and stays.
package com.android.quickstep;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;

public final class HgRecentsTaskPolicy {
    private HgRecentsTaskPolicy() {}

    /**
     * @param activityType {@code TaskInfo.topActivityType}
     * @param homeCategory {@code baseIntent} has {@code CATEGORY_HOME} (Shell can leave the type
     *     of Nova's task unset)
     */
    public static boolean showInOverview(int activityType, boolean homeCategory) {
        return activityType != ACTIVITY_TYPE_HOME && activityType != ACTIVITY_TYPE_RECENTS
                && !homeCategory;
    }

    /** AOSP's and HyperOS's App info entry points (base intent action or component). */
    static final String ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS";
    static final String ACTION_MIUI_APP_DETAILS =
            "miui.intent.action.APP_MANAGER_APPLICATION_DETAIL";
    static final String AOSP_APP_DETAILS = "com.android.settings/.applications.InstalledAppDetails";
    static final String MIUI_APP_DETAILS =
            "com.miui.securitycenter/com.miui.appmanager.ApplicationsDetailsActivity";

    /**
     * @param baseAction {@code baseIntent.getAction()}, may be null
     * @param baseComponent {@code baseIntent.getComponent().flattenToShortString()}, may be null
     */
    public static boolean showInOverview(int activityType, boolean homeCategory,
            String baseAction, String baseComponent) {
        return showInOverview(activityType, homeCategory)
                && !isAppDetails(baseAction, baseComponent);
    }

    /** A task opened straight onto an app's App info screen. */
    static boolean isAppDetails(String baseAction, String baseComponent) {
        return ACTION_APP_DETAILS.equals(baseAction) || ACTION_MIUI_APP_DETAILS.equals(baseAction)
                || AOSP_APP_DETAILS.equals(baseComponent) || MIUI_APP_DETAILS.equals(baseComponent);
    }
}
