// HyperGestures: a full-screen, transparent, non-touchable, non-focusable window that
// EdgeBackGesturePanel (the AOSP back arrow) is drawn in. Touches never land on it; the arrow is
// driven by events from TouchInteractionService's input monitor. TYPE_NAVIGATION_BAR_PANEL and
// the trusted-overlay flag need INTERNAL_SYSTEM_WINDOW, which the recents package holds; a
// trusted overlay also keeps it out of untrusted-touch occlusion, so apps below keep their input.
// The root is INVISIBLE unless it is armed and the arrow panel is VISIBLE
// (HgBackPolicy.overlayVisibility), so between gestures the window has no surface, no buffers and
// no SurfaceFlinger layer. The panel turns VISIBLE on every edge touch-down and GONE again after its
// exit animation; EdgeBackInputConsumer arms the root only once the handler accepts a back swipe,
// so an edge tap never maps the window. The root disarms itself when it goes INVISIBLE again.
// Main thread only (called from TouchInteractionService's input path).
package com.android.quickstep.interaction;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

public final class EdgeBackOverlay {
    private final Context mContext;
    private Root mRoot;
    private WindowManager mWm;
    private boolean mDestroyed;

    public EdgeBackOverlay(Context context, int displayId) {
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) {
            throw new IllegalArgumentException("no display " + displayId);
        }
        Context dc = context.createDisplayContext(display);
        mContext = dc.createWindowContext(
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null);
    }

    public ViewGroup root() {
        if (mDestroyed) {
            throw new IllegalStateException("EdgeBackOverlay used after destroy()");
        }
        if (mRoot == null) {
            mWm = mContext.getSystemService(WindowManager.class);
            Root root = new Root(mContext);
            root.setVisibility(View.INVISIBLE); // no surface until the arrow shows
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("HyperGesturesEdgeBack");
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTrustedOverlay();
            mWm.addView(root, lp);
            mRoot = root;
        }
        return mRoot;
    }

    /** A back swipe was accepted: show the window while the arrow panel is VISIBLE. */
    public void arm() {
        if (mRoot != null) mRoot.arm();
    }

    public void destroy() {
        mDestroyed = true;
        if (mRoot != null) {
            try {
                mWm.removeViewImmediate(mRoot);
            } catch (IllegalArgumentException ignored) {
                // view already removed (e.g. its display went away)
            }
            mRoot = null;
        }
    }

    /**
     * The window's root view; its visibility follows its children's. onChildVisibilityChanged is
     * a @hide ViewGroup hook (in the device framework: View.setFlags calls it on every visibility
     * change of a child); hg/launcher/proguard.flags keeps the override.
     */
    static final class Root extends FrameLayout {
        private boolean mArmed;

        Root(Context context) {
            super(context);
        }

        void arm() {
            mArmed = true;
            sync(null);
        }

        @Override
        protected void onChildVisibilityChanged(View child, int oldVisibility, int newVisibility) {
            super.onChildVisibilityChanged(child, oldVisibility, newVisibility);
            sync(null);
        }

        @Override
        public void onViewAdded(View child) {
            super.onViewAdded(child);
            sync(null);
        }

        @Override
        public void onViewRemoved(View child) {
            super.onViewRemoved(child);
            sync(child);
        }

        /** @param leaving a child being removed (it may still be in the child list) */
        private void sync(View leaving) {
            int n = getChildCount();
            int[] visibilities = new int[n];
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                visibilities[i] = c == leaving ? View.GONE : c.getVisibility();
            }
            int v = HgBackPolicy.overlayVisibility(mArmed, visibilities);
            if (v != View.VISIBLE) mArmed = false; // the gesture and its exit animation are over
            if (getVisibility() != v) setVisibility(v);
        }
    }
}
