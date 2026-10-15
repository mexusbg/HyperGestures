// HyperGestures: one edge-back gesture. Feeds the touch stream to Launcher3's
// EdgeBackGestureHandler (AOSP SystemUI's handler and arrow, forked for the tutorial), pilfers the
// pointers once the finger moves sideways, and on commit sends BACK through SystemUI.
package com.android.quickstep.interaction;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.quickstep.InputConsumer;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.TaskAnimationManager;
import com.android.systemui.shared.system.InputMonitorCompat;

final class EdgeBackInputConsumer implements InputConsumer,
        EdgeBackGestureHandler.BackGestureAttemptCallback {
    private static final String TAG = "HgEdgeBack";
    // HyperGestures: the key lives in HgBackPolicy, shared with HgLogCollector's key list.
    private static final String BACK_PATH_SETTING = HgBackPolicy.BACK_PATH_SETTING;

    private final Context mContext;
    private final int mDisplayId;
    private final EdgeBackGestureHandler mHandler;
    private final EdgeBackOverlay mOverlay;
    private final InputMonitorCompat mMonitor;
    private final TaskAnimationManager mTaskAnimationManager;
    private boolean mPilfered;
    /** Set after a RuntimeException: this consumer ignores the rest of its gesture. */
    private boolean mFailed;

    EdgeBackInputConsumer(Context context, int displayId, EdgeBackGestureHandler handler,
            EdgeBackOverlay overlay, InputMonitorCompat monitor, TaskAnimationManager tam) {
        mContext = context;
        mDisplayId = displayId;
        mHandler = handler;
        mOverlay = overlay;
        mMonitor = monitor;
        mTaskAnimationManager = tam;
        mHandler.registerBackGestureAttemptCallback(this);
    }

    @Override public int getType() { return TYPE_HG_BACK; }
    @Override public int getDisplayId() { return mDisplayId; }

    @Override
    public void onMotionEvent(MotionEvent ev) {
        if (mFailed) return;
        try {
            mHandler.onTouch(null, ev);
            // Take the pointers, and show the overlay window, only once the AOSP handler has
            // accepted the swipe; it rejects vertical-first moves, long presses and a second
            // finger for good.
            if (!mPilfered && mHandler.isBackGestureInProgress()) {
                mMonitor.pilferPointers();
                mPilfered = true;
                mOverlay.arm();
            }
        } catch (RuntimeException e) {
            fail("motion event failed", e);
        }
    }

    @Override
    public void onBackGestureAttempted(EdgeBackGestureHandler.BackGestureResult result) {
        if (mFailed) return;
        try {
            boolean completed =
                    result == EdgeBackGestureHandler.BackGestureResult.BACK_COMPLETED_FROM_LEFT
                    || result == EdgeBackGestureHandler.BackGestureResult.BACK_COMPLETED_FROM_RIGHT;
            if (!completed) return;
            if (!HgBackPolicy.canCommit(mTaskAnimationManager.isRecentsAnimationRunning(),
                    SystemClock.uptimeMillis(), mTaskAnimationManager.getRecentsCleanedUpAtMs())) {
                Log.w(TAG, "back not sent: recents animation running or just finished");
                return;
            }
            HgBackPolicy.BackPath path = HgBackPolicy.backPath(
                    Settings.Global.getString(mContext.getContentResolver(), BACK_PATH_SETTING));
            SystemUiProxy proxy = SystemUiProxy.INSTANCE.get(mContext);
            switch (path) {
                case NULL_EVENT:
                    proxy.onBackEvent(null, mDisplayId);
                    break;
                case EVENT:
                    long t = SystemClock.uptimeMillis();
                    proxy.onBackEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_BACK, 0), mDisplayId);
                    proxy.onBackEvent(new KeyEvent(t, SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), mDisplayId);
                    break;
                case KEY:
                default:
                    proxy.onKeyEvent(KeyEvent.KEYCODE_BACK, mDisplayId);
                    break;
            }
            Log.d(TAG, "back sent " + result + " via " + path);
        } catch (RuntimeException e) {
            fail("back commit failed", e);
        }
    }

    /** One exception in the AOSP panel code must not take down every gesture. */
    private void fail(String what, RuntimeException e) {
        mFailed = true;
        Log.e(TAG, what + "; edge back off until the service restarts", e);
        HgEdgeBack.disableForSession();
    }
}
