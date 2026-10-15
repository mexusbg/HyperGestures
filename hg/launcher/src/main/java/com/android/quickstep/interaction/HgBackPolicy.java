// HyperGestures: the rules of our edge-back gesture, extracted so they are testable. HyperOS
// SystemUI has no EdgeBackGestureHandler (MiuiHome owned back), so the recents provider does it.
package com.android.quickstep.interaction;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DREAMING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING;

import android.view.View;

public final class HgBackPolicy {
    /**
     * Settings.Global key (written by the owner over adb) picking how back is sent; unset = key.
     * One constant for the consumer and info.txt's key list.
     */
    public static final String BACK_PATH_SETTING = "hypergestures_back";

    private HgBackPolicy() {}

    /** Whether a back gesture may start at all: gestural nav, and none of AOSP's blocking states. */
    public static boolean isAllowed(boolean fullyGestural, long sysuiFlags) {
        if (!fullyGestural) return false;
        if ((sysuiFlags & (SYSUI_STATE_DEVICE_DREAMING | SYSUI_STATE_SCREEN_PINNING)) != 0) return false;
        boolean keyguard = (sysuiFlags & SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING) != 0;
        boolean bouncer = (sysuiFlags & SYSUI_STATE_BOUNCER_SHOWING) != 0;
        return !keyguard || bouncer;
    }

    /**
     * A touch-down that starts back: within leftEdge of the left edge or rightEdge of the right
     * edge, above the bottom area.
     */
    public static boolean isBackEdgeDown(float x, float y, int displayWidth, int displayHeight,
            int leftEdge, int rightEdge, int bottomGestureHeight) {
        if (y >= displayHeight - bottomGestureHeight) return false;
        return x <= leftEdge || x >= displayWidth - rightEdge;
    }

    /** AOSP's back sensitivity per side (Settings.Secure float, default 1.0): edge x scale. */
    public static final String INSET_SCALE_LEFT = "back_gesture_inset_scale_left";
    public static final String INSET_SCALE_RIGHT = "back_gesture_inset_scale_right";
    /**
     * The ends of AOSP Settings' back-sensitivity slider (0.60, 0.75, 1.00, 1.33, 1.66). SystemUI
     * itself applies no limit, but these keys are written over adb and by our settings page, and a
     * stray 0 would turn back off while a 10 would steal a fifth of the screen from apps.
     */
    public static final float MIN_INSET_SCALE = 0.6f;
    public static final float MAX_INSET_SCALE = 1.66f;

    /** The scale actually applied: the setting clamped to the slider, 1 if not a number. */
    public static float insetScale(float setting) {
        if (Float.isNaN(setting) || Float.isInfinite(setting)) return 1f;
        return Math.max(MIN_INSET_SCALE, Math.min(MAX_INSET_SCALE, setting));
    }

    /** The settings page's slider stops: AOSP Settings' (GestureNavigationSettingsFragment). */
    public static final float[] SENSITIVITY_STEPS = {0.6f, 0.75f, 1f, 1.33f, 1.66f};
    /** The stop of the default scale, 1.0. */
    public static final int DEFAULT_SENSITIVITY_STEP = 2;

    /** The slider stop nearest the scale actually applied for {@code setting} (insetScale). */
    public static int sensitivityStep(float setting) {
        float scale = insetScale(setting);
        int best = 0;
        for (int i = 1; i < SENSITIVITY_STEPS.length; i++) {
            if (Math.abs(SENSITIVITY_STEPS[i] - scale)
                    < Math.abs(SENSITIVITY_STEPS[best] - scale)) {
                best = i;
            }
        }
        return best;
    }

    /**
     * One side's back edge width in px, as SystemUI's GestureNavigationSettingsObserver computes
     * it: (int) (system back inset x scale).
     */
    public static int scaledEdgeWidth(int baseWidthPx, float scaleSetting) {
        return (int) (baseWidthPx * insetScale(scaleSetting));
    }

    /**
     * SystemUI EdgeBackGestureHandler.isWithinTouchRegion: a touch-down inside the display's
     * system-gesture exclusion region never starts back, so the app keeps it. The region is used
     * as WindowManager delivers it: WM already caps each window's zone per side edge
     * (DeviceConfig window_manager/system_gesture_exclusion_limit_dp, at least 200dp, kept from
     * the bottom of the zone up) and leaves sticky-immersive windows uncapped; SystemUI applies
     * no limit of its own. (SystemUI's exception while the nav bar is shown transiently has no
     * sysui state flag here and is not modelled.)
     */
    public static boolean startsBack(boolean backEdgeDown, boolean inExclusionRegion) {
        return backEdgeDown && !inExclusionRegion;
    }

    /** A slower release must have travelled past HgBackArrowMath.ARM_TRAVEL_PX (180 px). */
    public static final float COMMIT_VELOCITY_PX_PER_MS = 2f;

    /**
     * MIUI back arrow (hypergestures_back_style on): whether a released back swipe (ACTION_UP; a
     * CANCEL never goes back) sends back, as MiuiHome's GesturesBackTouchProcessor
     * (isTriggerBack = d > 180 || speed > 2 px/ms). dx and vxPxPerMs are the release's sideways
     * offset from the touch-down and its velocity (raw px, px/ms, screen x); both count only
     * inward, away from the edge the swipe started on. triggerBack is the panel's AOSP state
     * (kept direction, not more vertical than 2x sideways); while false the chevron is hidden,
     * so a release then never goes back. armed is whether the chevron was armed at the last
     * MOVE (HgMiuiBackArrow.isArmed): the release's own x may land just under 180 px after the
     * user saw the chevron and felt the ready tick, and that release still goes back.
     */
    public static boolean commitsOnRelease(boolean armed, boolean leftEdge, float dx,
            float vxPxPerMs, boolean triggerBack) {
        if (armed) return true;
        if (!triggerBack) return false;
        float travel = HgBackArrowMath.inwardTravel(leftEdge, dx);
        float velocity = HgBackArrowMath.inwardTravel(leftEdge, vxPxPerMs);
        return travel > HgBackArrowMath.ARM_TRAVEL_PX || velocity > COMMIT_VELOCITY_PX_PER_MS;
    }

    /** How long after a recents animation is cleaned up back stays blocked (Shell still finishing). */
    public static final long RECENTS_COOLDOWN_MS = 400;

    /**
     * Shell's cross-task back animation crashes SystemUI if back arrives mid recents animation, or
     * in the window after Launcher3 cleaned it up while Shell is still finishing the transition.
     * recentsCleanedUpAtMs is 0 if no recents animation has been cleaned up yet.
     */
    public static boolean canCommit(boolean recentsAnimationRunning, long nowMs,
            long recentsCleanedUpAtMs) {
        if (recentsAnimationRunning) return false;
        return !(recentsCleanedUpAtMs > 0 && nowMs - recentsCleanedUpAtMs < RECENTS_COOLDOWN_MS);
    }

    /**
     * The visibility of the edge-back overlay's root: VISIBLE while it is armed (the handler
     * accepted a back swipe) and any child (the arrow panel) is VISIBLE, else INVISIBLE. An
     * INVISIBLE root makes ViewRootImpl relayout the full-screen window as invisible, so
     * WindowManager drops its surface and buffers and SurfaceFlinger has no layer to compose while
     * no back gesture is in progress. The panel turns VISIBLE on every edge touch-down, taps
     * included; waiting for the armed swipe keeps those from mapping the window at all.
     */
    public static int overlayVisibility(boolean armed, int... childVisibilities) {
        if (!armed) return View.INVISIBLE;
        for (int v : childVisibilities) {
            if (v == View.VISIBLE) return View.VISIBLE;
        }
        return View.INVISIBLE;
    }

    /**
     * How back is sent through SystemUI; chosen by Settings.Global "hypergestures_back".
     * KEY: ISystemUiProxy.onKeyEvent(KEYCODE_BACK), an instant back without Shell's animation.
     * EVENT: onBackEvent(KeyEvent DOWN/UP), which drives Shell's predictive-back commit animation.
     * On HyperOS 4 that crashes SystemUI on every cross-activity and cross-task back: Xiaomi's
     * build compiled BackAnimationBackground.resetStatusBarCustomization() to `throw null`
     * (nothing we can register fixes it; stock MiuiHome runs predictive back through
     * startBackNavigation itself and never reaches it). Debug opt-in only.
     * NULL_EVENT: onBackEvent(null), SystemUI injects KEYCODE_BACK itself.
     */
    public enum BackPath { EVENT, NULL_EVENT, KEY }

    /** "event" and "null" (exact, lowercase) pick those paths; anything else is the default KEY. */
    public static BackPath backPath(String setting) {
        if ("event".equals(setting)) return BackPath.EVENT;
        if ("null".equals(setting)) return BackPath.NULL_EVENT;
        return BackPath.KEY;
    }
}
