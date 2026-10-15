// HyperGestures: what a recents card swiped down does, chosen at each touch-down from
// Settings.Global hypergestures_card_lock_swipe (the page's "Swipe down to lock"): lock or
// unlock its app (HgTaskLockSwipeController) unless it is "0", then AOSP's launch
// (TaskViewLaunchTouchController). Both controllers are made with the drag layer's controllers;
// reading the setting per touch-down (a cached Settings read, as edge back does) makes the
// switch apply to the next swipe, without a reboot. A gesture keeps the controller its
// touch-down chose.
package com.android.quickstep;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;

import com.android.launcher3.util.TouchController;

public final class HgSwipeDownController implements TouchController {
    private static final String TAG = "HgSwipeDown";

    private final Context mContext;
    private final TouchController mLock;
    private final TouchController mLaunch;
    private TouchController mActive;

    public HgSwipeDownController(Context context, TouchController lock, TouchController launch) {
        mContext = context;
        mLock = lock;
        mLaunch = launch;
        mActive = choose(readLockOn(), lock, launch);
    }

    /** The controller for a gesture: {@code lock} while the switch is on, else {@code launch}. */
    static <C> C choose(boolean lockOn, C lock, C launch) {
        return lockOn ? lock : launch;
    }

    private boolean readLockOn() {
        try {
            return HgRecentsSettings.isOn(mContext, HgRecentsSettings.CARD_LOCK_SWIPE);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read " + HgRecentsSettings.CARD_LOCK_SWIPE, e);
            return mActive != mLaunch; // keep the last choice (on before any)
        }
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mActive = choose(readLockOn(), mLock, mLaunch);
        }
        return mActive.onControllerInterceptTouchEvent(ev);
    }

    @Override
    public boolean onControllerTouchEvent(MotionEvent ev) {
        return mActive.onControllerTouchEvent(ev);
    }

    @Override
    public String dump() {
        return "HgSwipeDownController -> " + mActive.dump();
    }

    @Override
    public void onTouchControllerDestroyed() {
        mLock.onTouchControllerDestroyed();
        mLaunch.onTouchControllerDestroyed();
    }
}
