/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.interaction;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.core.math.MathUtils;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.app.animation.Interpolators;
import com.android.launcher3.R;
import com.android.launcher3.testing.shared.ResourceUtils;
import com.android.quickstep.HgRecentsSettings;

/** Forked from platform/frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/NavigationBarEdgePanel.java. */
public class EdgeBackGesturePanel extends View {

    private static final String LOG_TAG = "EdgeBackGesturePanel";

    private static final long DISAPPEAR_FADE_ANIMATION_DURATION_MS = 80;
    private static final long DISAPPEAR_ARROW_ANIMATION_DURATION_MS = 100;

    /**
     * The time required since the first vibration effect to automatically trigger a click
     */
    private static final int GESTURE_DURATION_FOR_CLICK_MS = 400;

    /**
     * The basic translation in dp where the arrow resides
     */
    private static final int BASE_TRANSLATION_DP = 32;

    /**
     * The length of the arrow leg measured from the center to the end
     */
    private static final int ARROW_LENGTH_DP = 18;

    /**
     * The angle measured from the xAxis, where the leg is when the arrow rests
     */
    private static final int ARROW_ANGLE_WHEN_EXTENDED_DEGREES = 56;

    /**
     * The angle that is added per 1000 px speed to the angle of the leg
     */
    private static final int ARROW_ANGLE_ADDED_PER_1000_SPEED = 4;

    /**
     * The maximum angle offset allowed due to speed
     */
    private static final int ARROW_MAX_ANGLE_SPEED_OFFSET_DEGREES = 4;

    /**
     * The thickness of the arrow. Adjusted to match the home handle (approximately)
     */
    private static final float ARROW_THICKNESS_DP = 2.5f;

    /**
     * The amount of rubber banding we do for the vertical translation
     */
    private static final int RUBBER_BAND_AMOUNT = 15;

    /**
     * The interpolator used to rubberband
     */
    private static final Interpolator RUBBER_BAND_INTERPOLATOR =
            new PathInterpolator(1.0f / 5.0f, 1.0f, 1.0f, 1.0f);

    /**
     * The amount of rubber banding we do for the translation before base translation
     */
    private static final int RUBBER_BAND_AMOUNT_APPEAR = 4;

    /**
     * The interpolator used to rubberband the appearing of the arrow.
     */
    private static final Interpolator RUBBER_BAND_INTERPOLATOR_APPEAR =
            new PathInterpolator(1.0f / RUBBER_BAND_AMOUNT_APPEAR, 1.0f, 1.0f, 1.0f);

    private BackCallback mBackCallback;

    /**
     * The paint the arrow is drawn with
     */
    private final Paint mPaint = new Paint();

    private final float mDensity;
    private final float mBaseTranslation;
    private final float mArrowLength;
    private final float mArrowThickness;

    /**
     * The minimum delta needed in movement for the arrow to change direction / stop triggering back
     */
    private final float mMinDeltaForSwitch;
    // The closest to y = 0 that the arrow will be displayed.
    private int mMinArrowPosition;
    // The amount the arrow is shifted to avoid the finger.
    private int mFingerOffset;

    private final float mSwipeThreshold;
    private final Path mArrowPath = new Path();
    private final Point mDisplaySize = new Point();

    private final SpringAnimation mAngleAnimation;
    private final SpringAnimation mTranslationAnimation;
    private final SpringAnimation mVerticalTranslationAnimation;
    private final SpringForce mAngleAppearForce;
    private final SpringForce mAngleDisappearForce;
    private final ValueAnimator mArrowDisappearAnimation;
    private final SpringForce mRegularTranslationSpring;
    private final SpringForce mTriggerBackSpring;

    private VelocityTracker mVelocityTracker;
    private int mArrowPaddingEnd;

    /**
     * True if the panel is currently on the left of the screen
     */
    private boolean mIsLeftPanel;

    private float mStartX;
    private float mStartY;
    private float mCurrentAngle;
    /**
     * The current translation of the arrow
     */
    private float mCurrentTranslation;
    /**
     * Where the arrow will be in the resting position.
     */
    private float mDesiredTranslation;

    private boolean mDragSlopPassed;
    private boolean mArrowsPointLeft;
    private float mMaxTranslation;
    private boolean mTriggerBack;
    private float mPreviousTouchTranslation;
    private float mTotalTouchDelta;
    private float mVerticalTranslation;
    private float mDesiredVerticalTranslation;
    private float mDesiredAngle;
    private float mAngleOffset;
    private float mDisappearAmount;
    private long mVibrationTime;
    private int mScreenSize;

    // HyperGestures: MiuiHome's back ticks (HgBackHaptics) for both arrow styles, in place of
    // AOSP's clicks; the system's haptic feedback setting alone gates them (System
    // haptic_feedback_disable when set, HyperOS's toggle; else haptic_feedback_enabled).
    private final HgBackHaptics mHgHaptics;

    // HyperGestures: stock HyperOS's indicator (HgMiuiBackArrow) and commit rule
    // (HgBackPolicy.commitsOnRelease) unless Settings.Global hypergestures_back_style is 0, read
    // on every touch-down; 0 keeps this AOSP arrow and its rule (back from the drag slop on).
    private final HgMiuiBackArrow mHgMiuiArrow;
    private boolean mHgMiuiStyle;
    /** The AOSP arrow's panel size (the MIUI bulge's panel is taller and wider). */
    private final int mHgAospWidth;
    private final int mHgAospHeight;
    /** Sideways travel away from the edge at the last event, raw px (MIUI arrow). */
    private float mHgInwardTravel;

    private final DynamicAnimation.OnAnimationEndListener mSetGoneEndListener =
            new DynamicAnimation.OnAnimationEndListener() {
                @Override
                public void onAnimationEnd(
                        DynamicAnimation animation, boolean canceled, float value, float velocity) {
                    animation.removeEndListener(this);
                    if (!canceled) {
                        setVisibility(GONE);
                    }
                }
            };

    private static final FloatPropertyCompat<EdgeBackGesturePanel> CURRENT_ANGLE =
            new FloatPropertyCompat<EdgeBackGesturePanel>("currentAngle") {
                @Override
                public void setValue(EdgeBackGesturePanel object, float value) {
                    object.setCurrentAngle(value);
                }

                @Override
                public float getValue(EdgeBackGesturePanel object) {
                    return object.getCurrentAngle();
                }
            };

    private static final FloatPropertyCompat<EdgeBackGesturePanel> CURRENT_TRANSLATION =
            new FloatPropertyCompat<EdgeBackGesturePanel>("currentTranslation") {
                @Override
                public void setValue(EdgeBackGesturePanel object, float value) {
                    object.setCurrentTranslation(value);
                }

                @Override
                public float getValue(EdgeBackGesturePanel object) {
                    return object.getCurrentTranslation();
                }
            };

    private static final FloatPropertyCompat<EdgeBackGesturePanel> CURRENT_VERTICAL_TRANSLATION =
            new FloatPropertyCompat<EdgeBackGesturePanel>("verticalTranslation") {

                @Override
                public void setValue(EdgeBackGesturePanel object, float value) {
                    object.setVerticalTranslation(value);
                }

                @Override
                public float getValue(EdgeBackGesturePanel object) {
                    return object.getVerticalTranslation();
                }
            };

    public EdgeBackGesturePanel(Context context, ViewGroup parent, LayoutParams layoutParams) {
        super(context);

        mDensity = context.getResources().getDisplayMetrics().density;
        // HyperGestures: MiuiHome's ticks, its arrow, and the AOSP arrow's size to go back to.
        mHgHaptics = new HgBackHaptics(context);
        mHgMiuiArrow = new HgMiuiBackArrow(this);
        mHgAospWidth = layoutParams.width;
        mHgAospHeight = layoutParams.height;

        mBaseTranslation = dp(BASE_TRANSLATION_DP);
        mArrowLength = dp(ARROW_LENGTH_DP);
        mArrowThickness = dp(ARROW_THICKNESS_DP);
        mMinDeltaForSwitch = dp(32);

        mPaint.setStrokeWidth(mArrowThickness);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);

        mArrowDisappearAnimation = ValueAnimator.ofFloat(0.0f, 1.0f);
        mArrowDisappearAnimation.setDuration(DISAPPEAR_ARROW_ANIMATION_DURATION_MS);
        mArrowDisappearAnimation.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mArrowDisappearAnimation.addUpdateListener(animation -> {
            mDisappearAmount = (float) animation.getAnimatedValue();
            invalidate();
        });

        mAngleAnimation =
                new SpringAnimation(this, CURRENT_ANGLE);
        mAngleAppearForce = new SpringForce()
                .setStiffness(500)
                .setDampingRatio(0.5f);
        mAngleDisappearForce = new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setFinalPosition(90);
        mAngleAnimation.setSpring(mAngleAppearForce).setMaxValue(90);

        mTranslationAnimation =
                new SpringAnimation(this, CURRENT_TRANSLATION);
        mRegularTranslationSpring = new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
        mTriggerBackSpring = new SpringForce()
                .setStiffness(450)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
        mTranslationAnimation.setSpring(mRegularTranslationSpring);
        mVerticalTranslationAnimation =
                new SpringAnimation(this, CURRENT_VERTICAL_TRANSLATION);
        mVerticalTranslationAnimation.setSpring(
                new SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        int currentNightMode =
                context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mPaint.setColor(context.getColor(R.color.gesture_tutorial_back_arrow_color));
        loadDimens();
        updateArrowDirection();

        mSwipeThreshold = ResourceUtils.getDimenByName(
            "navigation_edge_action_drag_threshold", context.getResources(), 16 /* defaultValue */);
        parent.addView(this, layoutParams);
        setVisibility(GONE);
    }

    void onDestroy() {
        mHgMiuiArrow.cancel(); // HyperGestures: stop the MIUI arrow's animations
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @SuppressLint("RtlHardcoded")
    void setIsLeftPanel(boolean isLeftPanel) {
        mIsLeftPanel = isLeftPanel;
    }

    boolean getIsLeftPanel() {
        return mIsLeftPanel;
    }

    void setDisplaySize(Point displaySize) {
        mDisplaySize.set(displaySize.x, displaySize.y);
        mScreenSize = Math.min(mDisplaySize.x, mDisplaySize.y);
    }

    void setBackCallback(BackCallback callback) {
        mBackCallback = callback;
    }

    private float getCurrentAngle() {
        return mCurrentAngle;
    }

    private float getCurrentTranslation() {
        return mCurrentTranslation;
    }

    void onMotionEvent(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragSlopPassed = false;
                resetOnDown();
                mStartX = event.getX();
                mStartY = event.getY();
                setVisibility(VISIBLE);
                if (mHgMiuiStyle) {
                    hgUseSize(mHgMiuiArrow.viewWidth(), mHgMiuiArrow.viewHeight());
                    hgPlaceMiuiArrow(event.getY());
                } else {
                    hgUseSize(mHgAospWidth, mHgAospHeight);
                    updatePosition(event.getY());
                }
                break;
            case MotionEvent.ACTION_MOVE:
                handleMoveEvent(event);
                break;
            case MotionEvent.ACTION_UP:
                if (mHgMiuiStyle) {
                    // HyperGestures: MiuiHome's rule (past 180 px or faster than 2 px/ms); a
                    // chevron armed at the last MOVE always goes back, even if the UP's own x
                    // lands just under 180 px, and a slow release before it never does.
                    float dx = event.getX() - mStartX;
                    mHgInwardTravel = HgBackArrowMath.inwardTravel(mIsLeftPanel, dx);
                    mVelocityTracker.computeCurrentVelocity(1 /* px per ms */);
                    if (HgBackPolicy.commitsOnRelease(mHgMiuiArrow.isArmed(), mIsLeftPanel, dx,
                            mVelocityTracker.getXVelocity(), mTriggerBack)) {
                        triggerBack();
                    } else {
                        cancelBack();
                    }
                } else if (mTriggerBack) {
                    triggerBack();
                } else {
                    cancelBack();
                }
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelBack();
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateArrowDirection();
        loadDimens();
        // HyperGestures: the MIUI arrow's sizes for the new density too.
        mHgMiuiArrow.loadDimens(getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mHgMiuiStyle) {
            mHgMiuiArrow.draw(canvas, mIsLeftPanel, getWidth(), getHeight());
            return;
        }
        float pointerPosition = mCurrentTranslation - mArrowThickness / 2.0f;
        canvas.save();
        canvas.translate(
                mIsLeftPanel ? pointerPosition : getWidth() - pointerPosition,
                (getHeight() * 0.5f) + mVerticalTranslation);

        // Let's calculate the position of the end based on the angle
        float x = (polarToCartX(mCurrentAngle) * mArrowLength);
        float y = (polarToCartY(mCurrentAngle) * mArrowLength);
        Path arrowPath = calculatePath(x, y);

        canvas.drawPath(arrowPath, mPaint);
        canvas.restore();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mMaxTranslation = getWidth() - mArrowPaddingEnd;
    }

    private void loadDimens() {
        Resources res = getResources();
        mArrowPaddingEnd = ResourceUtils.getDimenByName("navigation_edge_panel_padding", res, 8);
        mMinArrowPosition = ResourceUtils.getDimenByName("navigation_edge_arrow_min_y", res, 64);
        mFingerOffset = ResourceUtils.getDimenByName("navigation_edge_finger_offset", res, 48);
    }

    private void updateArrowDirection() {
        // Both panels arrow point the same way
        mArrowsPointLeft = getLayoutDirection() == LAYOUT_DIRECTION_LTR;
        invalidate();
    }

    private float getStaticArrowWidth() {
        return polarToCartX(ARROW_ANGLE_WHEN_EXTENDED_DEGREES) * mArrowLength;
    }

    private float polarToCartX(float angleInDegrees) {
        return (float) Math.cos(Math.toRadians(angleInDegrees));
    }

    private float polarToCartY(float angleInDegrees) {
        return (float) Math.sin(Math.toRadians(angleInDegrees));
    }

    private Path calculatePath(float x, float y) {
        if (!mArrowsPointLeft) {
            x = -x;
        }
        float extent = lerp(1.0f, 0.75f, mDisappearAmount);
        x = x * extent;
        y = y * extent;
        mArrowPath.reset();
        mArrowPath.moveTo(x, y);
        mArrowPath.lineTo(0, 0);
        mArrowPath.lineTo(x, -y);
        return mArrowPath;
    }

    private static float lerp(float start, float stop, float amount) {
        return start + (stop - start) * amount;
    }

    private void triggerBack() {
        // HyperGestures: the handler reports whether back was sent; it may refuse it
        // (HgBackPolicy.canCommit, a recents animation running or just finished).
        boolean hgSent = mBackCallback == null || mBackCallback.triggerBack();

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        if (!hgSent) {
            // HyperGestures: no back, so no hand-up tick or commit animation: leave as a cancel.
            hgHideAsCancelled();
            return;
        }
        // HyperGestures: MiuiHome's hand-up tick, dropped within 140 ms of the ready tick, in
        // place of AOSP's click on a slow or late release.
        mHgHaptics.onBackTriggered();
        if (mHgMiuiStyle) {
            // HyperGestures: MiuiHome collapses the same way for back and cancel.
            mHgMiuiArrow.onRelease(mHgInwardTravel);
            return;
        }

        // Let's also snap the angle a bit
        if (mAngleOffset > -4) {
            mAngleOffset = Math.max(-8, mAngleOffset - 8);
            updateAngle(true /* animated */);
        }

        // Finally, after the translation, animate back and disappear the arrow
        Runnable translationEnd = () -> {
            // let's snap it back
            mAngleOffset = Math.max(0, mAngleOffset + 8);
            updateAngle(true /* animated */);

            mTranslationAnimation.setSpring(mTriggerBackSpring);
            // Translate the arrow back a bit to make for a nice transition
            setDesiredTranslation(mDesiredTranslation - dp(32), true /* animated */);
            animate().alpha(0f).setDuration(DISAPPEAR_FADE_ANIMATION_DURATION_MS)
                    .withEndAction(() -> setVisibility(GONE));
            mArrowDisappearAnimation.start();
        };
        if (mTranslationAnimation.isRunning()) {
            mTranslationAnimation.addEndListener(new DynamicAnimation.OnAnimationEndListener() {
                @Override
                public void onAnimationEnd(DynamicAnimation animation, boolean canceled,
                        float value,
                        float velocity) {
                    animation.removeEndListener(this);
                    if (!canceled) {
                        translationEnd.run();
                    }
                }
            });
        } else {
            translationEnd.run();
        }
    }

    private void cancelBack() {
        if (mBackCallback != null) {
            mBackCallback.cancelBack();
        }
        hgHideAsCancelled();
    }

    // HyperGestures: cancelBack's arrow exit, split out for a back the handler refused to send.
    private void hgHideAsCancelled() {
        if (mHgMiuiStyle) {
            mHgMiuiArrow.onRelease(mHgInwardTravel);
            return;
        }

        if (mTranslationAnimation.isRunning()) {
            mTranslationAnimation.addEndListener(mSetGoneEndListener);
        } else {
            setVisibility(GONE);
        }
    }

    private void resetOnDown() {
        mHgMiuiStyle = HgRecentsSettings.isOn(getContext(), HgRecentsSettings.BACK_STYLE);
        mHgMiuiArrow.onDown();
        mHgInwardTravel = 0;
        animate().cancel();
        mAngleAnimation.cancel();
        mTranslationAnimation.cancel();
        mVerticalTranslationAnimation.cancel();
        mArrowDisappearAnimation.cancel();
        mAngleOffset = 0;
        mHgHaptics.onDown();
        mTranslationAnimation.setSpring(mRegularTranslationSpring);
        // Reset the arrow to the side
        setTriggerBack(false /* triggerBack */, false /* animated */);
        setDesiredTranslation(0, false /* animated */);
        setCurrentTranslation(0);
        updateAngle(false /* animate */);
        mPreviousTouchTranslation = 0;
        mTotalTouchDelta = 0;
        mVibrationTime = 0;
        setDesiredVerticalTransition(0, false /* animated */);
        if (mHgMiuiStyle) {
            // HyperGestures: the AOSP arrow's exit fades the panel out; the MIUI one never does.
            setAlpha(1f);
        }
    }

    private void handleMoveEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float touchTranslation = Math.abs(x - mStartX);
        float yOffset = y - mStartY;
        float delta = touchTranslation - mPreviousTouchTranslation;
        if (Math.abs(delta) > 0) {
            if (Math.signum(delta) == Math.signum(mTotalTouchDelta)) {
                mTotalTouchDelta += delta;
            } else {
                mTotalTouchDelta = delta;
            }
        }
        mPreviousTouchTranslation = touchTranslation;

        // Apply a haptic on drag slop passed
        if (!mDragSlopPassed && touchTranslation > mSwipeThreshold) {
            mDragSlopPassed = true;
            // HyperGestures: no AOSP click here; MiuiHome's ready tick comes past 180 px instead.
            mVibrationTime = SystemClock.uptimeMillis();

            // Let's show the arrow and animate it in!
            mDisappearAmount = 0.0f;
            setAlpha(1f);
            // And animate it go to back by default!
            setTriggerBack(true /* triggerBack */, true /* animated */);
        }

        // Let's make sure we only go to the baseextend and apply rubberbanding afterwards
        if (touchTranslation > mBaseTranslation) {
            float diff = touchTranslation - mBaseTranslation;
            float progress = MathUtils.clamp(diff / (mScreenSize - mBaseTranslation), 0, 1);
            progress = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
                    * (mMaxTranslation - mBaseTranslation);
            touchTranslation = mBaseTranslation + progress;
        } else {
            float diff = mBaseTranslation - touchTranslation;
            float progress = MathUtils.clamp(diff / mBaseTranslation, 0, 1);
            progress = RUBBER_BAND_INTERPOLATOR_APPEAR.getInterpolation(progress)
                    * (mBaseTranslation / RUBBER_BAND_AMOUNT_APPEAR);
            touchTranslation = mBaseTranslation - progress;
        }
        // By default we just assume the current direction is kept
        boolean triggerBack = mTriggerBack;

        //  First lets see if we had continuous motion in one direction for a while
        if (Math.abs(mTotalTouchDelta) > mMinDeltaForSwitch) {
            triggerBack = mTotalTouchDelta > 0;
        }

        // Then, let's see if our velocity tells us to change direction
        mVelocityTracker.computeCurrentVelocity(1000);
        float xVelocity = mVelocityTracker.getXVelocity();
        float yVelocity = mVelocityTracker.getYVelocity();
        float velocity = (float) Math.hypot(xVelocity, yVelocity);
        mAngleOffset = Math.min(velocity / 1000 * ARROW_ANGLE_ADDED_PER_1000_SPEED,
                ARROW_MAX_ANGLE_SPEED_OFFSET_DEGREES) * Math.signum(xVelocity);
        if (mIsLeftPanel && mArrowsPointLeft || !mIsLeftPanel && !mArrowsPointLeft) {
            mAngleOffset *= -1;
        }

        // Last if the direction in Y is bigger than X * 2 we also abort
        if (Math.abs(yOffset) > Math.abs(x - mStartX) * 2) {
            triggerBack = false;
        }
        setTriggerBack(triggerBack, true /* animated */);
        // HyperGestures: with the MIUI arrow, travel counts inward only, so the ready tick, the
        // chevron and the commit rule measure the same thing.
        float hgTravel = mHgMiuiStyle
                ? HgBackArrowMath.inwardTravel(mIsLeftPanel, x - mStartX)
                : Math.abs(x - mStartX);
        // HyperGestures: MiuiHome's ready tick, once, past 180 px of sideways travel while a
        // release would trigger back.
        mHgHaptics.onMove(hgTravel, mTriggerBack);
        if (mHgMiuiStyle) {
            // HyperGestures: the bulge stretches and the chevron arms; no AOSP springs.
            mHgInwardTravel = hgTravel;
            mHgMiuiArrow.onMove(hgTravel, mTriggerBack);
            return;
        }

        if (!mTriggerBack) {
            touchTranslation = 0;
        } else if (mIsLeftPanel && mArrowsPointLeft
                || (!mIsLeftPanel && !mArrowsPointLeft)) {
            // If we're on the left we should move less, because the arrow is facing the other
            // direction
            touchTranslation -= getStaticArrowWidth();
        }
        setDesiredTranslation(touchTranslation, true /* animated */);
        updateAngle(true /* animated */);

        float maxYOffset = getHeight() / 2.0f - mArrowLength;
        float progress =
                MathUtils.clamp(Math.abs(yOffset) / (maxYOffset * RUBBER_BAND_AMOUNT), 0, 1);
        float verticalTranslation = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
                * maxYOffset * Math.signum(yOffset);
        setDesiredVerticalTransition(verticalTranslation, true /* animated */);
    }

    private void updatePosition(float touchY) {
        float positionY = touchY - mFingerOffset;
        positionY = Math.max(positionY, mMinArrowPosition);
        positionY -= getLayoutParams().height / 2.0f;
        setX(mIsLeftPanel ? 0 : mDisplaySize.x - getLayoutParams().width);
        setY(MathUtils.clamp((int) positionY, 0, mDisplaySize.y));
    }

    // HyperGestures: the panel's size for the arrow style read at this touch-down.
    private void hgUseSize(int width, int height) {
        LayoutParams lp = getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            setLayoutParams(lp);
        }
    }

    // HyperGestures: MiuiHome anchors the bulge once, centred 20 px above the touch-down and
    // allowed past the screen's top or bottom; it never follows the finger.
    private void hgPlaceMiuiArrow(float touchY) {
        setX(mIsLeftPanel ? 0 : mDisplaySize.x - mHgMiuiArrow.viewWidth());
        setY(touchY - HgBackArrowMath.ANCHOR_ABOVE_DOWN_PX - mHgMiuiArrow.viewHeight() / 2f);
    }

    private void setDesiredVerticalTransition(float verticalTranslation, boolean animated) {
        if (mDesiredVerticalTranslation != verticalTranslation) {
            mDesiredVerticalTranslation = verticalTranslation;
            if (!animated) {
                setVerticalTranslation(verticalTranslation);
            } else {
                mVerticalTranslationAnimation.animateToFinalPosition(verticalTranslation);
            }
            invalidate();
        }
    }

    private void setVerticalTranslation(float verticalTranslation) {
        mVerticalTranslation = verticalTranslation;
        invalidate();
    }

    private float getVerticalTranslation() {
        return mVerticalTranslation;
    }

    private void setDesiredTranslation(float desiredTranslation, boolean animated) {
        if (mDesiredTranslation != desiredTranslation) {
            mDesiredTranslation = desiredTranslation;
            if (!animated) {
                setCurrentTranslation(desiredTranslation);
            } else {
                mTranslationAnimation.animateToFinalPosition(desiredTranslation);
            }
        }
    }

    private void setCurrentTranslation(float currentTranslation) {
        mCurrentTranslation = currentTranslation;
        invalidate();
    }

    private void setTriggerBack(boolean triggerBack, boolean animated) {
        if (mTriggerBack != triggerBack) {
            mTriggerBack = triggerBack;
            // HyperGestures: the MIUI arrow has no angle or translation springs to drive.
            if (mHgMiuiStyle) return;
            mAngleAnimation.cancel();
            updateAngle(animated);
            // Whenever the trigger back state changes the existing translation animation should be
            // cancelled
            mTranslationAnimation.cancel();
        }
    }

    private void updateAngle(boolean animated) {
        float newAngle = mTriggerBack ? ARROW_ANGLE_WHEN_EXTENDED_DEGREES + mAngleOffset : 90;
        if (newAngle != mDesiredAngle) {
            if (!animated) {
                setCurrentAngle(newAngle);
            } else {
                mAngleAnimation.setSpring(mTriggerBack ? mAngleAppearForce : mAngleDisappearForce);
                mAngleAnimation.animateToFinalPosition(newAngle);
            }
            mDesiredAngle = newAngle;
        }
    }

    private void setCurrentAngle(float currentAngle) {
        mCurrentAngle = currentAngle;
        invalidate();
    }

    private float dp(float dp) {
        return mDensity * dp;
    }

    /** Callback to let the gesture handler react to the detected back gestures. */
    interface BackCallback {
        /**
         * Indicates that a Back gesture was recognized.
         * HyperGestures: @return whether back was sent; false plays the cancel exit instead.
         */
        boolean triggerBack();

        /** Indicates that the gesture was cancelled. */
        void cancelBack();
    }
}
