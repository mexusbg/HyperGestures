// HyperGestures: a toast-like message in our own overlay window (Clear all's "512MB cleared").
//
// android.widget.Toast did not show on the phone (0.3.9.25): the message comes about a second
// after Clear all, when recents has gone to the background, and HyperOS suppresses toasts from
// background apps (MiuiHome never hits that: it is HOME). So the message is a non-focusable,
// non-touchable TYPE_APPLICATION_OVERLAY window marked as a system application overlay, which the
// recents provider may add (SYSTEM_APPLICATION_OVERLAY is a "recents" permission, requested in
// quickstep's manifest), styled like HyperOS's toast: a rounded pill, bottom centre above the
// navigation area, dark in dark mode and light otherwise. It shows for SHOW_MS, one at a time (a
// new one replaces it). If the window cannot be added it falls back to a plain Toast.
// Main thread only.
package com.android.quickstep;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

final class HgToastOverlay {
    private HgToastOverlay() {}

    private static final String TAG = "HgClearAll";
    static final long SHOW_MS = 2000;
    /** Above the navigation area, as Toast's default offset. */
    private static final float BOTTOM_DP = 64;

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Runnable sHide = HgToastOverlay::hide;
    @Nullable private static View sShown;

    /** Shows {@code text} for SHOW_MS, replacing a message still on screen. */
    static void show(Context context, CharSequence text) {
        Context app = context.getApplicationContext();
        hide();
        float density = app.getResources().getDisplayMetrics().density;
        boolean dark = (app.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        TextView view = new TextView(app);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(dark ? 0xFFFFFFFF : 0xE6000000);
        view.setGravity(Gravity.CENTER);
        int padH = Math.round(20 * density);
        int padV = Math.round(11 * density);
        view.setPadding(padH, padV, padH, padV);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(dark ? 0xE6333333 : 0xF2FFFFFF);
        pill.setCornerRadius(100 * density); // fully rounded ends
        if (!dark) pill.setStroke(Math.max(1, Math.round(0.5f * density)), 0x1F000000);
        view.setBackground(pill);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.setSystemApplicationOverlay(true);
        lp.setTitle("HgClearAllMessage");
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(BOTTOM_DP * density);
        lp.windowAnimations = android.R.style.Animation_Toast;

        WindowManager wm = app.getSystemService(WindowManager.class);
        try {
            if (wm == null) throw new IllegalStateException("no WindowManager");
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            Log.w(TAG, "message overlay failed, using a Toast: " + e);
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
            return;
        }
        sShown = view;
        sMain.postDelayed(sHide, SHOW_MS);
    }

    private static void hide() {
        sMain.removeCallbacks(sHide);
        View view = sShown;
        sShown = null;
        if (view == null || !view.isAttachedToWindow()) return;
        try {
            WindowManager wm = view.getContext().getSystemService(WindowManager.class);
            if (wm != null) wm.removeView(view);
        } catch (RuntimeException e) {
            Log.w(TAG, "message overlay removal failed: " + e);
        }
    }
}
