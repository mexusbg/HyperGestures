// HyperGestures: while swiping up from an app, drag the window to a top corner for split screen
// (top-left) or a floating window (top-right), as in HyperOS (design ruling 6). Not over the home
// app (there is no app window to drag). Off: Settings.Global hypergestures_corner_drag=0.
//
// FallbackSwipeHandler feeds the finger's position (display px, from OtherActivityInputConsumer)
// to one HgCornerDrag per gesture. As in MiuiHome (HgCornerGeometry), each corner's frosted
// circle fades in and grows as the finger nears that corner; within reach and slow enough the
// finger holds over it (a haptic tick, on entry only, as MiuiHome's performEnterRecent) and the
// target morphs on a spring: the floating corner's circle into a glowing frame around the card,
// which shrinks into it like a mini window; the split corner's into a frosted panel over the top
// half, the card shrinking into its top-left corner. The card's shrink is the window's
// (TaskViewSimulator.hgCornerFloating / hgCornerSplit, set by the swipe handler each time it
// applies the window). Releasing while held ends the gesture in recents (FallbackSwipeHandler's
// hgAdjustEndTarget) and leaves a pending action for the running task; when the gesture animation
// has ended in recents, RecentsView.onGestureAnimationEnd runs it with the same card actions as the
// hold buttons (HgTaskShortcuts SPLIT_SCREEN / FLOATING_WINDOW). A pending action is dropped if
// the gesture ends anywhere else, or after PENDING_TIMEOUT_MS.
//
// The split corner works as HyperOS's: the app goes into the top half and the second app is picked
// from the home screen below (HgSplitSingle, owner 2026-10-08). Without HyperOS's split screen it
// falls back to AOSP's selection among the other cards, so it is offered only when the app can
// split and either HyperOS's one-app split is available or recents has another card (owner,
// 2026-10-07: with only Facebook in recents the corner left an empty split selection).
package com.android.quickstep;

import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.launcher3.popup.SystemShortcut;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;

import java.util.List;

public final class HgCornerDrag {
    private static final String TAG = "HgCornerDrag";

    /** What a corner does. */
    public enum Target { NONE, SPLIT, FLOATING }

    static final long PENDING_TIMEOUT_MS = 4000;
    /** The finger's speed is measured over moves at least this far apart. */
    private static final long SPEED_WINDOW_MS = 8;

    /** [V] MiuiHome's hold spring (circle to frame, card 1 to 0.8): damping 0.95, response 0.3. */
    static final float HOLD_DAMPING = 0.95f;
    static final float HOLD_RESPONSE = 0.3f;

    /** Android's stiffness for a spring of {@code response} seconds (MIUI's response). */
    static float stiffness(float response) {
        double omega = 2 * Math.PI / response;
        return (float) (omega * omega);
    }

    /** {@code held} as a Target: -1 split, 1 floating (HgCornerGeometry.heldCorner). */
    static Target target(int held) {
        return held < 0 ? Target.SPLIT : held > 0 ? Target.FLOATING : Target.NONE;
    }

    // --- one gesture ---------------------------------------------------------------------------

    private final float mWidth;
    private final float mHeight;
    private final float mDensity;
    /** Reapplies the window (the card's shrink follows the hold springs). */
    private final Runnable mOnCardChanged;
    @Nullable private HgCornerTargetsView mTargets;
    private Target mArmed = Target.NONE;
    /** Whether split screen can be offered; null until recents has its cards. */
    @Nullable private Boolean mSplitOk;
    /** Whether a floating window can be offered; null until recents has its cards. */
    @Nullable private Boolean mFloatingOk;
    /** The card count the checks last ran on (re-run only when it changes). */
    private int mCheckedCount = -1;
    /** Hold progress per corner, 0..1: drives the morph and the card's shrink. */
    private final Spring mSplitHold;
    private final Spring mFloatingHold;
    private boolean mReleased;
    // The finger's speed (px per ms), for MiuiHome's hold rule.
    private float mSpeed;
    private float mLastX = Float.NaN;
    private float mLastY;
    private long mLastAt;
    private final float[] mSplitCard = new float[4];

    public HgCornerDrag(float screenWidth, float screenHeight, float density,
            Runnable onCardChanged) {
        mWidth = screenWidth;
        mHeight = screenHeight;
        mDensity = density;
        mOnCardChanged = onCardChanged;
        mSplitHold = new Spring(HOLD_DAMPING, stiffness(HOLD_RESPONSE), 1f / 500, this::onHold);
        mFloatingHold = new Spring(HOLD_DAMPING, stiffness(HOLD_RESPONSE), 1f / 500, this::onHold);
        HgCornerGeometry.splitCard(screenWidth, screenHeight, density, mSplitCard);
    }

    /** The finger moved to (x, y); {@code recentsView} draws the targets (null until ready). */
    public void onMove(float x, float y, @Nullable RecentsView<?, ?> recentsView) {
        if (mReleased) return;
        trackSpeed(x, y, SystemClock.uptimeMillis());
        float splitApproach = HgCornerGeometry.approach(x, y, 0, mHeight);
        float floatingApproach = HgCornerGeometry.approach(x, y, mWidth, mHeight);
        // Re-checked while the cards change: recents may still hold only the running app's
        // placeholder card while its list loads.
        int count = recentsView == null ? 0 : recentsView.getTaskViewCount();
        if (count > 0 && count != mCheckedCount) {
            mCheckedCount = count;
            boolean ok = splitAvailable(recentsView);
            if (mSplitOk == null && !ok) {
                Log.d(TAG, "split corner off: no second app to pair with (yet)");
            }
            mSplitOk = ok;
            if (mFloatingOk == null || !mFloatingOk) mFloatingOk = floatingAvailable(recentsView);
        }
        boolean splitOk = Boolean.TRUE.equals(mSplitOk);
        boolean floatingOk = Boolean.TRUE.equals(mFloatingOk);
        int held = mArmed == Target.SPLIT ? -1 : mArmed == Target.FLOATING ? 1 : 0;
        Target target = target(HgCornerGeometry.heldCorner(x, y, mWidth, mHeight, splitOk,
                floatingOk, held, mSpeed, mDensity));
        // The view only once an offered corner is approached: none for a swipe with no offer.
        if (mTargets == null && recentsView != null
                && ((splitOk && splitApproach > 0f) || (floatingOk && floatingApproach > 0f))) {
            mTargets = HgCornerTargetsView.attach(recentsView, mWidth, mHeight);
        }
        if (target != mArmed) {
            // MiuiHome ticks on entering a hold only (NavStubView$H 0x103; none on leaving).
            if (target != Target.NONE && recentsView != null) {
                recentsView.performHapticFeedback(
                        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
            }
            mArmed = target;
            mSplitHold.to(target == Target.SPLIT ? 1f : 0f);
            mFloatingHold.to(target == Target.FLOATING ? 1f : 0f);
        }
        if (mTargets != null) {
            mTargets.update(splitOk ? splitApproach : 0f, floatingOk ? floatingApproach : 0f);
            mTargets.setHold(mSplitHold.value(), mFloatingHold.value());
        }
    }

    /** Smoothed finger speed over moves at least SPEED_WINDOW_MS apart. */
    private void trackSpeed(float x, float y, long now) {
        if (Float.isNaN(mLastX)) {
            mLastX = x;
            mLastY = y;
            mLastAt = now;
            return;
        }
        long dt = now - mLastAt;
        if (dt < SPEED_WINDOW_MS) return;
        float speed = (float) Math.hypot(x - mLastX, y - mLastY) / dt;
        mSpeed = mSpeed == 0f ? speed : (mSpeed + speed) / 2f;
        mLastX = x;
        mLastY = y;
        mLastAt = now;
    }

    /** A hold spring moved: the window shrinks or grows back, the target morphs. */
    private void onHold() {
        mOnCardChanged.run();
        if (mTargets != null) mTargets.setHold(mSplitHold.value(), mFloatingHold.value());
    }

    /** Hold progress over the floating target, 0..1 (TaskViewSimulator.hgCornerFloating). */
    public float floatingProgress() {
        return mFloatingHold.value();
    }

    /** Hold progress over the split target, 0..1 (TaskViewSimulator.hgCornerSplit). */
    public float splitProgress() {
        return mSplitHold.value();
    }

    /** Where the card ends over the split target, screen px {left, top, right, bottom}. */
    public float[] splitCardTarget() {
        return mSplitCard;
    }

    /** The window was applied: the card's screen rect and corner radius, for the frame. */
    public void onCardMoved(float left, float top, float right, float bottom, float radius) {
        if (mTargets != null) mTargets.setCard(left, top, right, bottom, radius);
    }

    /** Whether the frame needs the card's rect (only while the floating target morphs). */
    public boolean wantsCard() {
        return mTargets != null && mFloatingHold.value() > 0f;
    }

    /** The app being swiped from can open in a floating window (HgTaskShortcuts' rule). */
    private static boolean floatingAvailable(RecentsView<?, ?> recentsView) {
        TaskView running = recentsView.getRunningTaskView();
        if (running == null || running.getTaskContainers().size() != 1) return false;
        String pkg = HgTaskLockStore.packageOf(running.getTaskContainers().get(0).getTask());
        return pkg != null && HgTaskMenuPolicy.showFloatingWindow(
                HgFloatingWindow.systemSupports(),
                HgFloatingWindow.appSupports(recentsView.getContext(), pkg),
                false /* grouped: a single card */);
    }

    /** The app being swiped from can split, and recents has another card to pair it with. */
    private static boolean splitAvailable(RecentsView<?, ?> recentsView) {
        TaskView running = recentsView.getRunningTaskView();
        if (running == null || running.getTaskContainers().size() != 1) return false;
        if (!HgSplitSingle.canSplit(running.getTaskContainers().get(0).getTask())) return false;
        return HgSplitSingle.available(recentsView.getContext())
                || HgTaskMenuPolicy.showSplitScreen(
                        HgTaskShortcuts.otherSplittableCards(recentsView, running));
    }

    /** The finger lifted: the armed corner (NONE if none). Hides the targets. */
    public Target onRelease() {
        Target armed = mArmed;
        dismiss();
        return armed;
    }

    /**
     * The gesture ended or was cancelled: hides the targets. The hold springs stop where they
     * are: the swipe handler takes the card's shrink from there (no jump) and returns it to 0
     * along its end animation.
     */
    public void dismiss() {
        mReleased = true;
        mArmed = Target.NONE;
        mSplitHold.cancel();
        mFloatingHold.cancel();
        if (mTargets != null) {
            mTargets.detach();
            mTargets = null;
        }
    }

    /**
     * A spring on a plain value (androidx SpringAnimation over a FloatValueHolder), retargeted
     * on every move and never restarted; {@code onUpdate} runs on each of its frames.
     */
    static final class Spring {
        private final FloatValueHolder mHolder = new FloatValueHolder();
        private final SpringAnimation mAnim;

        Spring(float damping, float stiffness, float minVisibleChange, Runnable onUpdate) {
            mAnim = new SpringAnimation(mHolder);
            mAnim.setSpring(new SpringForce().setDampingRatio(damping).setStiffness(stiffness));
            mAnim.setMinimumVisibleChange(minVisibleChange);
            mAnim.addUpdateListener((anim, value, velocity) -> onUpdate.run());
        }

        float value() {
            return mHolder.getValue();
        }

        /** Springs to {@code target}; nothing if it is already there and at rest. */
        void to(float target) {
            if (!mAnim.isRunning() && mHolder.getValue() == target) return;
            mAnim.animateToFinalPosition(target);
        }

        /** Jumps to {@code value} (a target's first showing: no fly-in). */
        void snap(float value) {
            mAnim.cancel();
            mHolder.setValue(value);
        }

        void cancel() {
            mAnim.cancel();
        }
    }

    // --- the action after the gesture ------------------------------------------------------------

    private static Target sPendingTarget = Target.NONE;
    private static int sPendingTaskId = -1;
    private static long sPendingAt;

    /**
     * Whether a floating-window corner action waits for the gesture to end in recents: floating
     * windows must not shrink just before it opens one (HgFreeformState). Main thread.
     */
    public static boolean hasPendingFloating() {
        return sPendingTarget == Target.FLOATING
                && SystemClock.uptimeMillis() - sPendingAt <= PENDING_TIMEOUT_MS;
    }

    /** A new gesture began: an earlier gesture's action never carries over. */
    public static void clearPending() {
        sPendingTarget = Target.NONE;
        sPendingTaskId = -1;
    }

    /** Main thread: the gesture will end in recents; run {@code target} for {@code taskId} there. */
    public static void setPending(Target target, int taskId) {
        sPendingTarget = target;
        sPendingTaskId = taskId;
        sPendingAt = SystemClock.uptimeMillis();
        Log.i(TAG, "released in the " + target + " corner over task " + taskId);
    }

    /** RecentsView.onGestureAnimationEnd: runs a pending action if the gesture ended in recents. */
    /** Whether a corner action waits for the gesture's end (RecentsView: then no other move). */
    public static boolean hasPending() {
        return sPendingTarget != Target.NONE;
    }

    public static void onGestureAnimationEnd(RecentsView<?, ?> recentsView, boolean endedInRecents) {
        Target target = sPendingTarget;
        int taskId = sPendingTaskId;
        long age = SystemClock.uptimeMillis() - sPendingAt;
        sPendingTarget = Target.NONE;
        sPendingTaskId = -1;
        if (target == Target.NONE) return;
        if (!endedInRecents || age > PENDING_TIMEOUT_MS) {
            Log.i(TAG, "corner action dropped (in recents: " + endedInRecents + ", " + age + " ms)");
            if (endedInRecents) HgFreeformState.toRecents(recentsView.getContext(), true);
            return;
        }
        if (target == Target.FLOATING) {
            if (!runShortcut(recentsView, taskId, HgTaskShortcuts.FLOATING_WINDOW)) {
                // Not opened, recents stays up: floating windows shrink as for any opening.
                HgFreeformState.toRecents(recentsView.getContext(), true);
            }
            return;
        }
        // The split corner: floating windows shrink as for any opening of recents.
        HgFreeformState.toRecents(recentsView.getContext(), true);
        // HyperOS's drag to split: the app on top, the second app from the home screen.
        if (HgSplitSingle.available(recentsView.getContext())) {
            recentsView.post(() -> HgSplitSingle.start(recentsView, taskId));
            return;
        }
        runShortcut(recentsView, taskId, HgTaskShortcuts.SPLIT_SCREEN);
    }

    /** Runs {@code factory}'s card action for {@code taskId}; false if there was none. */
    private static boolean runShortcut(RecentsView<?, ?> recentsView, int taskId,
            TaskShortcutFactory factory) {
        TaskView taskView = recentsView.getTaskViewByTaskId(taskId);
        if (taskView == null || taskView.getTaskContainers().isEmpty()) {
            Log.w(TAG, "corner action: no card for task " + taskId);
            return false;
        }
        TaskContainer first = taskView.getTaskContainers().get(0);
        RecentsViewContainer container =
                RecentsViewContainer.containerFromContext(recentsView.getContext());
        List<SystemShortcut> shortcuts;
        try {
            shortcuts = factory.getShortcuts(container, first);
        } catch (RuntimeException e) {
            Log.w(TAG, "corner action failed to load", e);
            return false;
        }
        if (shortcuts == null || shortcuts.isEmpty()) {
            Log.i(TAG, "corner action not offered for task " + taskId + "; left in recents");
            return false;
        }
        // After the frame that settled recents, so the card is laid out where the action expects.
        recentsView.post(() -> shortcuts.get(0).onClick(taskView));
        return true;
    }
}
