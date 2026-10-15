/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep.fallback;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.android.launcher3.statemanager.StatefulContainer;
import com.android.launcher3.uioverrides.touchcontrollers.TaskViewDismissTouchController;
import com.android.launcher3.uioverrides.touchcontrollers.TaskViewLaunchTouchController;
import com.android.launcher3.util.TouchController;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.HgSwipeDownController;
import com.android.quickstep.HgTaskLockSwipeController;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;

/**
 * Drag layer for fallback recents activity
 */
public class RecentsDragLayer<T extends Context & RecentsViewContainer
        & StatefulContainer<RecentsState>> extends BaseDragLayer<T> {

    // HyperGestures: the current gesture started during a Clear all that returns to the running
    // app (RecentsView.isHgClearAllRunning) and is swallowed whole, down to its up or cancel, so
    // no card, card swipe or button sees it.
    private boolean mHgSwallowGesture;

    public RecentsDragLayer(Context context, AttributeSet attrs) {
        super(context, attrs, 1 /* alphaChannelCount */);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            RecentsView<?, ?> recentsView = mContainer.getOverviewPanel();
            mHgSwallowGesture = recentsView != null && recentsView.isHgClearAllRunning();
        }
        if (mHgSwallowGesture) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                mHgSwallowGesture = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void recreateControllers() {
        super.recreateControllers();
        // HyperGestures: a card swiped down locks or unlocks its app, as in HyperOS's recents
        // (HgTaskLockSwipeController; tapping still launches). AOSP's swipe-down launch when
        // hypergestures_card_lock_swipe is 0, read at each touch-down (HgSwipeDownController).
        TouchController swipeDown = new HgSwipeDownController(mContainer,
                new HgTaskLockSwipeController<>(mContainer),
                new TaskViewLaunchTouchController<>(mContainer));
        // HyperGestures: in MIUI's vertical layout a card is dismissed sideways, either way: one
        // dismiss controller per side (the right-hand one does nothing in the other layouts).
        mControllers = new TouchController[]{
                swipeDown,
                new TaskViewDismissTouchController<>(mContainer, -1),
                new TaskViewDismissTouchController<>(mContainer, 1),
                new FallbackNavBarTouchController(mContainer)
        };
    }
}
