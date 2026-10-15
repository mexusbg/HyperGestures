// HyperGestures: HyperOS's memory line in recents ("5.3 GB of 16.0 GB available"; HyperOS puts it
// top right, the owner wanted it at the bottom), shown or hidden by holding the trash button for
// HOLD_MS (owner, 2026-10-07). A tap on the trash still clears all; a hold that toggles does not.
//
// Shown or hidden as in HyperOS (plan ruling R2): by Settings.System miui_recents_show_mem_info
// (MiuiHome's "Show memory status", 0/1, default 0 = hidden), observed while recents is attached.
// The hold writes that setting, so MIUI's switch and ours are one. SettingsProvider lets us: a
// WRITE_SECURE_SETTINGS holder skips the WRITE_SETTINGS app-op for System keys, and a privileged
// app may write keys outside AOSP's public list (our manifest also requests WRITE_SETTINGS, belt
// and braces). The app preference the hold used before is carried over once (show_ram on and the
// system key unset) and then dropped, whether or not that write worked.
// HyperOS also hides the line in multi-window; our recents is never in a small window.
//
// The line lives in the recents drag layer (bottom centre, under the trash button) and fades with
// the recents action row (OverviewActionsView's alpha, copied on each frame the row draws), so it
// shows with the trash button and nowhere else. It reads the memory each time it fades in.
package com.android.quickstep;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.views.RecentsViewContainer;

public final class HgRamIndicator {
    private static final String TAG = "HgRamIndicator";
    /** MiuiHome's switch (MiuiSettingsUtils.MIUI_RECENTS_SHOW_MEM_INFO), Settings.System. */
    static final String SHOW_MEM_INFO = "miui_recents_show_mem_info";
    /** The app preference the hold toggled before the line followed SHOW_MEM_INFO. */
    private static final String OLD_PREFS = "hg_recents";
    private static final String OLD_KEY_SHOW = "show_ram";
    static final long HOLD_MS = 5000;
    /** Gap between the line and the bottom inset (the gesture bar). */
    private static final float BOTTOM_MARGIN_DP = 6;

    private final View mActions;
    private final View mTrash;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mShow = isOn(mActions.getContext());
            refresh();
            sync();
        }
    };
    @Nullable private TextView mLine;
    private boolean mToggledThisPress;
    /** SHOW_MEM_INFO, read on attach and on each change (sync runs every recents frame). */
    private boolean mShow;
    private float mLastAlpha;

    private HgRamIndicator(View actions, View trash) {
        mActions = actions;
        mTrash = trash;
    }

    /** OverviewActionsView.onFinishInflate: wires the trash hold and the memory line. */
    public static void attach(View actions, @Nullable View trash) {
        if (trash == null) return;
        HgRamIndicator indicator = new HgRamIndicator(actions, trash);
        trash.setOnTouchListener(indicator::onTrashTouch);
        actions.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private final ViewTreeObserver.OnPreDrawListener mSync = () -> {
                indicator.sync();
                return true;
            };

            @Override
            public void onViewAttachedToWindow(View v) {
                indicator.observe(true);
                indicator.addLine();
                v.getViewTreeObserver().addOnPreDrawListener(mSync);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                // Not removing the line here: it goes with the same window, and removing a child
                // of the drag layer while the window dispatches its detach crashes that loop.
                v.getViewTreeObserver().removeOnPreDrawListener(mSync);
                indicator.mHandler.removeCallbacks(indicator.mToggle);
                indicator.observe(false);
            }
        });
    }

    /**
     * HyperOS's text (MiuiHome RecentsContainer): {@code template} ("%1$s of %2$s available")
     * with the free memory and the total, the total first rounded up to the size the phone is
     * sold with (HyperOS's HardwareInfo.getTotalPhysicalMemory) and then to a whole GB. Memory
     * extension ("16.0+6.0 GB") is not shown: it is off on the owner's phone.
     */
    static String format(String template, String mbTemplate, String gbTemplate, long availBytes,
            long totalBytes) {
        return String.format(template,
                formatMemory(availBytes / 1024, false, mbTemplate, gbTemplate),
                formatMemory(physicalBytes(totalBytes) / 1024, true, mbTemplate, gbTemplate));
    }

    /**
     * MiuiHome's getFormatedMemory: whole MB under 1024 MB ({@code mbTemplate}, "%1$s MB"), else
     * GB with one decimal ({@code gbTemplate}, "%1$s GB"), rounded, or with {@code roundUp} the
     * next whole GB. The decimal point is always '.', as HyperOS builds the number by hand.
     */
    static String formatMemory(long kb, boolean roundUp, String mbTemplate, String gbTemplate) {
        long mb = kb / 1024;
        if (mb < 1024) return String.format(mbTemplate, Long.toString(mb));
        long tenths = roundUp ? (long) Math.ceil(mb / 1024.0) * 10 : Math.round(mb * 10 / 1024.0);
        return String.format(gbTemplate, (tenths / 10) + "." + (tenths % 10));
    }

    /**
     * HyperOS's HardwareInfo.getTotalPhysicalMemory: the kernel's total (which leaves out the
     * kernel's own share) rounded up to a multiple of 1 GiB up to 4 GiB, 2 GiB up to 8 GiB, else
     * 4 GiB; so a 16 GB phone's 14.9 GiB is 16 GiB. Read from that method's bytecode in the HOS4
     * miui-framework.jar (not in the research doc); only the 16 GB phone has been seen, other
     * RAM sizes are unverified.
     */
    static long physicalBytes(long totalBytes) {
        long gib = 1L << 30;
        long step = totalBytes <= 4 * gib ? gib : totalBytes <= 8 * gib ? 2 * gib : 4 * gib;
        return (totalBytes + step - 1) & ~(step - 1);
    }

    private boolean onTrashTouch(View v, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mToggledThisPress = false;
                mHandler.postDelayed(mToggle, HOLD_MS);
                break;
            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mToggle);
                if (mToggledThisPress) {
                    v.setPressed(false);
                    return true; // the hold toggled the line: no Clear all
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mToggle);
                break;
            default:
                break;
        }
        return false;
    }

    private final Runnable mToggle = this::toggle;

    private void toggle() {
        mToggledThisPress = true;
        boolean show = !mShow;
        // Shown or hidden at once; if the write fails, only until recents is next attached.
        mShow = show;
        write(mTrash.getContext(), show);
        mTrash.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Log.i(TAG, "memory line " + (show ? "shown" : "hidden"));
        refresh();
        sync();
    }

    private static boolean write(Context context, boolean show) {
        try {
            return Settings.System.putInt(context.getContentResolver(), SHOW_MEM_INFO,
                    show ? 1 : 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot write " + SHOW_MEM_INFO + ": " + e);
            return false;
        }
    }

    static boolean isOn(Context context) {
        return Settings.System.getInt(context.getContentResolver(), SHOW_MEM_INFO, 0) != 0;
    }

    /** Once: the old app preference, if it showed the line and HyperOS's key is unset. */
    private static void migrate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(OLD_KEY_SHOW)) return;
        boolean show = prefs.getBoolean(OLD_KEY_SHOW, false);
        ContentResolver cr = context.getContentResolver();
        if (show && Settings.System.getString(cr, SHOW_MEM_INFO) == null) {
            write(context, true); // one attempt: a failure is logged, not retried on each attach
        }
        prefs.edit().remove(OLD_KEY_SHOW).apply();
    }

    private void observe(boolean on) {
        ContentResolver cr = mActions.getContext().getContentResolver();
        if (on) {
            cr.registerContentObserver(Settings.System.getUriFor(SHOW_MEM_INFO), false,
                    mObserver);
        } else {
            cr.unregisterContentObserver(mObserver);
        }
    }

    private void addLine() {
        migrate(mActions.getContext());
        mShow = isOn(mActions.getContext());
        BaseDragLayer<?> dragLayer;
        try {
            dragLayer = RecentsViewContainer.containerFromContext(mActions.getContext())
                    .getDragLayer();
        } catch (RuntimeException e) {
            return;
        }
        if (dragLayer == null) return;
        if (mLine != null && mLine.getParent() == dragLayer) return; // already in this window
        Context context = mActions.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        TextView line = new TextView(context);
        line.setTextColor(Color.WHITE);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        line.setShadowLayer(4 * density, 0f, 1 * density, 0x66000000);
        line.setAlpha(0f);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        // Inset-aware (InsettableFrameLayout adds the insets as margins): at the bottom, under the
        // trash button, above the gesture bar (owner, 2026-10-07: "move it to the bottom").
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Math.round(BOTTOM_MARGIN_DP * density);
        dragLayer.addView(line, lp);
        mLine = line;
        refresh();
    }

    /** Copies the action row's visibility; re-reads the memory each time the line fades in. */
    private void sync() {
        TextView line = mLine;
        if (line == null) return;
        // The action row fades and hides through its button row (mActionButtons' alpha), the
        // trash button's parent, not through OverviewActionsView itself.
        boolean show = mShow && mTrash.isShown();
        View row = mTrash.getParent() instanceof View parent ? parent : mTrash;
        float alpha = show ? mActions.getAlpha() * row.getAlpha() * mTrash.getAlpha() : 0f;
        if (alpha > 0f && mLastAlpha == 0f) refresh();
        mLastAlpha = alpha;
        if (line.getAlpha() != alpha) line.setAlpha(alpha);
    }

    private void refresh() {
        TextView line = mLine;
        if (line == null) return;
        Context context = line.getContext();
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        line.setText(format(context.getString(R.string.hg_recents_memory_info),
                context.getString(R.string.hg_recents_memory_mega),
                context.getString(R.string.hg_recents_memory_giga),
                info.availMem, info.totalMem));
    }
}
