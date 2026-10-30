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
package com.android.quickstep;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.content.Intent.EXTRA_COMPONENT_NAME;
import static android.content.Intent.EXTRA_USER;

import static com.android.app.animation.Interpolators.ACCELERATE;
import static com.android.app.animation.Interpolators.DECELERATE;
import static com.android.app.animation.Interpolators.LINEAR;
import static com.android.launcher3.GestureNavContract.EXTRA_ENABLE_GESTURE_CONTRACT;
import static com.android.launcher3.GestureNavContract.EXTRA_GESTURE_CONTRACT;
import static com.android.launcher3.GestureNavContract.EXTRA_ICON_POSITION;
import static com.android.launcher3.GestureNavContract.EXTRA_ICON_SURFACE;
import static com.android.launcher3.GestureNavContract.EXTRA_LAUNCH_COOKIE;
import static com.android.launcher3.GestureNavContract.EXTRA_ON_FINISH_CALLBACK;
import static com.android.launcher3.GestureNavContract.EXTRA_REMOTE_CALLBACK;
import static com.android.launcher3.anim.AnimatorListeners.forEndCallback;
import static com.android.quickstep.OverviewComponentObserver.startHomeIntentSafely;

import android.animation.ObjectAnimator;
import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Interpolator;
import android.window.TransitionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.anim.SpringAnimationBuilder;
import com.android.launcher3.display.DisplayController;
import com.android.launcher3.states.StateAnimationConfig;
import com.android.launcher3.util.MSDLPlayerWrapper;
import com.android.launcher3.util.ObjectWrapper;
import com.android.launcher3.util.StableViewInfo;
import com.android.quickstep.fallback.FallbackActivityRecentsView;
import com.android.quickstep.fallback.RecentsState;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.RectFSpringAnim;
import com.android.quickstep.util.SurfaceTransaction.SurfaceProperties;
import com.android.quickstep.util.TaskViewSimulator;
import com.android.quickstep.util.TransformParams;
import com.android.quickstep.util.TransformParams.BuilderProxy;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.Task.TaskKey;
import com.android.systemui.shared.system.InputConsumerController;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Handles the navigation gestures when a 3rd party launcher is the default home activity.
 */
public class FallbackSwipeHandler extends
        AbsSwipeUpHandler<RecentsActivity, FallbackActivityRecentsView, RecentsState> {

    private static final String TAG = "FallbackSwipeHandler";

    private static final float HOME_SWIPE_UP_SCALE = 0.9f;
    private static final float HOME_SWIPE_UP_ALPHA = 0.75f;
    private static final Interpolator HOME_SWIPE_UP_INTERPOLATOR = DECELERATE;

    /**
     * Message used for receiving gesture nav contract information. We use a static messenger to
     * avoid leaking too make binders in case the receiving launcher does not handle the contract
     * properly.
     */
    private static StaticMessageReceiver sMessageReceiver = null;

    private FallbackHomeAnimationFactory mActiveAnimationFactory;
    private final boolean mRunningOverHome;

    private final Matrix mTmpMatrix = new Matrix();

    private boolean mAppCanEnterPip;

    // HyperGestures: the home app's window alpha during a swipe over it. Its scale and position
    // are never changed; it stays 1 until recents is attached (a hold or a quick switch), then
    // fades out so recents shows (HgHomeGesturePolicy). Each change reapplies the transform.
    private final AnimatedFloat mHgHomeAlpha =
            new AnimatedFloat(this::applyHgHomeAlpha, HgHomeGesturePolicy.HOME_ALPHA_SHOWN);
    // HyperGestures: the recents blur during a swipe from an app to the home app
    // (hgFadeRecentsBlurOut).
    private final AnimatedFloat mHgHomeBlur = new AnimatedFloat(this::applyHgHomeBlur, 1f);
    // HyperGestures: drag an app's window to a top corner for split screen or a floating window
    // (HgCornerDrag); null over the home app, in landscape, or when switched off.
    @Nullable private HgCornerDrag mHgCornerDrag;
    private HgCornerDrag.Target mHgCornerReleased = HgCornerDrag.Target.NONE;
    // HyperGestures: while the finger is down the window takes the corner drag's shrink from its
    // hold springs; once it lifts, the shrink returns to 0 along the end animation from where the
    // card is drawn (mHgCornerBack: 1 -> 0 of the shrink at release).
    private boolean mHgCornerFollowsFinger;
    private final AnimatedFloat mHgCornerBack = new AnimatedFloat(this::applyHgCornerBack);
    private float mHgCornerBackFloating;
    private float mHgCornerBackSplit;

    // HyperGestures: this gesture was released over the home app where AOSP would have gone HOME
    // (HgHomeGesturePolicy.backOnRelease): it springs back, then sends a back key (HgHomeBack).
    private boolean mHgBackOnRelease;

    public FallbackSwipeHandler(Context context,
            TaskAnimationManager taskAnimationManager,
            RecentsAnimationDeviceState deviceState,
            RotationTouchHelper rotationTouchHelper,
            GestureState gestureState,
            boolean continuingLastGesture,
            InputConsumerController inputConsumer,
            MSDLPlayerWrapper msdlPlayerWrapper,
            int displayId) {
        super(context,
                taskAnimationManager,
                deviceState,
                rotationTouchHelper,
                gestureState,
                continuingLastGesture,
                inputConsumer,
                msdlPlayerWrapper,
                displayId);

        mRunningOverHome = mGestureState.getRunningTask() != null
                && mGestureState.getRunningTask().isHomeTask();
        // HyperGestures: from an app too, not only over the home app. Behind a translucent app
        // (Viber's chat screen) the home window is a closing target, and AOSP's default proxy
        // (ALWAYS_VISIBLE) drew it full screen above recents; this one hides it (alpha 0).
        runActionOnRemoteHandles(remoteTargetHandle ->
                remoteTargetHandle.getTransformParams().setHomeBuilderProxy(
                FallbackSwipeHandler.this::updateHomeActivityTransformDuringSwipeUp));
        if (mRunningOverHome) {
            // HyperGestures: no fade may outlive the gesture's hold on the home window's leash.
            mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED, mHgHomeAlpha::cancelAnimation);
            // Recents is hidden behind the home app until attached: no blur meanwhile, and full
            // blur again once the gesture is over (recents shown or gone).
            HgRecentsDepthController.setRecentsVisibility(0f);
            mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED, () -> {
                GestureState.GestureEndTarget end = mGestureState.getEndTarget();
                if (end == GestureState.GestureEndTarget.RECENTS
                        || end == GestureState.GestureEndTarget.NEW_TASK) {
                    HgRecentsDepthController.setRecentsVisibility(1f);
                } else {
                    // Back on the home app or cancelled: no blur flash behind it now.
                    HgRecentsDepthController.resetRecentsVisibilityQuietly();
                }
            });
        }
        HgCornerDrag.clearPending(); // HyperGestures: a new gesture; nothing carries over
        HgFreeformState.resetDedupe(); // HyperGestures
        if (!mRunningOverHome && HgRecentsSettings.isOn(context, HgRecentsSettings.CORNER_DRAG)) {
            float w = mDp.getDeviceProperties().getWidthPx();
            float h = mDp.getDeviceProperties().getHeightPx();
            if (w < h) {
                mHgCornerDrag = new HgCornerDrag(w, h,
                        context.getResources().getDisplayMetrics().density,
                        this::applyHgCornerHold);
                mHgCornerFollowsFinger = true;
                mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED, this::resetHgCorner);
            }
        }
    }

    // HyperGestures: reapply the window transform while the home window's alpha changes; the
    // recents blur follows how much of recents shows through (HgRecentsDepthController).
    private void applyHgHomeBlur() {
        HgRecentsDepthController.setRecentsVisibility(mHgHomeBlur.value);
    }

    /**
     * HyperGestures: a swipe from an app ends on the home app. AOSP fades the recents window out
     * late (ACCELERATE) and the home app in only once its window appears, near the end; with the
     * blur on recents' window, the last frames crossfaded a blurred, dimmed recents against a
     * half-faded home screen (owner, 2026-10-10: "blink when going home to Nova"). The blur goes
     * first, over the first half of the animation, so the card shrinks over the plain wallpaper
     * the home screen shows and only its icons fade in. Full blur again, quietly, for the next
     * time recents opens.
     */
    private void hgFadeRecentsBlurOut(long duration) {
        ObjectAnimator fade = mHgHomeBlur.animateToValue(0f);
        fade.setDuration(duration / 2);
        fade.setInterpolator(DECELERATE);
        fade.start();
        mStateCallback.runOnceAtState(STATE_HANDLER_INVALIDATED, () -> {
            mHgHomeBlur.cancelAnimation();
            HgRecentsDepthController.resetRecentsVisibilityQuietly();
        });
    }

    private void applyHgHomeAlpha() {
        HgRecentsDepthController.setRecentsVisibility(1f - mHgHomeAlpha.value);
        if (mRecentsAnimationController != null && mActiveAnimationFactory == null) {
            applyScrollAndTransform();
        }
    }

    // HyperGestures: over the home app, recents stays hidden behind it (no card drawn behind the
    // home screen) until the swipe is a hold or a quick switch or ends in RECENTS or NEW_TASK;
    // the home window fades out when recents is attached and back in when it is detached.
    // AOSP attached recents from the first frame, since the home window is "going away".
    @Override
    protected boolean adjustRecentsAttachedToAppWindow(
            boolean recentsAttachedToAppWindow, boolean attachedByGesture, boolean animate) {
        boolean attached = HgHomeGesturePolicy.recentsAttached(recentsAttachedToAppWindow,
                mRunningOverHome, mGestureState.getEndTarget(), attachedByGesture);
        if (!mRunningOverHome) {
            return attached;
        }
        float alpha = HgHomeGesturePolicy.homeWindowAlpha(attached);
        if (!animate && mGestureState.getEndTarget() != null) {
            // Settled on the end target: the final value now, before the recents animation is
            // finished to it. While the finger is down the fade is always animated.
            mHgHomeAlpha.cancelAnimation();
            mHgHomeAlpha.updateValue(alpha);
        } else if (!mHgHomeAlpha.isAnimatingToValue(alpha)
                && !mHgHomeAlpha.isSettledOnValue(alpha)) {
            if (alpha == HgHomeGesturePolicy.HOME_ALPHA_HIDDEN) {
                Log.i(TAG, "hold or quick switch over the home app: recents shown");
            }
            ObjectAnimator fade = mHgHomeAlpha.animateToValue(alpha);
            fade.setDuration(RECENTS_ATTACH_DURATION);
            fade.setInterpolator(LINEAR);
            fade.start();
        }
        return attached;
    }

    private void updateHomeActivityTransformDuringSwipeUp(
            SurfaceProperties builder, RemoteAnimationTarget app, TransformParams params) {
        if (mActiveAnimationFactory != null) {
            return;
        }
        // HyperGestures: over the home app its window is not scaled, faded or moved with the
        // swipe (the owner does not want the "slight shrink" of HOME_SWIPE_UP_SCALE/_ALPHA):
        // identity matrix, and only mHgHomeAlpha once recents is attached.
        if (mRunningOverHome) {
            if (app.windowConfiguration.getActivityType() == ACTIVITY_TYPE_HOME) {
                mTmpMatrix.reset();
                builder.setMatrix(mTmpMatrix).setAlpha(mHgHomeAlpha.value);
                builder.setShow();
            }
            return;
        }
        // The currentShift is already interpolated by the magnetic swipe detach effect
        float currentShift = getCurrentShiftValue();
        setHomeScaleAndAlpha(
                builder,
                app,
                currentShift,
                mRunningOverHome
                        ? Utilities.mapBoundToRange(
                                currentShift,
                                /* lowerBound= */ 0f,
                                /* upperBound= */ mDragLengthFactor,
                                /* toMin= */ 1f,
                                /* toMax= */ HOME_SWIPE_UP_ALPHA,
                                /* interpolator= */ HOME_SWIPE_UP_INTERPOLATOR)
                        : 0f);
    }

    private void setHomeScaleAndAlpha(
            SurfaceProperties builder,
            RemoteAnimationTarget app,
            float verticalShift,
            float alpha) {
        if (app.windowConfiguration.getActivityType() != ACTIVITY_TYPE_HOME) {
            return;
        }
        float scale = mRunningOverHome
                ? Utilities.mapBoundToRange(
                        verticalShift,
                        /* lowerBound= */ 0f,
                        /* upperBound= */ mDragLengthFactor,
                        /* toMin= */ 1f,
                        /* toMax= */ HOME_SWIPE_UP_SCALE,
                        /* interpolator= */ HOME_SWIPE_UP_INTERPOLATOR)
                : 1f;
        mTmpMatrix.setScale(scale, scale,
                app.localBounds.exactCenterX(), app.localBounds.exactCenterY());
        builder.setMatrix(mTmpMatrix).setAlpha(alpha);
        builder.setShow();
    }

    // HyperGestures: a released swipe up over the home app itself (the third-party HOME) stays
    // on it. HOME would start the home intent to the home app already in front (a home-to-home
    // transition and a flash, runbook B4); LAST_TASK springs the window back and finishes the
    // recents animation to the running task, as a cancelled gesture does. Hold (RECENTS) and
    // quick switch (NEW_TASK) never come through here. Rule in HgHomeGesturePolicy (unit-tested).
    @Override
    public void hgOnFingerMove(float x, float y) {
        if (mHgCornerDrag != null) mHgCornerDrag.onMove(x, y, mRecentsView);
    }

    @Override
    public void hgOnFingerUp(float x, float y) {
        mHgCornerFollowsFinger = false;
        if (mHgCornerDrag != null) mHgCornerReleased = mHgCornerDrag.onRelease();
    }

    @Override
    public void onGestureCancelled() {
        mHgCornerFollowsFinger = false;
        if (mHgCornerDrag != null) mHgCornerDrag.dismiss();
        mHgCornerReleased = HgCornerDrag.Target.NONE;
        super.onGestureCancelled();
    }

    // HyperGestures: the corner drag's shrink goes onto the window each time it is applied, and
    // the floating frame follows the card as drawn (HgCornerDrag, TaskViewSimulator
    // hgCornerFloating). Applied last there, so it shrinks the card wherever the horizontal,
    // stack or vertical layout has put it during the swipe.
    @Override
    protected void applyScrollAndTransform() {
        HgCornerDrag drag = mHgCornerDrag;
        boolean follow = drag != null && mHgCornerFollowsFinger && mRemoteTargetHandles != null;
        if (follow) {
            float floating = drag.floatingProgress();
            float split = drag.splitProgress();
            float[] target = drag.splitCardTarget();
            for (RemoteTargetGluer.RemoteTargetHandle handle : mRemoteTargetHandles) {
                TaskViewSimulator tvs = handle.getTaskViewSimulator();
                tvs.hgCornerFloating.value = floating;
                tvs.hgCornerSplit.value = split;
                System.arraycopy(target, 0, tvs.hgCornerSplitTarget, 0, 4);
            }
        }
        super.applyScrollAndTransform();
        if (follow && drag.wantsCard() && mRemoteTargetHandles.length > 0) {
            TaskViewSimulator tvs = mRemoteTargetHandles[0].getTaskViewSimulator();
            RectF card = tvs.getCurrentRect();
            drag.onCardMoved(card.left, card.top, card.right, card.bottom,
                    tvs.getScaledCornerRadius());
        }
    }

    /** HyperGestures: a hold spring moved while the finger is down: reapply the window. */
    private void applyHgCornerHold() {
        if (mHgCornerFollowsFinger) applyScrollAndTransform();
    }

    /**
     * HyperGestures: the finger lifted with the card shrunk over a corner target (the springs
     * stopped where they were): it grows back over the end animation, with its duration and
     * interpolator, so the card goes on from where it is drawn. HOME needs nothing: its window
     * animation starts from the current rect, shrink included.
     */
    private void hgAnimateCornerBack(long duration, Interpolator interpolator,
            GestureState.GestureEndTarget endTarget) {
        if (mHgCornerDrag == null || mRemoteTargetHandles == null
                || mRemoteTargetHandles.length == 0
                || endTarget == GestureState.GestureEndTarget.HOME) {
            return;
        }
        TaskViewSimulator tvs = mRemoteTargetHandles[0].getTaskViewSimulator();
        mHgCornerBackFloating = tvs.hgCornerFloating.value;
        mHgCornerBackSplit = tvs.hgCornerSplit.value;
        if (mHgCornerBackFloating == 0f && mHgCornerBackSplit == 0f) return;
        ObjectAnimator back = mHgCornerBack.animateToValue(1f, 0f);
        back.setDuration(duration);
        back.setInterpolator(interpolator);
        back.start();
    }

    /**
     * HyperGestures: a frame of mHgCornerBack. The end animation applies the window on its own
     * frames too; applying here as well keeps the shrink in step even when the end animation's
     * shift barely changes (a release already at recents' card size).
     */
    private void applyHgCornerBack() {
        if (mRemoteTargetHandles == null) return;
        float f = mHgCornerBack.value;
        for (RemoteTargetGluer.RemoteTargetHandle handle : mRemoteTargetHandles) {
            TaskViewSimulator tvs = handle.getTaskViewSimulator();
            tvs.hgCornerFloating.value = mHgCornerBackFloating * f;
            tvs.hgCornerSplit.value = mHgCornerBackSplit * f;
        }
        applyScrollAndTransform();
    }

    /** HyperGestures: the handler is done: no corner targets, no shrink left on its window. */
    private void resetHgCorner() {
        mHgCornerFollowsFinger = false;
        // Interrupted while the card grew back (a touch soon after a release into recents):
        // finish it now, which applies the unshrunk window while its targets are still valid,
        // and let recents redraw its live tile (its own simulator never had the shrink).
        boolean wasGrowingBack = mHgCornerBack.isAnimating();
        if (wasGrowingBack) mHgCornerBack.finishAnimation();
        if (mHgCornerDrag != null) mHgCornerDrag.dismiss();
        if (mRemoteTargetHandles != null) {
            for (RemoteTargetGluer.RemoteTargetHandle handle : mRemoteTargetHandles) {
                TaskViewSimulator tvs = handle.getTaskViewSimulator();
                tvs.hgCornerFloating.value = 0f;
                tvs.hgCornerSplit.value = 0f;
            }
        }
        if (wasGrowingBack && mRecentsView != null && mRecentsView.getEnableDrawingLiveTile()) {
            mRecentsView.redrawLiveTile();
        }
    }

    // HyperGestures: floating windows shrink to mini windows when the gesture settles in recents or
    // on the home screen, as with MiuiHome (HgFreeformState); not while a corner action is about to
    // open the app in a floating window or a split.
    @Override
    protected void hgOnSettledOnEndTarget(GestureState.GestureEndTarget endTarget) {
        if (HgCornerDrag.hasPendingFloating()) return;
        if (endTarget == GestureState.GestureEndTarget.RECENTS) {
            HgFreeformState.toRecents(mContext, true /* fromGesture */);
        } else if (endTarget == GestureState.GestureEndTarget.HOME) {
            HgFreeformState.toHome(mContext);
        }
    }

    // HyperGestures: released in a corner: the gesture ends in recents, where the corner's action
    // runs on the app's card once the animation has ended (HgCornerDrag).
    @Override
    protected GestureState.GestureEndTarget hgAdjustEndTarget(
            GestureState.GestureEndTarget endTarget) {
        HgCornerDrag.Target corner = mHgCornerReleased;
        mHgCornerReleased = HgCornerDrag.Target.NONE;
        if (corner == HgCornerDrag.Target.NONE) return endTarget;
        HgCornerDrag.setPending(corner, mGestureState.getTopRunningTaskId());
        return GestureState.GestureEndTarget.RECENTS;
    }

    @Override
    protected GestureState.GestureEndTarget getHomeTarget() {
        return HgHomeGesturePolicy.homeTarget(super.getHomeTarget(), mRunningOverHome);
    }

    // HyperGestures: the release itself (not the drag's finish-to-home hint) notes whether the
    // spring back is to be followed by a back key, so an open folder or drawer closes.
    @Override
    protected GestureState.GestureEndTarget getReleasedHomeTarget() {
        GestureState.GestureEndTarget aosp = super.getHomeTarget();
        mHgBackOnRelease = HgHomeGesturePolicy.backOnRelease(aosp, mRunningOverHome,
                mRunningOverHome && HgHomeBack.enabled(mContext));
        return HgHomeGesturePolicy.homeTarget(aosp, mRunningOverHome);
    }

    // HyperGestures: Shell's "will finish to home" hint (in fallback mode, to the recents
    // activity). AOSP sends swipeUpThresholdPassed && getHomeTarget() == HOME, which is always
    // false over the home app (getHomeTarget above). TaskAnimationManager finishes a RECENTS end
    // over a third-party home with toHome=false and relies on that hint being true; with it
    // false, Shell returned to the home app and recents closed again after a hold. Over home the
    // hint is true once the end target is RECENTS (HgHomeGesturePolicy, unit-tested); a release
    // back on the home app (LAST_TASK) keeps it false, so its finish still returns to it.
    @Override
    protected boolean adjustWillFinishToHome(boolean willFinishToHome) {
        return HgHomeGesturePolicy.willFinishToHome(
                willFinishToHome, mRunningOverHome, mGestureState.getEndTarget());
    }

    // HyperGestures: one log line per gesture that ends back on the home app or in recents
    // (phone check B4), the finish-to-recents hint at once for a RECENTS end (not only on the end
    // animation's next frame), and the end duration of a spring back after recents was shown.
    @Override
    protected void animateGestureEnd(
            float startShift,
            float endShift,
            long duration,
            @NonNull Interpolator interpolator,
            @NonNull GestureState.GestureEndTarget endTarget,
            @NonNull PointF velocityPxPerMs) {
        if (mRunningOverHome && endTarget == GestureState.GestureEndTarget.LAST_TASK) {
            Log.i(TAG, "gesture over the home app ends on it (LAST_TASK): no home intent"
                    + (mHgBackOnRelease ? ", back key once it has sprung back" : ""));
            if (mHgBackOnRelease) {
                HgHomeBack.sendWhenSettled(
                        mContext, mTaskAnimationManager, mGestureState.getDisplayId());
            }
            // HyperGestures: the end waits for STATE_RECENTS_SCROLLING_FINISHED, i.e. the end
            // of RecentsView's page snap, which here is the hidden recents behind the home
            // app (PagedView's default snap is 750 ms): on the phone (0.3.9.40) the back key
            // came about 850 ms after the release. Nothing of recents is shown, so the snap
            // is finished at once (abortScrollerAnimation moves to its final position and
            // ends the page transition, which runs the waiting callback).
            // A snap started later (e.g. by recents settling) must not hold it either: the
            // scroll state is marked finished directly as well (runOnceAtState: a later
            // page end setting it again is a no-op).
            if (mRecentsView != null) {
                mRecentsView.abortScrollerAnimation();
            }
            mGestureState.setState(GestureState.STATE_RECENTS_SCROLLING_FINISHED);
        }
        if (mRunningOverHome && endTarget == GestureState.GestureEndTarget.RECENTS) {
            Log.i(TAG, "gesture over the home app ends in recents: finish to recents");
            if (mRecentsAnimationController != null) {
                mRecentsAnimationController.setWillFinishToHome(
                        HgHomeGesturePolicy.willFinishToHome(false, true, endTarget));
            }
        }
        // HyperGestures: back on the home app after recents was shown, the end animation lasts
        // as long as the home window's fade back in (HgHomeGesturePolicy).
        duration = HgHomeGesturePolicy.endAnimationDuration(duration, mRunningOverHome,
                endTarget, mHgHomeAlpha.value, RECENTS_ATTACH_DURATION);
        hgAnimateCornerBack(duration, interpolator, endTarget);
        super.animateGestureEnd(
                startShift, endShift, duration, interpolator, endTarget, velocityPxPerMs);
    }

    @Override
    protected HomeAnimationFactory createHomeAnimationFactory(
            List<IBinder> launchCookies,
            long duration,
            boolean isTargetTranslucent,
            boolean appCanEnterPip,
            RemoteAnimationTarget runningTaskTarget,
            @Nullable TaskView targetTaskView) {
        mAppCanEnterPip = appCanEnterPip;
        if (appCanEnterPip) {
            return new FallbackPipToHomeAnimationFactory();
        }
        mActiveAnimationFactory = new FallbackHomeAnimationFactory(duration, targetTaskView);
        startHomeIntent(
                mActiveAnimationFactory,
                runningTaskTarget,
                targetTaskView,
                targetTaskView != null
                        ? null
                        : launchCookies.stream()
                                .filter(launchCookie ->
                                        ObjectWrapper.<StableViewInfo>unwrap(launchCookie) != null)
                                .findFirst()
                                .orElse(null),
                "FallbackSwipeHandler-home");
        return mActiveAnimationFactory;
    }

    private void startHomeIntent(
            @Nullable FallbackHomeAnimationFactory gestureContractAnimationFactory,
            @Nullable RemoteAnimationTarget runningTaskTarget,
            @Nullable TaskView targetTaskView,
            @Nullable IBinder launchCookie,
            @NonNull String reason) {
        ActivityOptions options = ActivityOptions.makeCustomAnimation(mContext, 0, 0);
        Intent intent = new Intent(mGestureState.getHomeIntent());
        if (gestureContractAnimationFactory != null) {
            TaskKey taskKey = null;
            if (targetTaskView != null) {
                Task firstTask = targetTaskView.getFirstTask();

                taskKey = firstTask == null ? null : firstTask.key;
            } else if (runningTaskTarget != null) {
                TaskInfo taskInfo = runningTaskTarget.taskInfo;

                taskKey = taskInfo == null ? null : new TaskKey(taskInfo);
            }
            if (taskKey != null) {
                gestureContractAnimationFactory.addGestureContract(intent, taskKey, launchCookie);
            }
        }
        startHomeIntentSafely(mContext, intent, options.toBundle(), reason);
    }

    @Override
    public void onTasksAppeared(@NonNull RemoteAnimationTarget[] appearedTaskTargets,
            @Nullable TransitionInfo transitionInfo) {
        if (mActiveAnimationFactory != null && mActiveAnimationFactory.handleHomeTaskAppeared(
                appearedTaskTargets)) {
            mActiveAnimationFactory = null;
            return;
        }

        super.onTasksAppeared(appearedTaskTargets, transitionInfo);
    }

    /**
     * HyperGestures: never with a third-party home, as on stock HyperOS (MiuiHome closes the app
     * as usual and only asks for the fade-in). Here the morph commits the PiP before the home app
     * is started (finishRecentsControllerToHome), so for that moment the app's own task is the
     * top one: on HyperOS the activity left in it resumes, and Facebook then finishes its PiP
     * video; SystemUI's PipTransition finds no PiP change and removes both tasks, so the app
     * was gone from recents.
     */
    @Override
    protected boolean hgSwipesPipToHome() {
        return false;
    }

    @Override
    protected void finishRecentsControllerToHome(Runnable callback) {
        final Runnable recentsCallback;
        if (mAppCanEnterPip) {
            // Make sure Launcher is resumed after auto-enter-pip transition to actually trigger
            // the PiP task appearing.
            recentsCallback = () -> {
                callback.run();
                startHomeIntent(
                        /* gestureContractAnimationFactory= */ null,
                        /* runningTaskTarget= */ null,
                        /* targetTaskView= */ null,
                        /* launchCookie= */ null,
                        /* reason= */ "FallbackSwipeHandler-resumeLauncher");
            };
        } else {
            recentsCallback = callback;
        }
        if (mRecentsView != null) {
            mRecentsView.cleanupRemoteTargets();
        }
        mRecentsAnimationController.finish(
                /* toHome= */mAppCanEnterPip,
                recentsCallback,
                /* sendUserLeaveHint= */ true,
                /* reason= */ new ActiveGestureLog.CompoundString(
                        "FallbackSwipeHandler.finishRecentsControllerToHome"));
    }

    @Override
    protected void switchToScreenshot() {
        if (mRunningOverHome) {
            // When the current task is home, then we don't need to capture anything
            mStateCallback.setStateOnUiThread(STATE_SCREENSHOT_CAPTURED);
        } else {
            super.switchToScreenshot();
        }
    }

    @Override
    protected void notifyGestureAnimationStartToRecents() {
        if (!mRunningOverHome) {
            super.notifyGestureAnimationStartToRecents();
            return;
        }
        if ((DisplayController.getNavigationMode(mContext).hasGestures
                || mGestureState.isTrackpadGesture())
                && mRecentsView != null) {
            mRecentsView.onGestureAnimationStartOnHome(
                    mGestureState.getRunningTask().getPlaceholderGroupedTaskInfo(
                            /* splitTaskIds = */ null));
        }
    }

    private class FallbackPipToHomeAnimationFactory extends HomeAnimationFactory {
        @NonNull
        @Override
        public AnimatorPlaybackController createActivityAnimationToHome() {
            // copied from {@link LauncherSwipeHandlerV2.LauncherHomeAnimationFactory}
            long accuracy = 2 * Math.max(mDp.getDeviceProperties().getWidthPx(), mDp.getDeviceProperties().getHeightPx());
            return mContainer.getStateManager().createAnimationToNewWorkspace(
                    RecentsState.HOME, accuracy, StateAnimationConfig.SKIP_ALL_ANIMATIONS);
        }
    }

    private class FallbackHomeAnimationFactory extends HomeAnimationFactory
            implements Consumer<Message> {
        private final Rect mTempRect = new Rect();
        private final TransformParams mHomeAlphaParams = new TransformParams();
        private final AnimatedFloat mHomeAlpha;

        private final AnimatedFloat mVerticalShiftForScale = new AnimatedFloat();
        private final AnimatedFloat mRecentsAlpha = new AnimatedFloat();

        private final RectF mTargetRect = new RectF();
        private SurfaceControl mSurfaceControl;

        private boolean mAnimationFinished;
        private Message mOnFinishCallback;

        private final long mDuration;
        @Nullable private final TaskView mTargetTaskView;

        private RectFSpringAnim mSpringAnim;
        FallbackHomeAnimationFactory(long duration, @Nullable TaskView targetTaskView) {
            mDuration = duration;
            mTargetTaskView = targetTaskView;

            if (mRunningOverHome) {
                float currentShift = getCurrentShiftValue();
                mHomeAlpha = new AnimatedFloat();
                mHomeAlpha.value = Utilities.boundToRange(1 - currentShift, 0, 1);
                mVerticalShiftForScale.value = currentShift;
                runActionOnRemoteHandles(remoteTargetHandle ->
                        remoteTargetHandle.getTransformParams().setHomeBuilderProxy(
                                FallbackHomeAnimationFactory.this
                                        ::updateHomeActivityTransformDuringHomeAnim));
            } else {
                mHomeAlpha = new AnimatedFloat(this::updateHomeAlpha);
                mHomeAlpha.value = 0;
                mHomeAlphaParams.setHomeBuilderProxy(
                        this::updateHomeActivityTransformDuringHomeAnim);
            }

            mRecentsAlpha.value = 1;
            runActionOnRemoteHandles(remoteTargetHandle ->
                    remoteTargetHandle.getTransformParams().setBaseBuilderProxy(
                            FallbackHomeAnimationFactory.this
                                    ::updateRecentsActivityTransformDuringHomeAnim));
        }

        @Nullable
        @Override
        public TaskView getTargetTaskView() {
            return mTargetTaskView;
        }

        @NonNull
        @Override
        public RectF getWindowTargetRect() {
            if (mTargetRect.isEmpty()) {
                mTargetRect.set(super.getWindowTargetRect());
            }
            return mTargetRect;
        }

        private void updateRecentsActivityTransformDuringHomeAnim(SurfaceProperties builder,
                RemoteAnimationTarget app, TransformParams params) {
            builder.setAlpha(mRecentsAlpha.value);
        }

        private void updateHomeActivityTransformDuringHomeAnim(SurfaceProperties builder,
                RemoteAnimationTarget app, TransformParams params) {
            setHomeScaleAndAlpha(builder, app, mVerticalShiftForScale.value, mHomeAlpha.value);
        }

        @NonNull
        @Override
        public AnimatorPlaybackController createActivityAnimationToHome() {
            PendingAnimation pa = new PendingAnimation(mDuration);
            pa.setFloat(mRecentsAlpha, AnimatedFloat.VALUE, 0, ACCELERATE);
            return pa.createPlaybackController();
        }

        private void updateHomeAlpha() {
            if (mHomeAlphaParams.getTargetSet() != null) {
                mHomeAlphaParams.applySurfaceParams(
                        mHomeAlphaParams.createSurfaceParams(BuilderProxy.NO_OP));
            }
        }

        public boolean handleHomeTaskAppeared(RemoteAnimationTarget[] appearedTaskTargets) {
            RemoteAnimationTarget appearedTaskTarget = appearedTaskTargets[0];
            if (appearedTaskTarget.windowConfiguration.getActivityType() == ACTIVITY_TYPE_HOME) {
                RemoteAnimationTargets targets = new RemoteAnimationTargets(
                        new RemoteAnimationTarget[] {appearedTaskTarget},
                        new RemoteAnimationTarget[0], new RemoteAnimationTarget[0],
                        appearedTaskTarget.mode);
                mHomeAlphaParams.setTargetSet(targets);
                updateHomeAlpha();
                return true;
            }
            return false;
        }

        @Override
        public void playAtomicAnimation(float velocity) {
            ObjectAnimator alphaAnim = mHomeAlpha.animateToValue(mHomeAlpha.value, 1);
            alphaAnim.setDuration(mDuration).setInterpolator(ACCELERATE);
            alphaAnim.start();
            if (!mRunningOverHome) hgFadeRecentsBlurOut(mDuration); // HyperGestures

            if (mRunningOverHome) {
                // Spring back launcher scale
                new SpringAnimationBuilder(mContext)
                        .setStartValue(mVerticalShiftForScale.value)
                        .setEndValue(0)
                        .setStartVelocity(-velocity / mTransitionDragLength)
                        .setMinimumVisibleChange(1f / mDp.getDeviceProperties().getHeightPx())
                        .setDampingRatio(0.6f)
                        .setStiffness(800)
                        .build(mVerticalShiftForScale, AnimatedFloat.VALUE)
                        .start();
            }
        }

        @Override
        public void setAnimation(RectFSpringAnim anim) {
            mSpringAnim = anim;
            mSpringAnim.addAnimatorListener(forEndCallback(this::onRectAnimationEnd));
        }

        private void onRectAnimationEnd() {
            mAnimationFinished = true;
            maybeSendEndMessage();
        }

        private void maybeSendEndMessage() {
            if (mAnimationFinished && mOnFinishCallback != null) {
                try {
                    mOnFinishCallback.replyTo.send(mOnFinishCallback);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error sending icon position", e);
                }
            }
        }

        @Override
        public void accept(Message msg) {
            try {
                Bundle data = msg.getData();
                RectF position = data.getParcelable(EXTRA_ICON_POSITION);
                if (!position.isEmpty()) {
                    mSurfaceControl = data.getParcelable(EXTRA_ICON_SURFACE);
                    mTargetRect.set(position);
                    if (mSpringAnim != null) {
                        mSpringAnim.onTargetPositionChanged();
                    }
                }
                mOnFinishCallback = data.getParcelable(EXTRA_ON_FINISH_CALLBACK);
                maybeSendEndMessage();
            } catch (Exception e) {
                // Ignore
            }
        }

        @Override
        public void update(RectF currentRect, float progress, float radius, int overlayAlpha) {
            if (mSurfaceControl != null) {
                currentRect.roundOut(mTempRect);
                Transaction t = new Transaction();
                try {
                    t.setGeometry(mSurfaceControl, null, mTempRect, Surface.ROTATION_0);
                    t.apply();
                } catch (RuntimeException e) {
                    // Ignore
                }
            }
        }

        private void addGestureContract(
                @NonNull Intent intent,
                @NonNull TaskKey key,
                @Nullable IBinder launchCookie) {
            if (mRunningOverHome) {
                return;
            }
            if (key.getComponent() == null) {
                return;
            }
            if (sMessageReceiver == null) {
                sMessageReceiver = new StaticMessageReceiver();
            }
            Bundle gestureNavContract = new Bundle();

            gestureNavContract.putBoolean(
                    EXTRA_ENABLE_GESTURE_CONTRACT, mRemoteTargetHandles.length <= 1);
            gestureNavContract.putBinder(EXTRA_LAUNCH_COOKIE, launchCookie);
            gestureNavContract.putParcelable(EXTRA_COMPONENT_NAME, key.getComponent());
            gestureNavContract.putParcelable(EXTRA_USER, UserHandle.of(key.userId));
            gestureNavContract.putParcelable(
                    EXTRA_REMOTE_CALLBACK, sMessageReceiver.newCallback(this));
            intent.putExtra(EXTRA_GESTURE_CONTRACT, gestureNavContract);
        }
    }

    private static class StaticMessageReceiver implements Handler.Callback {

        private final Messenger mMessenger =
                new Messenger(new Handler(Looper.getMainLooper(), this));

        private ParcelUuid mCurrentUID = new ParcelUuid(UUID.randomUUID());
        private WeakReference<Consumer<Message>> mCurrentCallback = new WeakReference<>(null);

        public Message newCallback(Consumer<Message> callback) {
            mCurrentUID = new ParcelUuid(UUID.randomUUID());
            mCurrentCallback = new WeakReference<>(callback);

            Message msg = Message.obtain();
            msg.replyTo = mMessenger;
            msg.obj = mCurrentUID;
            return msg;
        }

        @Override
        public boolean handleMessage(@NonNull Message message) {
            if (mCurrentUID.equals(message.obj)) {
                Consumer<Message> consumer = mCurrentCallback.get();
                if (consumer != null) {
                    consumer.accept(message);
                    return true;
                }
            }
            return false;
        }
    }
}
