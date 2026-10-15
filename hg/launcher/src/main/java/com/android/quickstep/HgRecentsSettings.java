// HyperGestures: off switches for the MIUI-style recents features. Each is a Settings.Global key
// that is on unless set to "0", so a problem found on the phone can be turned off without a
// rebuild:
//   adb shell settings put global hypergestures_card_actions 0    (off)
//   adb shell settings delete global hypergestures_card_actions   (back on)
package com.android.quickstep;

import android.content.Context;
import android.provider.Settings;

import com.hypergestures.notif.NotifKeys;

public final class HgRecentsSettings {
    private HgRecentsSettings() {}

    /** Blurred wallpaper behind recents (HgRecentsBlur). Read each time recents opens. */
    public static final String BLUR = "hypergestures_recents_blur";
    /**
     * Recents follows HyperOS's blur terms too, and its frosted elements turn solid when it
     * cannot blur (HgBlurSupport). Read when RecentsActivity is created.
     */
    public static final String BLUR_FALLBACK = "hypergestures_blur_fallback";
    /** Swipe a card down to lock or unlock it (HgTaskLockSwipeController). Read per touch. */
    public static final String CARD_LOCK_SWIPE = "hypergestures_card_lock_swipe";
    /** Hold a card for the round action buttons (HgTaskActionsView). */
    public static final String CARD_ACTIONS = "hypergestures_card_actions";
    /** The app's icon and name above its card, no chip menu (HgTaskHeader). */
    public static final String HEADER = "hypergestures_recents_header";
    /** Rounded-square icons instead of HyperOS's square system mask (HgIconShape). adb only. */
    public static final String ICON_SHAPE = "hypergestures_icon_shape";
    /** Drag an app's window to a top corner while swiping up: split screen / floating window. */
    public static final String CORNER_DRAG = "hypergestures_corner_drag";
    /** Swipe up from HyperOS's split screen as a split card (HgSoScState). Read at SystemUI bind. */
    public static final String SPLIT_GESTURE = "hypergestures_split_gesture";
    /** Floating windows shrink to mini windows over recents and home (HgFreeformState). */
    public static final String FLOATING_SHRINK = "hypergestures_floating_shrink";
    /** A dismissed card's app is killed too, as HyperOS does (HgProcessKill.dismissed). */
    public static final String DISMISS_KILLS = "hypergestures_dismiss_kills";
    /** Clear all kills the cleared apps and toasts the memory freed, as HyperOS (HgClearAll). */
    public static final String CLEAR_KILLS = "hypergestures_clear_kills";
    /**
     * Stock HyperOS's back indicator, a dark bulge with a "<" (interaction/HgMiuiBackArrow), and
     * its commit rule: back only past 180 px or on a release faster than 2 px/ms
     * (HgBackPolicy.commitsOnRelease). "0" restores AOSP's arrow and AOSP's rule (back from the
     * drag slop on). Read on every back touch-down.
     */
    public static final String BACK_STYLE = "hypergestures_back_style";
    /**
     * An instant "reboot to switch gestures to MiuiHome" notice from the app when HOME becomes
     * MiuiHome (HgHomeSwitch); the module's watchdog notice comes either way.
     */
    public static final String HOME_NOTICE = "hypergestures_home_notice";
    /**
     * The recents layout (HgRecentsLayout): not an on/off switch but an override of MIUI's
     * task_stack_view_layout_style. "1" is today's horizontal layout (the stack's off switch),
     * "2" the stack, "0" vertical; unset (or invalid) is filled once with HyperOS's current
     * layout (HgSettingsMigration) and follows MIUI until then. Read as recents opens.
     */
    public static final String RECENTS_LAYOUT = "hypergestures_recents_layout";
    /**
     * The last step of HgSettingsMigration's one-time deletes of the keys of the switches the
     * page dropped (adb-only off switches now): "1" header, card actions, split gesture,
     * floating shrink; "2" rounded icons; "3" the removed back vibration key.
     */
    public static final String SETTINGS_MIGRATION = HgSettingsMigration.MARKER;
    /**
     * The gesture line drawn invisible (HgHideGestureLine). Unlike the switches above it is OFF
     * unless "1" (HgHideGestureLine.isOn): it changes the stock look, so it is opt-in. The
     * settings page applies it at once; service start, BOOT_COMPLETED and each opening of the
     * page check it, so it works whatever HOME is. SystemUI draws the change from its next start
     * (a reboot).
     */
    public static final String HIDE_GESTURE_LINE = "hypergestures_hide_gesture_line";
    /**
     * The notification icon fix (ported from HyperNotificationFix): real app icons in
     * notifications instead of white squares. OFF unless "1" (NotifKeys.isOn): it only works
     * through LSPosed, so it is opt-in. The hooks are not in this APK: they ship as their own
     * small APK, com.hypergestures.notif (the LSPosed module, scoped to SystemUI and the AOD), and
     * share only this key and the "hooked" status key with us (NotifKeys, compiled into both).
     * It works whatever HOME is. The hooks follow the setting in SystemUI's and the AOD's
     * process with a ContentObserver, so a change applies to the next notification icon built,
     * without a restart.
     */
    public static final String NOTIFICATION_ICONS = NotifKeys.KEY;
    /**
     * We tell the module's health checks that SystemUI bound us and that our gesture monitor is
     * registered (HgHealthFacts), so a dump format a firmware changed can't roll it back. Off
     * ("0"): our next bind or monitor change clears both values and writes none, and the module,
     * which reads the switch once a boot (hg_facts_on), ignores both values while it is 0, so its
     * checks go by the dumps alone. Set it, then reboot.
     */
    public static final String HEALTH_FACTS = "hypergestures_health_facts";
    /**
     * "Fix App info back" (HgAppInfoTrampoline): any app's standard App info request opens
     * Security's App info the MiuiHome way, so it stays when App permissions opens. Off ("0"):
     * the trampoline component is disabled and Android resolves App info exactly as stock. The
     * settings page applies a toggle at once; an adb change applies at the next gesture service
     * start, boot or opening of the settings page (or the next App info request, forwarded).
     */
    public static final String APP_INFO_FIX = "hypergestures_app_info_fix";

    /** "0" turns a feature off; anything else, or unset, leaves it on. */
    public static boolean isOn(String value) {
        return !"0".equals(value);
    }

    public static boolean isOn(Context context, String key) {
        return isOn(Settings.Global.getString(context.getContentResolver(), key));
    }
}
