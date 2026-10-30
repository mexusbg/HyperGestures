// HyperGestures: swipe a recents card down to lock or unlock its app, as in HyperOS's recents
// (design ruling 5). Replaces AOSP's swipe-down-to-launch (TaskViewLaunchTouchController) in the
// fallback recents drag layer; a tap still launches.
//
// The card follows the finger with resistance (its secondary dismiss translation, which the
// swipe-up dismiss also uses; the two never run together) and a frosted badge with the state the
// card will switch to grows in the gap above it. Past ARM_DP of card travel the badge is armed (a
// haptic tick); releasing armed toggles the lock (HgTaskLocking), otherwise nothing changes. Either
// way the card springs back. The same guards as AOSP's swipe-down apply
// (RecentsView.shouldSwipeDownLaunchTaskView: no open menu, not scrolling, interactive state, the
// centred card), plus gesture navigation and a card with something to lock.
package com.android.quickstep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.display.DisplayController;
import com.android.launcher3.touch.SingleAxisSwipeDetector;
import com.android.launcher3.util.TouchController;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.orientation.RecentsPagedOrientationHandler;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.quickstep.views.TaskView;

public class HgTaskLockSwipeController<T extends Context & RecentsViewContainer>
        implements TouchController, SingleAxisSwipeDetector.Listener {
    private static final String TAG = "HgTaskLockSwipe";

    /** Card travel (dp) at which releasing toggles the lock. */
    static final float ARM_DP = 56;
    /** The card moves this fraction of the finger's travel up to ARM_DP... */
    static final float FOLLOW = 0.5f;
    /** ...and this fraction of the rest beyond it. */
    static final float FOLLOW_PAST_ARM = 0.15f;
    private static final float BADGE_DP = 48;
    private static final float BADGE_PADDING_DP = 13;
    private static final long SPRING_BACK_MS = 380;

    private final T mContainer;
    private final RecentsView<?, ?> mRecentsView;
    private final SingleAxisSwipeDetector mDetector;
    private final boolean mIsRtl;
    private final float mDensity;
    private final Rect mTempRect = new Rect();

    @Nullable private TaskView mTaskView;
    @Nullable private ImageView mBadge;
    @Nullable private Animator mReleaseAnim;
    private boolean mCanIntercept;
    private boolean mDragging;
    private boolean mArmed;
    private boolean mTargetLocked;
    private int mDisplacementFactor = 1;
    private int mDownDirection = SingleAxisSwipeDetector.DIRECTION_NEGATIVE;
    /** The orientation's dismiss direction on the secondary axis (-1 in portrait). */
    private int mDismissDirection = -1;
    private float mCardTop;
    private float mCardCenterX;

    public HgTaskLockSwipeController(T container) {
        mContainer = container;
        mRecentsView = container.getOverviewPanel();
        RecentsPagedOrientationHandler orientation = mRecentsView.getPagedOrientationHandler();
        mDetector = new SingleAxisSwipeDetector(container, this, orientation.getUpDownSwipeDirection());
        mIsRtl = Utilities.isRtl(container.getResources());
        mDensity = container.getResources().getDisplayMetrics().density;
    }

    /**
     * The card's travel for a finger travel {@code d} (px, positive downward): {@link #FOLLOW} of
     * it up to the arm point, then {@link #FOLLOW_PAST_ARM} of the rest; nothing upward.
     */
    static float cardTravel(float d, float armPx) {
        if (d <= 0) return 0;
        float t = d * FOLLOW;
        return t <= armPx ? t : armPx + (t - armPx) * (FOLLOW_PAST_ARM / FOLLOW);
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !mDragging) {
            clearState();
        }
        if (action == MotionEvent.ACTION_DOWN) {
            mCanIntercept = onActionDown(ev);
            if (!mCanIntercept) {
                clearState();
                return false;
            }
        }
        if (!mCanIntercept) return false;
        onControllerTouchEvent(ev);
        // "Down" is the detector's negative direction in portrait and landscape, positive in
        // seascape (as AOSP's TaskViewLaunchTouchController checks it).
        boolean downIsNegative = mDownDirection == SingleAxisSwipeDetector.DIRECTION_NEGATIVE;
        boolean initialTouchDown = downIsNegative != mDetector.wasInitialTouchPositive();
        return mDetector.isDraggingState() && initialTouchDown;
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        return mDetector.onTouchEvent(ev);
    }

    private boolean onActionDown(MotionEvent ev) {
        if (!HgRecentsSettings.isOn(mContainer, HgRecentsSettings.CARD_LOCK_SWIPE)) return false;
        if ((ev.getEdgeFlags() & Utilities.EDGE_NAV_BAR) != 0) return false;
        if (!DisplayController.getNavigationMode(mContainer).hasGestures) return false;
        if (mReleaseAnim != null && mReleaseAnim.isRunning()) mReleaseAnim.end();
        BaseDragLayer<?> dragLayer = mContainer.getDragLayer();
        TaskView found = null;
        for (TaskView taskView : mRecentsView.getTaskViews()) {
            if (mRecentsView.isTaskViewVisible(taskView) && dragLayer.isEventOverView(taskView, ev)) {
                found = taskView;
                break;
            }
        }
        if (found == null || !mRecentsView.shouldSwipeDownLaunchTaskView(found)
                || !HgTaskLocking.canLock(found)) {
            return false;
        }
        mTaskView = found;
        mDisplacementFactor =
                mRecentsView.getPagedOrientationHandler().getTaskDragDisplacementFactor(mIsRtl);
        mDownDirection = mRecentsView.getPagedOrientationHandler().getDownDirection(mIsRtl);
        mDismissDirection =
                mRecentsView.getPagedOrientationHandler().getTaskDismissVerticalDirection();
        mDetector.setDetectableScrollConditions(mDownDirection, /* ignoreSlop= */ false);
        return true;
    }

    @Override
    public void onDragStart(boolean start, float startDisplacement) {
        TaskView taskView = mTaskView;
        if (taskView == null) return;
        mDragging = true;
        mArmed = false;
        mTargetLocked = !HgTaskLocking.isLocked(mContainer, taskView);
        mContainer.getDragLayer().getDescendantRectRelativeToSelf(taskView, mTempRect);
        mCardTop = mTempRect.top;
        mCardCenterX = mTempRect.exactCenterX();
        mBadge = createBadge(mTargetLocked);
        Log.d(TAG, "drag started on task " + taskView.getTaskIds()[0] + ", will "
                + (mTargetLocked ? "lock" : "unlock"));
    }

    private ImageView createBadge(boolean lock) {
        Context context = mContainer;
        int size = Math.round(BADGE_DP * mDensity);
        int padding = Math.round(BADGE_PADDING_DP * mDensity);
        ImageView badge = new ImageView(context);
        badge.setImageResource(lock ? R.drawable.ic_lock : R.drawable.hg_ic_task_unlock);
        badge.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        HgBlurSupport.followGlassCircle(badge); // frosted, or solid without blur
        badge.setPadding(padding, padding, padding, padding);
        badge.setAlpha(0f);
        badge.setScaleX(0.6f);
        badge.setScaleY(0.6f);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Drag-layer coordinates: no inset margins (InsettableFrameLayout adds them otherwise).
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(size, size);
        lp.ignoreInsets = true;
        mContainer.getDragLayer().addView(badge, lp);
        badge.setTranslationX(mCardCenterX - size / 2f);
        badge.setTranslationY(mCardTop - size / 2f);
        return badge;
    }

    @Override
    public boolean onDrag(float displacement) {
        TaskView taskView = mTaskView;
        if (taskView == null) return true;
        float armPx = ARM_DP * mDensity;
        float travel = cardTravel(displacement * mDisplacementFactor, armPx);
        // Away from the dismiss direction, whichever sign that has in this orientation.
        float translation = travel * -mDismissDirection;
        taskView.getSecondaryDismissTranslationProperty().setValue(taskView, translation);
        HgLiveTile.mirrorSecondary(taskView, translation);
        float progress = Math.min(1f, travel / armPx);
        boolean armed = travel >= armPx;
        if (armed != mArmed) {
            mArmed = armed;
            taskView.performHapticFeedback(armed
                    ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    : HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE);
        }
        ImageView badge = mBadge;
        if (badge != null) {
            // In the gap the card leaves above it, growing with the pull.
            float size = badge.getLayoutParams().width;
            badge.setTranslationY(mCardTop + travel / 2f - size / 2f);
            badge.setAlpha(progress);
            float scale = 0.6f + 0.4f * progress + (mArmed ? 0.12f : 0f);
            badge.setScaleX(scale);
            badge.setScaleY(scale);
        }
        return true;
    }

    @Override
    public void onDragEnd(float velocity) {
        TaskView taskView = mTaskView;
        ImageView badge = mBadge;
        mDragging = false;
        if (taskView == null) {
            clearState();
            return;
        }
        boolean toggle = mArmed;
        if (toggle) {
            HgTaskLocking.setLocked(mContainer, taskView, mTargetLocked);
            // MiuiHome's lock / unlock effect, else the CONFIRM tick (HgRecentsHaptics).
            HgRecentsHaptics.playLock(taskView, mTargetLocked);
            Log.i(TAG, (mTargetLocked ? "locked" : "unlocked") + " by swipe down");
        }
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator card = ObjectAnimator.ofFloat(taskView,
                taskView.getSecondaryDismissTranslationProperty(), 0f);
        card.setInterpolator(new OvershootInterpolator(1.1f));
        card.setDuration(SPRING_BACK_MS);
        card.addUpdateListener(a -> HgLiveTile.mirrorSecondary(taskView, (float) a.getAnimatedValue()));
        if (badge != null) {
            ObjectAnimator fade = ObjectAnimator.ofFloat(badge, View.ALPHA, 0f);
            fade.setInterpolator(new DecelerateInterpolator());
            fade.setDuration(toggle ? SPRING_BACK_MS + 120 : SPRING_BACK_MS / 2);
            fade.setStartDelay(toggle ? 120 : 0);
            ObjectAnimator rise = ObjectAnimator.ofFloat(badge, View.TRANSLATION_Y,
                    badge.getTranslationY() - (toggle ? 24 * mDensity : 0));
            rise.setDuration(SPRING_BACK_MS);
            set.playTogether(card, fade, rise);
        } else {
            set.play(card);
        }
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                removeBadge(badge);
            }
        });
        mReleaseAnim = set;
        set.start();
        mTaskView = null;
        mBadge = null;
        clearDetector();
    }

    private void removeBadge(@Nullable ImageView badge) {
        if (badge != null && badge.getParent() != null) {
            mContainer.getDragLayer().removeView(badge);
        }
    }

    private void clearDetector() {
        mDetector.finishedScrolling();
        mDetector.setDetectableScrollConditions(0, false);
    }

    private void clearState() {
        clearDetector();
        if (mTaskView != null && !mDragging) {
            mTaskView = null;
        }
        if (mBadge != null && !mDragging) {
            removeBadge(mBadge);
            mBadge = null;
        }
        mArmed = false;
    }
}
