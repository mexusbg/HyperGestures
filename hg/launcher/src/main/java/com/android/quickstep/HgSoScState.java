// HyperGestures: which two apps are in HyperOS's split screen (SoSc), for the swipe-up gesture.
//
// AOSP's launcher learns the running split pair from ISplitScreenListener (TopTaskTracker) and its
// layout from KEY_EXTRA_SPLIT_BOUNDS in the recents animation's extras. HyperOS's SoSc calls neither:
// the recents animation has both halves as separate targets but key_SplitBounds is always null
// (Shell looks it up for task -1), so a swipe-up from a split animated each half as its own
// fullscreen card. HyperOS's own feed is IMultiTaskingStateManager (onInitialize bundle key
// extra_shell_miui_multitasking_state): its listener's code 1, onSoScStateChanged, reports the
// state (2 = both halves filled) and the two top tasks. Shell forwards SoSc state to it only once an
// ISoScSplitScreenListener is registered too (a dummy here; Shell never calls it). Both interfaces
// keep one listener each (MiuiHome's, when it runs; it is disabled with this module).
//
// With the pair known, TopTaskTracker reports it (getRunningSplitTaskIds: the gesture's live tile
// becomes a split card) and RecentsAnimationCallbacks adds the missing SplitBounds from the two
// targets (splitBoundsFor), so the gesture takes AOSP's split path end to end. Returning to the
// app finishes the animation with toHome=false, which makes Shell restore the split by itself.
//
// Codes and parcels are those of the phone's own Miui-WindowManager-Shell (research:
// docs/research/2026-10-08-sosc-split-gesture.md): each code is read from it at runtime and the
// binders are used only when every call was confirmed there (HgShellCalls, any firmware) and they
// name the expected descriptors (as HgSoScSplitScreen). Off:
// Settings.Global hypergestures_split_gesture=0: no pair and no added bounds, so each half animates
// as before (HyperOS's divider background is still kept out of the app targets; it only exists in
// a split).
package com.android.quickstep;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.RemoteAnimationTarget;

import androidx.annotation.Nullable;

import com.android.launcher3.util.Executors;
import com.android.wm.shell.shared.split.SplitBounds;
import com.android.wm.shell.shared.split.SplitScreenConstants;

public final class HgSoScState {
    private static final String TAG = "HgSplit";

    static final String MTSM_KEY = "extra_shell_miui_multitasking_state";
    static final String MTSM_DESC =
            "com.android.wm.shell.multitasking.common.IMultiTaskingStateManager";
    static final String LISTENER_DESC =
            "com.android.wm.shell.multitasking.common.IMultiTaskingStateChangeListener";
    static final String SOSC_DESC = "com.android.wm.shell.sosc.ISoScSplitScreen";
    static final String SOSC_LISTENER_DESC = "com.android.wm.shell.sosc.ISoScSplitScreenListener";
    /**
     * IMultiTaskingStateChangeListener's last code on OS4.0.0.11/.13 (all oneway; only the state
     * code is used here): the rest are swallowed up to this or the state code, if higher.
     */
    private static final int LISTENER_LAST_CODE = 16;
    /** SoSc state: both halves filled (-1 off, 0/1 one app with the home screen). */
    static final int STATE_FULL = 2;

    /** The full-split pair, top/left first; immutable, read on binder threads and on main. */
    static final class Pair {
        final int leftTopTaskId;
        final int rightBottomTaskId;
        final Rect leftTop;
        final Rect rightBottom;

        Pair(int leftTopTaskId, int rightBottomTaskId, Rect leftTop, Rect rightBottom) {
            this.leftTopTaskId = leftTopTaskId;
            this.rightBottomTaskId = rightBottomTaskId;
            this.leftTop = new Rect(leftTop);
            this.rightBottom = new Rect(rightBottom);
        }

        boolean sameTasks(@Nullable Pair other) {
            return other != null && other.leftTopTaskId == leftTopTaskId
                    && other.rightBottomTaskId == rightBottomTaskId;
        }
    }

    /** Where a pair change goes: TopTaskTracker, on the main thread (-1 ids clear it). */
    interface PairSink {
        void post(int leftTopTaskId, int rightBottomTaskId);
    }

    /**
     * The followed state and pair, and the listener they come from. One lock for the binder
     * thread's reports and unregister: a report still in flight when unregister runs either lands
     * before it (and unregister's clear is posted after its pair) or finds its listener gone; it
     * can never post its pair after the clear, which would leave TopTaskTracker a stale pair.
     */
    static final class Feed {
        @Nullable private Object mSource;
        @Nullable private volatile Pair mPair;
        /** SoSc's last reported state (-1 when not followed). */
        private volatile int mLastState = -1;

        synchronized void start(Object source) {
            mSource = source;
        }

        /** Stops following and forgets the pair; always clears TopTaskTracker's, given a sink. */
        synchronized void stop(@Nullable PairSink sink) {
            mSource = null;
            mPair = null;
            mLastState = -1;
            if (sink != null) sink.post(-1, -1);
        }

        /**
         * A state report from {@code source}'s listener: records it, and posts the pair's ids when
         * they changed. @return whether it posted (never once {@code source} is not the followed
         * listener: unregistered while the report was in flight).
         */
        synchronized boolean onState(Object source, int state, @Nullable Pair pair,
                PairSink sink) {
            if (source != mSource) return false;
            mLastState = state;
            Pair old = mPair;
            mPair = pair;
            if (pair == null ? old == null : pair.sameTasks(old)) return false;
            sink.post(pair == null ? -1 : pair.leftTopTaskId,
                    pair == null ? -1 : pair.rightBottomTaskId);
            return true;
        }

        @Nullable
        Pair pair() {
            return mPair;
        }

        int lastState() {
            return mLastState;
        }

        void forgetPair() {
            mPair = null;
        }
    }

    private static final Feed FEED = new Feed();
    @Nullable private static volatile HgSoScState sCurrent;
    /** For unregister's clear when nothing is followed (register's own unregister). */
    @Nullable private static volatile Context sAppContext;

    private final Context mContext;
    private final IBinder mManager;
    private final IBinder mSoSc;
    /** This firmware's codes (HgShellInterfaces), confirmed before this instance exists. */
    private final int mMtsmRegister;
    private final int mMtsmUnregister;
    private final int mSoScRegister;
    private final int mSoScUnregister;
    private final int mOnStateCode;
    /** Never called by Shell; registering it switches Shell's state forward on. */
    private final Binder mSoScDummy = new Binder(SOSC_LISTENER_DESC);
    private final Binder mListener = new Binder(LISTENER_DESC) {
        @Override
        protected boolean onTransact(int code, Parcel data, @Nullable Parcel reply, int flags)
                throws RemoteException {
            if (code == mOnStateCode) {
                data.enforceInterface(LISTENER_DESC);
                Rect root = data.readTypedObject(Rect.CREATOR);
                Rect leftTop = data.readTypedObject(Rect.CREATOR);
                Rect rightBottom = data.readTypedObject(Rect.CREATOR);
                int state = data.readInt();
                ActivityManager.RunningTaskInfo leftTopTask =
                        data.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
                ActivityManager.RunningTaskInfo rightBottomTask =
                        data.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
                boolean minimized = data.readBoolean();
                onState(state, leftTopTask, rightBottomTask, leftTop, rightBottom, minimized);
                return true;
            }
            if (code >= 1 && code <= Math.max(LISTENER_LAST_CODE, mOnStateCode)) {
                return true; // oneway notifications we do not use
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private HgSoScState(Context context, IBinder manager, IBinder sosc,
            HgShellCalls.Result calls) {
        mContext = context.getApplicationContext();
        mManager = manager;
        mSoSc = sosc;
        mMtsmRegister = calls.code(HgShellCalls.Call.MTSM_REGISTER_LISTENER);
        mMtsmUnregister = calls.code(HgShellCalls.Call.MTSM_UNREGISTER_LISTENER);
        mSoScRegister = calls.code(HgShellCalls.Call.SOSC_REGISTER_LISTENER);
        mSoScUnregister = calls.code(HgShellCalls.Call.SOSC_UNREGISTER_LISTENER);
        mOnStateCode = calls.code(HgShellCalls.Call.MTSM_ON_SOSC_STATE_CHANGED);
    }

    /**
     * SystemUiProxy.setProxy: starts following HyperOS's split state from this onInitialize
     * bundle (replacing an earlier registration), or does nothing where it can't.
     */
    public static synchronized void register(Context context, @Nullable Bundle params) {
        sAppContext = context.getApplicationContext();
        unregister();
        if (params == null || !HgRecentsSettings.isOn(context, HgRecentsSettings.SPLIT_GESTURE)) {
            return;
        }
        IBinder manager = params.getBinder(MTSM_KEY);
        IBinder sosc = params.getBinder(HgSoScSplitScreen.BUNDLE_KEY);
        HgShellCalls.Result calls = HgShellInterfaces.get();
        boolean confirmed = calls.usable(HgShellCalls.Feature.SPLIT_STATE);
        if (manager == null || sosc == null || !confirmed
                || !MTSM_DESC.equals(descriptorOf(manager))
                || !SOSC_DESC.equals(descriptorOf(sosc))) {
            Log.i(TAG, "split state not followed (binders " + (manager != null) + "/"
                    + (sosc != null) + (confirmed ? ""
                    : ", " + calls.reason(HgShellCalls.Feature.SPLIT_STATE)) + ")");
            return;
        }
        HgSoScState state = new HgSoScState(context, manager, sosc, calls);
        sCurrent = state; // before registering: Shell replays the current state at once
        FEED.start(state);
        oneway(manager, state.mMtsmRegister, MTSM_DESC, state.mListener);
        oneway(sosc, state.mSoScRegister, SOSC_DESC, state.mSoScDummy);
        Log.i(TAG, "following HyperOS's split state");
    }

    /**
     * SystemUiProxy.clearProxy (through register): stops following, forgets the pair, and always
     * clears TopTaskTracker's, after any pair the listener posted (FEED's lock).
     */
    public static synchronized void unregister() {
        HgSoScState state = sCurrent;
        sCurrent = null;
        Context context = state != null ? state.mContext : sAppContext;
        FEED.stop(context == null ? null : mainSink(context));
        if (state == null) return;
        oneway(state.mManager, state.mMtsmUnregister, MTSM_DESC, state.mListener);
        oneway(state.mSoSc, state.mSoScUnregister, SOSC_DESC, state.mSoScDummy);
    }

    private static PairSink mainSink(Context context) {
        return (lt, rb) -> Executors.MAIN_EXECUTOR.execute(() ->
                TopTaskTracker.INSTANCE.get(context).hgSetSoScPair(lt, rb));
    }

    /** Binder thread. */
    private void onState(int state, @Nullable ActivityManager.RunningTaskInfo leftTopTask,
            @Nullable ActivityManager.RunningTaskInfo rightBottomTask, @Nullable Rect leftTop,
            @Nullable Rect rightBottom, boolean minimized) {
        Pair pair = (state == STATE_FULL && !minimized && leftTopTask != null
                && rightBottomTask != null && leftTop != null && rightBottom != null
                && isPair(leftTopTask.taskId, rightBottomTask.taskId, leftTop, rightBottom))
                ? new Pair(leftTopTask.taskId, rightBottomTask.taskId, leftTop, rightBottom)
                : null;
        // Posts nothing once unregistered while this call was in flight (FEED's lock).
        if (FEED.onState(this, state, pair, mainSink(mContext))) {
            Log.i(TAG, pair == null ? "split state " + state + ": no pair"
                    : "split pair " + pair.leftTopTaskId + " / " + pair.rightBottomTaskId);
        }
    }

    /** Whether HyperOS's split screen is on (one app with home, or both halves), if followed. */
    public static boolean inSplit() {
        int state = FEED.lastState();
        return state == 0 || state == 1 || state == STATE_FULL;
    }

    /** Whether HyperOS's split screen holds one app with the home screen, if followed. */
    public static boolean inOneAppSplit() {
        return isOneAppState(FEED.lastState());
    }

    /** SoSc's states 0 and 1: one app in the top/left or the bottom/right half, the other empty. */
    static boolean isOneAppState(int state) {
        return state == 0 || state == 1;
    }

    /**
     * TopTaskTracker: an app outside the pair came to front fullscreen, so the split is over; the
     * next state broadcast, even an identical one, sets it again.
     */
    static void forgetPair() {
        FEED.forgetPair();
    }

    /** Two distinct real tasks, the first above or left of the second. */
    static boolean isPair(int leftTopTaskId, int rightBottomTaskId, Rect leftTop,
            Rect rightBottom) {
        return leftTopTaskId > 0 && rightBottomTaskId > 0 && leftTopTaskId != rightBottomTaskId
                && !leftTop.isEmpty() && !rightBottom.isEmpty()
                && (rightBottom.top >= leftTop.bottom || rightBottom.left >= leftTop.right);
    }

    /**
     * RecentsAnimationCallbacks.onAnimationStart (binder thread): the SplitBounds HyperOS leaves
     * out, from the closing targets of the known pair; null if the pair is not both there.
     */
    @Nullable
    public static SplitBounds splitBoundsFor(RemoteAnimationTarget[] targets) {
        Pair pair = FEED.pair();
        if (pair == null) return null;
        Rect leftTop = null;
        Rect rightBottom = null;
        for (RemoteAnimationTarget t : targets) {
            if (t.mode != RemoteAnimationTarget.MODE_CLOSING) continue;
            if (t.taskId == pair.leftTopTaskId) leftTop = boundsOf(t, pair.leftTop);
            else if (t.taskId == pair.rightBottomTaskId) rightBottom = boundsOf(t, pair.rightBottom);
        }
        if (leftTop == null || rightBottom == null
                || !isPair(pair.leftTopTaskId, pair.rightBottomTaskId, leftTop, rightBottom)) {
            return null;
        }
        boolean stacked = rightBottom.top >= leftTop.bottom;
        int snap = snapPosition(stacked ? leftTop.height() : leftTop.width(),
                stacked ? rightBottom.height() : rightBottom.width());
        Log.d(TAG, "split bounds for the gesture: " + leftTop + " / " + rightBottom);
        return new SplitBounds(leftTop, rightBottom, pair.leftTopTaskId, pair.rightBottomTaskId,
                snap);
    }

    private static Rect boundsOf(RemoteAnimationTarget t, Rect fallback) {
        Rect r = t.screenSpaceBounds;
        return r != null && !r.isEmpty() ? new Rect(r) : new Rect(fallback);
    }

    /** AOSP's snap position closest to a split of {@code leftTop} : {@code rightBottom}. */
    static int snapPosition(int leftTop, int rightBottom) {
        float ratio = leftTop / (float) Math.max(1, leftTop + rightBottom);
        if (ratio < 0.2f) return SplitScreenConstants.SNAP_TO_2_10_90;
        if (ratio < 0.4f) return SplitScreenConstants.SNAP_TO_2_33_66;
        if (ratio > 0.8f) return SplitScreenConstants.SNAP_TO_2_90_10;
        if (ratio > 0.6f) return SplitScreenConstants.SNAP_TO_2_66_33;
        return SplitScreenConstants.SNAP_TO_2_50_50;
    }

    @Nullable
    private static String descriptorOf(IBinder b) {
        try {
            return b.getInterfaceDescriptor();
        } catch (RemoteException | RuntimeException e) {
            return null;
        }
    }

    private static void oneway(IBinder b, int code, String descriptor, IBinder arg) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeStrongBinder(arg);
            b.transact(code, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, descriptor + " " + code + " failed: " + e);
        } finally {
            data.recycle();
        }
    }
}
