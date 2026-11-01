// HyperGestures: the blurred background of recents, as in HyperOS's own recents (design ruling 1-2).
//
// AOSP's recents-in-a-window (RecentsWindowManager) blurs with a DepthController: a state handler
// that sets a SurfaceFlinger background blur on the recents surface (and zooms the wallpaper) to
// each RecentsState's depth (70% in overview, ro.launcher.depth.overview), and that BlurUtils moves
// below the live tile so the running app's card stays sharp. Fallback RecentsActivity never had
// one; this gives it the same, fed by HgBlurSupport: the system's cross-window blur state and
// HyperOS's own blur terms (HyperOS turns blur off on lower-end devices, and in battery saver and
// low-power modes; the controller follows it). A window background blur
// (Window.setBackgroundBlurRadius) does not work here: it needs a translucent window.
//
// With the controller the activity takes HgOverviewBlurStyle (AOSP's blur-styled actions and a
// dim over the wallpaper instead of AOSP's solid fallback scrim), whether or not recents can blur:
// as in MIUI, a phone without blur shows its wallpaper dimmed. The dim follows HgBlurSupport live:
// light over the blur (hg_overview_scrim), darker over a sharp wallpaper so the white headers and
// memory line stay readable (hg_overview_scrim_no_blur). Settings.Global
// hypergestures_recents_blur=0 (the page's "Blur background") turns the blur off: recents then
// looks as on a phone without blur (the dimmed sharp wallpaper, solid buttons). It is read each
// time recents opens (HgRecentsDepthController.setActivityStarted), so the switch applies the
// next time recents opens, without a reboot; the controller exists either way.
package com.android.quickstep;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.statehandlers.DepthController;
import com.android.launcher3.statemanager.StateManager;
import com.android.launcher3.views.ScrimView;
import com.android.quickstep.fallback.RecentsState;

public final class HgRecentsBlur {
    private HgRecentsBlur() {}

    private static final String TAG = "HgRecentsBlur";

    /**
     * A DepthController for {@code activity}, following whether recents can blur
     * (HgBlurSupport, which includes the owner's switch); null only if it cannot be created.
     * Call from onCreate, before the views are inflated.
     */
    @Nullable
    public static DepthController<RecentsState, RecentsActivity> createDepthController(
            RecentsActivity activity) {
        boolean on = HgRecentsSettings.isOn(activity, HgRecentsSettings.BLUR);
        // Followed with or without the blur: the frosted elements need the answer either way.
        activity.closeOnDestroy(HgBlurSupport.track(activity, on));
        try {
            Log.i(TAG, "recents blur " + (on ? "on" : "off (hypergestures_recents_blur=0)")
                    + "; blur "
                    + (HgBlurSupport.isBlurAvailable(activity) ? "available" : "unavailable"));
            DepthController<RecentsState, RecentsActivity> controller =
                    new HgRecentsDepthController(activity, HgBlurSupport.blurState(activity));
            // The running app's live tile is drawn below recents: never mark recents opaque, even
            // with the solid fallback scrim (blur off), or the tile shows black.
            controller.setHasContentBehindContainer(true);
            followScrim(activity);
            return controller;
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "recents blur unavailable", e);
            return null;
        }
    }

    /**
     * Whether the blur-styled theme (the dim over the wallpaper) applies: whenever there is a
     * controller, with or without blur right now (scrimColor darkens the dim without blur). With
     * hypergestures_blur_fallback=0, 0.3.9.29's rule: only with blur at creation.
     */
    public static boolean useBlurStyle(@Nullable DepthController<?, ?> depthController) {
        // A controller exists only after createDepthController started tracking the state.
        return depthController != null
                && (HgBlurSupport.isPolicyOnNow() || HgBlurSupport.isBlurAvailableNow());
    }

    /**
     * RecentsState.getScrimColor: the theme's scrim {@code themeColor}, or, over our dimmed
     * wallpaper while recents cannot blur, the darker no-blur dim. It follows the same answer as
     * the glass buttons (HgBlurSupport.isFrostedNow), so dim and buttons switch together.
     */
    public static int scrimColor(Context context, int themeColor) {
        if (context instanceof RecentsActivity activity && activity.getHgDepthController() != null
                && !HgBlurSupport.isFrostedNow()) {
            return context.getColor(R.color.hg_overview_scrim_no_blur);
        }
        return themeColor;
    }

    /**
     * While the activity lives, a change of HgBlurSupport's answer re-dims the scrim at once (the
     * state handlers set it only on state changes), as the glass buttons swap at once. A state
     * animation under way sets its own end colour, from the new answer.
     */
    private static void followScrim(RecentsActivity activity) {
        activity.closeOnDestroy(HgBlurSupport.blurState(activity).forEach(
                activity.getMainExecutor(), blur -> {
                    ScrimView scrim = activity.getScrimView();
                    StateManager<RecentsState, RecentsActivity> states =
                            activity.getStateManager();
                    if (scrim == null || states == null || states.isInTransition()) return null;
                    scrim.setScrimColors(states.getState().getScrimColor(activity));
                    return null;
                }));
    }
}
