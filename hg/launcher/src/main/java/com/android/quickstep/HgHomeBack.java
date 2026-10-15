// HyperGestures: the back key sent after a released swipe up over the home app (the third-party
// HOME), so it closes an open folder or drawer as a back press does, instead of nothing happening
// (HgHomeGesturePolicy.backOnRelease). It is sent once the window has sprung back and Shell's
// recents animation is cleaned up, so the key reaches the home app's window
// (HgHomeGesturePolicy.homeBackStep). No polling: it waits for TaskAnimationManager's clean-up
// (a one-shot listener), then HOME_BACK_SETTLE_MS, and gives up after HOME_BACK_TIMEOUT_MS; only
// after such a swipe. Same path as edge back's default: SystemUI's onKeyEvent(KEYCODE_BACK). Off
// with `settings put global hypergestures_home_back 0`.
package com.android.quickstep;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

public final class HgHomeBack {
    private HgHomeBack() {}

    private static final String TAG = "HgHomeBack";
    static final String SETTING = "hypergestures_home_back";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Settings.Global hypergestures_home_back, read once per released swipe over home. */
    public static boolean enabled(Context context) {
        return HgHomeGesturePolicy.homeBackEnabled(
                Settings.Global.getString(context.getContentResolver(), SETTING));
    }

    /** Main thread. Sends one back key to displayId once this gesture's recents animation ended. */
    public static void sendWhenSettled(Context context, TaskAnimationManager tam, int displayId) {
        new Waiter(context.getApplicationContext(), tam, displayId,
                SystemClock.uptimeMillis()).start();
    }

    /** One released swipe's wait, on the main thread: each event re-runs homeBackStep. */
    private static final class Waiter {
        private final Context mContext;
        private final TaskAnimationManager mTam;
        private final int mDisplayId;
        private final long mReleasedAt;
        /** The timeout and the settle delay: both just look again. */
        private final Runnable mStep = this::step;
        /** TaskAnimationManager's one-shot clean-up listener. */
        private final Runnable mOnCleanUp = this::onCleanUp;
        private boolean mListening;
        private boolean mDone;

        Waiter(Context context, TaskAnimationManager tam, int displayId, long releasedAt) {
            mContext = context;
            mTam = tam;
            mDisplayId = displayId;
            mReleasedAt = releasedAt;
        }

        void start() {
            MAIN.postDelayed(mStep, HgHomeGesturePolicy.HOME_BACK_TIMEOUT_MS + 1);
            step();
        }

        private void onCleanUp() {
            mListening = false;
            step(); // within the clean-up: at most schedules the settle delay
        }

        private void step() {
            if (mDone) return;
            long now = SystemClock.uptimeMillis();
            long cleanedUpAt = mTam.getRecentsCleanedUpAtMs();
            switch (HgHomeGesturePolicy.homeBackStep(now, mReleasedAt,
                    mTam.isRecentsAnimationRunning(), cleanedUpAt)) {
                case WAIT:
                    if (cleanedUpAt >= mReleasedAt) {
                        // Cleaned up: the rest of the settle time, then look again.
                        MAIN.postDelayed(mStep, Math.max(1,
                                cleanedUpAt + HgHomeGesturePolicy.HOME_BACK_SETTLE_MS - now));
                    } else if (!mListening) {
                        mListening = true;
                        mTam.addOneShotCleanUpListener(mOnCleanUp);
                    }
                    return;
                case SEND:
                    finish();
                    try {
                        SystemUiProxy.INSTANCE.get(mContext).onKeyEvent(
                                KeyEvent.KEYCODE_BACK, mDisplayId);
                        Log.i(TAG, "swipe up over the home app: back sent after "
                                + (now - mReleasedAt) + " ms");
                    } catch (RuntimeException e) {
                        Log.w(TAG, "back after a swipe up over the home app failed", e);
                    }
                    return;
                case GIVE_UP:
                default:
                    finish();
                    Log.i(TAG, "swipe up over the home app: back not sent (recents still"
                            + " running or a new gesture began)");
            }
        }

        private void finish() {
            mDone = true;
            MAIN.removeCallbacks(mStep);
            if (mListening) {
                mListening = false;
                mTam.removeOneShotCleanUpListener(mOnCleanUp);
            }
        }
    }
}
