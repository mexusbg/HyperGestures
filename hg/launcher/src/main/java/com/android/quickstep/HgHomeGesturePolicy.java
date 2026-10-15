// HyperGestures: how a swipe up over the HOME app itself behaves, extracted so it is testable.
// In fallback recents mode the third-party home (Nova) is just the running task, so AOSP's
// FallbackSwipeHandler ends a released swipe up on it with the HOME target: it plays the
// app-to-home window animation on the home window and sends the home intent to the home app that
// is already in front (a home-to-home transition, and Nova runs its own "home pressed on home"
// action), which the owner sees as a flash (runbook B4). During the drag AOSP also scales the
// home window down and fades it, with recents attached behind it from the first frame; the owner
// wants the home screen not to move at all unless the swipe becomes a hold or a quick switch.
package com.android.quickstep;

import com.android.quickstep.GestureState.GestureEndTarget;

public final class HgHomeGesturePolicy {
    private HgHomeGesturePolicy() {}

    /** Home window alpha while recents is hidden behind it: untouched. */
    public static final float HOME_ALPHA_SHOWN = 1f;
    /** Home window alpha once recents is attached (hold, quick switch): faded out. */
    public static final float HOME_ALPHA_HIDDEN = 0f;

    /**
     * The end target to use where AbsSwipeUpHandler asks for its "go home" target
     * (getHomeTarget: a released swipe up that is neither a pause nor a quick switch).
     * Over the home app, HOME becomes LAST_TASK: the window springs back and the recents
     * animation is finished back to the running task (the home app), so nothing is launched
     * and Shell's recents transition still ends. Any other target (RECENTS for a trackpad,
     * REJECT_HOME on a display without home) and any other running task keep AOSP's choice.
     */
    public static GestureEndTarget homeTarget(GestureEndTarget aospHomeTarget,
            boolean runningOverHome) {
        if (runningOverHome && aospHomeTarget == GestureEndTarget.HOME) {
            return GestureEndTarget.LAST_TASK;
        }
        return aospHomeTarget;
    }

    /**
     * Whether a released swipe up over the home app sends a back key once it has sprung back:
     * exactly when AOSP would have gone HOME ({@link #homeTarget} turned that into LAST_TASK), so
     * the home app closes an open folder or drawer as on a back press, without the home-to-home
     * transition. A swipe that falls short or is pulled back down (AOSP's own LAST_TASK), a hold
     * (RECENTS) and a quick switch (NEW_TASK) never send it; nor does any other running task.
     *
     * @param aospHomeTarget AOSP's getHomeTarget() for the released swipe
     * @param enabled Settings.Global hypergestures_home_back is not "0"
     */
    public static boolean backOnRelease(GestureEndTarget aospHomeTarget, boolean runningOverHome,
            boolean enabled) {
        return enabled && runningOverHome && aospHomeTarget == GestureEndTarget.HOME;
    }

    /** "0" turns the back key on a swipe up over the home app off; anything else (unset) is on. */
    public static boolean homeBackEnabled(String setting) {
        return !"0".equals(setting);
    }

    /** ms after Shell's recents animation is cleaned up before the back key is sent. */
    public static final long HOME_BACK_SETTLE_MS = 80;
    /** ms after the release the back key is given up if the recents animation never ends. */
    public static final long HOME_BACK_TIMEOUT_MS = 1500;

    /** What the pending back key after a release over the home app does now. */
    public enum HomeBackStep { WAIT, SEND, GIVE_UP }

    /**
     * The back key goes to the focused window, so it waits until this gesture's recents
     * animation has been cleaned up (focus is back on the home app) and HOME_BACK_SETTLE_MS have
     * passed; a cleanup from before the release is an earlier gesture's. Gives up after
     * HOME_BACK_TIMEOUT_MS, or if a new recents animation started (the user swiped again).
     *
     * @param recentsCleanedUpAtMs uptime of the last cleanup, 0 if none
     */
    public static HomeBackStep homeBackStep(long nowMs, long releasedAtMs,
            boolean recentsAnimationRunning, long recentsCleanedUpAtMs) {
        if (nowMs - releasedAtMs > HOME_BACK_TIMEOUT_MS) return HomeBackStep.GIVE_UP;
        boolean cleanedUpSinceRelease = recentsCleanedUpAtMs >= releasedAtMs;
        if (recentsAnimationRunning) {
            return cleanedUpSinceRelease ? HomeBackStep.GIVE_UP : HomeBackStep.WAIT;
        }
        if (!cleanedUpSinceRelease) return HomeBackStep.WAIT;
        return nowMs - recentsCleanedUpAtMs >= HOME_BACK_SETTLE_MS
                ? HomeBackStep.SEND : HomeBackStep.WAIT;
    }

    /**
     * Whether recents (the task cards) is shown alongside the running window, where
     * AbsSwipeUpHandler.maybeUpdateRecentsAttachedState decides it.
     *
     * @param aospAttached AOSP's decision. Over the home app it is true from the first frame,
     *     because the home window "is going away" (isNotInRecents), and for every end target
     *     but HOME and REJECT_HOME.
     * @param runningOverHome the running task is the home app
     * @param endTarget the gesture's end target, null while the finger is down
     * @param attachedByGesture AOSP's rule for a window that stays in recents: the motion has
     *     paused (hold), the swipe is likely a quick switch, or a continued gesture has already
     *     scrolled to another task
     * @return over the home app: attached only once the swipe is a hold or a quick switch
     *     (while the finger is down), or when it ends in RECENTS or NEW_TASK; hidden for a swipe
     *     that ends back on the home app (LAST_TASK) or anywhere else. Every other running task
     *     keeps AOSP's decision.
     */
    public static boolean recentsAttached(boolean aospAttached, boolean runningOverHome,
            GestureEndTarget endTarget, boolean attachedByGesture) {
        if (!runningOverHome) {
            return aospAttached;
        }
        if (endTarget == null) {
            return attachedByGesture;
        }
        return endTarget == GestureEndTarget.RECENTS || endTarget == GestureEndTarget.NEW_TASK;
    }

    /**
     * The home window's alpha during a swipe over the home app (its scale and position are never
     * changed): untouched while recents is hidden, faded out once recents is attached, so recents
     * shows on the whole screen instead of around a shrunken home window.
     */
    public static float homeWindowAlpha(boolean recentsAttached) {
        return recentsAttached ? HOME_ALPHA_HIDDEN : HOME_ALPHA_SHOWN;
    }

    /**
     * The duration of the end animation of a swipe over the home app. When it springs back to
     * the home app (LAST_TASK) after recents was shown (a hold, then back down), the home window
     * fades back in over {@code fadeDurationMs}; the end animation is stretched to at least that,
     * so the recents animation is not finished onto a half-faded home window. When recents was
     * never shown the home window was never moved, scaled or faded (identity transform, alpha 1),
     * so the spring back animates nothing visible: it ends at once (0 ms), and Shell's recents
     * transition is cleaned up right away. On the phone (0.3.9.36) AOSP's spring back took about
     * 760 ms there, and the back key (HgHomeBack), which waits for that cleanup, came about
     * 850 ms after the release. Anything else keeps AOSP's duration.
     */
    public static long endAnimationDuration(long aospDurationMs, boolean runningOverHome,
            GestureEndTarget endTarget, float homeAlpha, long fadeDurationMs) {
        if (runningOverHome && endTarget == GestureEndTarget.LAST_TASK) {
            return homeAlpha < HOME_ALPHA_SHOWN ? Math.max(aospDurationMs, fadeDurationMs) : 0;
        }
        return aospDurationMs;
    }

    /**
     * The hint AbsSwipeUpHandler.updateSysUiFlags gives Shell through setWillFinishToHome (in
     * fallback mode "home" is the recents activity). Shell's recents transition reads it when it
     * is finished: a finish with toHome=false and the hint false returns to the paused task.
     * Launching RecentsActivity over a third-party home, TaskAnimationManager finishes a RECENTS
     * end exactly that way (toHome=false) and relies on AOSP's hint being true by then
     * ({@code swipeUpThresholdPassed && getHomeTarget() == HOME}). Over the home app
     * getHomeTarget is LAST_TASK ({@link #homeTarget}), so the hint stayed false and a hold
     * that ended in RECENTS was finished back to the home app, which came back in front of
     * recents (phone test 2026-10-01, runbook B4).
     *
     * @param aospWillFinishToHome AOSP's hint (always false over the home app)
     * @param runningOverHome the running task is the home app
     * @param endTarget the gesture's end target, null while the finger is down
     * @return over the home app: true once the end target is RECENTS, otherwise AOSP's hint
     *     (a release back on the home app, LAST_TASK, must stay false so its finish returns to
     *     it). Every other running task keeps AOSP's hint.
     */
    public static boolean willFinishToHome(boolean aospWillFinishToHome, boolean runningOverHome,
            GestureEndTarget endTarget) {
        if (runningOverHome && endTarget == GestureEndTarget.RECENTS) {
            return true;
        }
        return aospWillFinishToHome;
    }
}
