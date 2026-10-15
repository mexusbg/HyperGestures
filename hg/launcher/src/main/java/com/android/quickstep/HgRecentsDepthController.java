// HyperGestures: the recents blur's DepthController (HgRecentsBlur), with two changes to AOSP's
// (and, as recents opens, the owner's blur switch read again: HgBlurSupport.refreshRecentsBlur).
//
// 1. No wallpaper zoom. AOSP zooms the wallpaper out with the depth; HyperOS's recents does not,
//    and the zoom showed behind Nova on every swipe up over the home screen (owner, 2026-10-07:
//    "animation starts when issuing the home gesture on Nova"), since every fallback recents state
//    has 70% depth and recents is started under the home app for the gesture.
// 2. The blur follows how much of recents can be seen (setRecentsVisibility): during a swipe over
//    the home app recents stays hidden behind it until a hold or quick switch attaches it
//    (FallbackSwipeHandler fades the home window out then), so the blur fades in with that and is
//    0 while the home screen is all there is to see.
package com.android.quickstep;

import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.util.ListenableRef;
import com.android.quickstep.fallback.RecentsState;

import java.lang.ref.WeakReference;

public final class HgRecentsDepthController extends DepthController<RecentsState, RecentsActivity> {
    private static final String TAG = "HgRecentsBlur";

    /** The live recents activity's controller, for the swipe handler (main thread). */
    private static WeakReference<HgRecentsDepthController> sCurrent = new WeakReference<>(null);

    /**
     * How much of recents can be seen; static, so a controller created during a swipe (recents
     * recreated) starts from the swipe's value, not from 1. Main thread.
     */
    private static float sVisibility = 1f;
    /** A quiet reset happened and is not on the surface yet (applied at onResume). */
    private static boolean sApplyPending;

    HgRecentsDepthController(RecentsActivity activity, ListenableRef<Boolean> blurState) {
        super(activity, blurState);
        sCurrent = new WeakReference<>(this);
    }

    /**
     * How much of recents can be seen, 0 to 1 (1 - the home window's alpha during a swipe over
     * the home app; 1 otherwise). Main thread. No-op without a recents blur.
     */
    public static void setRecentsVisibility(float visibility) {
        float v = Math.max(0f, Math.min(1f, visibility));
        if (v == sVisibility) return;
        if ((v == 0f) != (sVisibility == 0f)) {
            Log.d(TAG, v == 0f ? "blur held: recents hidden behind home" : "blur follows recents");
        }
        sVisibility = v;
        sApplyPending = false;
        HgRecentsDepthController controller = sCurrent.get();
        // Called every frame of the home fade: only when the radius changes (it is full well
        // before the fade ends), and not before recents has a surface (its first apply uses it).
        if (controller != null && controller.hgBlurWouldChange()) controller.applyDepthAndBlur();
    }

    /**
     * The swipe is over without recents shown (back on the home app, or cancelled): full
     * visibility again for the next time recents opens, without re-applying now, which would
     * flash the blur behind the home app while Shell hides recents.
     */
    public static void resetRecentsVisibilityQuietly() {
        sVisibility = 1f;
        sApplyPending = true;
        // A swipe released mid-fade may have left SurfaceFlinger's early wakeup on.
        HgRecentsDepthController controller = sCurrent.get();
        if (controller != null) controller.setEarlyWakeup(false);
    }

    /**
     * RecentsActivity.onResume: puts a quiet reset on the surface, in case recents comes back
     * without a stop and start (an overview command, 3-button recents) that would re-apply it.
     */
    public static void applyPendingVisibility() {
        if (!sApplyPending) return;
        sApplyPending = false;
        HgRecentsDepthController controller = sCurrent.get();
        if (controller != null) controller.applyDepthAndBlur();
    }

    /** RecentsActivity.onDestroy: forgets this controller. */
    public void onDestroy() {
        if (sCurrent.get() == this) sCurrent.clear();
    }

    @Nullable
    static HgRecentsDepthController current() {
        return sCurrent.get();
    }

    /** Recents opens: the owner's blur switch as it is now (HgBlurSupport.refreshRecentsBlur). */
    @Override
    public void setActivityStarted(boolean isStarted) {
        if (isStarted) HgBlurSupport.refreshRecentsBlur(mContainer);
        super.setActivityStarted(isStarted);
    }

    @Override
    protected float hgWallpaperZoom(float depth) {
        return 0f;
    }

    /** No blur depth while recents cannot blur (switched off or unavailable): nothing to send. */
    @Override
    protected float hgBlurDepth(float depth) {
        if (!mCrossWindowBlursEnabled) return 0f;
        return depth * sVisibility;
    }
}
