// HyperGestures: entry point from TouchInteractionHandler (aosp-patch). Keeps one
// EdgeBackGestureHandler + overlay per display and rebuilds them when the display size changes
// (rotation), so the edge geometry always matches the current display.
package com.android.quickstep.interaction;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewGroup;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.testing.shared.ResourceUtils;
import com.android.launcher3.Utilities;
import com.android.quickstep.InputConsumer;
import com.android.quickstep.RecentsAnimationDeviceState;
import com.android.quickstep.TaskAnimationManager;
import com.android.systemui.shared.system.InputMonitorCompat;

public final class HgEdgeBack {
    private HgEdgeBack() {}

    private static final class PerDisplay {
        EdgeBackOverlay overlay;
        EdgeBackGestureHandler handler;
        final Point size = new Point();
        /** The density the handler's and panel's px values (slop, bar height, size) are for. */
        int densityDpi;
    }

    private static final String TAG = "HgEdgeBack";
    private static final long FAILURE_BACKOFF_MS = 10_000;
    /** Overlay/handler setups failing in a row after which edge back stays off until destroy(). */
    private static final int MAX_SETUP_FAILURES = 3;

    private static final SparseArray<PerDisplay> sDisplays = new SparseArray<>();
    /** Uptime of the last failed setup per display; no retry for FAILURE_BACKOFF_MS. */
    private static final SparseLongArray sFailedAt = new SparseLongArray();
    private static int sSetupFailures;

    /**
     * Only touch-downs this close to a side edge are looked at further (cheap pre-check). 40dp
     * covers the system back inset; it is widened by the largest back-sensitivity scale.
     */
    private static final float PRECHECK_MARGIN_DP = 40 * HgBackPolicy.MAX_INSET_SCALE;

    /** Set after a RuntimeException in a consumer; cleared by destroy(). */
    private static boolean sDisabledForSession;

    /** A back consumer for this ACTION_DOWN, or null if it doesn't start a back gesture. */
    public static InputConsumer consumerFor(Context ctx, RecentsAnimationDeviceState deviceState,
            TaskAnimationManager tam, InputMonitorCompat monitor, MotionEvent down) {
        if (sDisabledForSession) return null;
        if (!HgBackPolicy.isAllowed(deviceState.isFullyGesturalNavMode(), deviceState.getSysuiStateFlags())) {
            return null;
        }
        if (!HgBackPolicy.canCommit(tam.isRecentsAnimationRunning(), SystemClock.uptimeMillis(),
                tam.getRecentsCleanedUpAtMs())) {
            return null;
        }
        int displayId = down.getDisplayId();
        // The DeviceProfile used below is the default display's; the alpha targets one display.
        if (displayId != Display.DEFAULT_DISPLAY) return null;
        PerDisplay pd = null;
        boolean settingUp = false;
        try {
            // Cheap x pre-check before any DeviceProfile or resource lookups.
            int widthPx = ctx.getResources().getDisplayMetrics().widthPixels;
            int margin = Utilities.dpToPx(PRECHECK_MARGIN_DP);
            float x = down.getX();
            if (x > margin && x < widthPx - margin) return null;

            DeviceProfile dp = InvariantDeviceProfile.INSTANCE.get(ctx).getDeviceProfile(ctx);
            int w = dp.getDeviceProperties().getWidthPx();
            int h = dp.getDeviceProperties().getHeightPx();
            int base = baseEdgeWidth(ctx.getResources());
            int leftEdge = edgeWidth(ctx, base, true);
            int rightEdge = edgeWidth(ctx, base, false);
            int bottom = ResourceUtils.getNavbarSize(ResourceUtils.NAVBAR_BOTTOM_GESTURE_SIZE,
                    ctx.getResources());
            // App gesture-exclusion zones (seek bars, drawers): RecentsAnimationDeviceState keeps
            // WindowManager's exclusion region for the default display (Launcher3's
            // GestureExclusionManager listener, registered in gestural nav mode and updated on
            // the main thread; WM re-sends it on rotation and layout).
            boolean edgeDown = HgBackPolicy.isBackEdgeDown(x, down.getY(), w, h, leftEdge,
                    rightEdge, bottom);
            boolean excluded = edgeDown && deviceState.isInExclusionRegion(down);
            if (!HgBackPolicy.startsBack(edgeDown, excluded)) {
                if (excluded && Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "edge down in gesture-exclusion region; left to the app");
                }
                return null;
            }

            // Rebuilt on a new size or density ("Display size"): the handler and panel take their
            // px values (touch slop, bottom gesture height, panel size) once, as they are built.
            int densityDpi = ctx.getResources().getConfiguration().densityDpi;
            pd = sDisplays.get(displayId);
            if (pd == null || pd.size.x != w || pd.size.y != h || pd.densityDpi != densityDpi) {
                if (pd != null) {
                    sDisplays.remove(displayId);
                    teardown(pd);
                }
                if (SystemClock.uptimeMillis() - sFailedAt.get(displayId, -FAILURE_BACKOFF_MS)
                        < FAILURE_BACKOFF_MS) {
                    return null;
                }
                settingUp = true;
                pd = new PerDisplay();
                pd.size.set(w, h);
                pd.densityDpi = densityDpi;
                pd.overlay = new EdgeBackOverlay(ctx, displayId);
                ViewGroup root = pd.overlay.root();
                pd.handler = new EdgeBackGestureHandler(ctx, dp);
                pd.handler.setViewGroupParent(root);
                sFailedAt.delete(displayId);
                sDisplays.put(displayId, pd);
                sSetupFailures = 0;
            }
            // The handler tests the same touch-down again; give it the widths just used.
            pd.handler.setEdgeWidths(leftEdge, rightEdge);
            return new EdgeBackInputConsumer(ctx, displayId, pd.handler, pd.overlay, monitor, tam);
        } catch (RuntimeException e) {
            sFailedAt.put(displayId, SystemClock.uptimeMillis());
            if (pd != null) {
                if (sDisplays.get(displayId) == pd) sDisplays.remove(displayId);
                teardown(pd);
            }
            // A setup that keeps failing (e.g. a framework change after an OTA) would otherwise
            // retry, with an addView and a stack trace, on an edge touch every FAILURE_BACKOFF_MS.
            // Only overlay/handler setups count, and only in a row (a success resets the count).
            if (settingUp && ++sSetupFailures >= MAX_SETUP_FAILURES) {
                Log.e(TAG, "edge back unavailable on display " + displayId
                        + "; off until the service restarts", e);
                disableForSession();
            } else {
                Log.e(TAG, "edge back unavailable on display " + displayId, e);
            }
            return null;
        }
    }

    /** Logged the first failed back-sensitivity read; later failures stay quiet. */
    private static boolean sScaleReadFailed;

    /** The system back inset in px: config_backGestureInset, or 18dp if 0 (gesture nav off). */
    static int baseEdgeWidth(Resources res) {
        int inset = ResourceUtils.getNavbarSize("config_backGestureInset", res);
        return inset == 0 ? Utilities.dpToPx(18) : inset;
    }

    /**
     * One side's back edge width in px: the system back inset (baseEdgeWidth) times AOSP's back
     * sensitivity (Settings.Secure back_gesture_inset_scale_left/right), as
     * SystemUI's GestureNavigationSettingsObserver computes it; HyperOS compiled SystemUI's back
     * handler out, so nothing else applies these keys. Read on every edge touch-down instead of
     * observed: Settings' per-process cache answers repeat reads from memory and re-queries only
     * after the provider's generation counter moved, so a new value applies from the next swipe
     * with no observer to register and unregister, and pre-checked touches never get here.
     */
    static int edgeWidth(Context ctx, int base, boolean left) {
        float scale;
        try {
            scale = Settings.Secure.getFloat(ctx.getContentResolver(),
                    left ? HgBackPolicy.INSET_SCALE_LEFT : HgBackPolicy.INSET_SCALE_RIGHT, 1f);
        } catch (RuntimeException e) {
            // A failing read must not cost back itself (consumerFor would give up on every edge
            // touch, the tutorial's handler would not build): use AOSP's default scale.
            if (!sScaleReadFailed) {
                sScaleReadFailed = true;
                Log.w(TAG, "back sensitivity unreadable; using 1.0", e);
            }
            scale = 1f;
        }
        return HgBackPolicy.scaledEdgeWidth(base, scale);
    }

    /**
     * A consumer hit a RuntimeException: no more edge back until destroy() (the service
     * restarts), and the overlay and handler entries are torn down.
     */
    static void disableForSession() {
        sDisabledForSession = true;
        for (int i = 0; i < sDisplays.size(); i++) {
            teardown(sDisplays.valueAt(i));
        }
        sDisplays.clear();
    }

    private static void teardown(PerDisplay pd) {
        try {
            if (pd.handler != null) pd.handler.setViewGroupParent(null);
        } catch (RuntimeException e) {
            Log.w(TAG, "handler teardown failed", e);
        }
        try {
            if (pd.overlay != null) pd.overlay.destroy();
        } catch (RuntimeException e) {
            Log.w(TAG, "overlay teardown failed", e);
        }
    }

    /** TouchInteractionService destroyed: remove the overlay windows. */
    public static void destroy() {
        for (int i = 0; i < sDisplays.size(); i++) {
            PerDisplay pd = sDisplays.valueAt(i);
            teardown(pd);
        }
        sDisplays.clear();
        sFailedAt.clear();
        sSetupFailures = 0;
        sDisabledForSession = false;
    }
}
