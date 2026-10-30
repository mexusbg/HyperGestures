// HyperGestures: the lock badge on a locked recents card (HgTaskLockStore): Launcher3's ic_lock
// on a small dark disc in the top-start corner of the card's thumbnail, added to the card's
// TaskContentView (a ConstraintLayout holding the @id/snapshot thumbnail) on first use. The
// start corner, not the end: in the stack the newer card covers a card's end side, and older
// cards show only a strip on their start side, so a badge at the end could not be seen
// (2026-10-31 review). Locking or unlocking a card (refresh) fades and scales the badge in or out
// over ANIM_MS; binding a card shows it at once.
package com.android.quickstep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.launcher3.R;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;

public final class HgTaskLockBadge {
    private HgTaskLockBadge() {}

    private static final String TAG_BADGE = "hg_task_lock_badge";
    private static final int SIZE_DP = 24;
    private static final int PADDING_DP = 5;
    private static final int MARGIN_DP = 8;
    static final long ANIM_MS = 150;
    /** The scale the badge grows from, and shrinks to. */
    private static final float FROM_SCALE = 0.6f;

    /** Shows or hides the lock badge on one card's content view, at once. */
    public static void apply(ConstraintLayout contentView, boolean locked) {
        apply(contentView, locked, false);
    }

    /** As above; {@code animate}: fades and scales it in or out (a lock toggled now). */
    static void apply(ConstraintLayout contentView, boolean locked, boolean animate) {
        View existing = contentView.findViewWithTag(TAG_BADGE);
        if (!locked) {
            if (existing == null) return;
            existing.animate().cancel();
            if (animate && existing.getVisibility() == View.VISIBLE) {
                existing.animate().alpha(0f).scaleX(FROM_SCALE).scaleY(FROM_SCALE)
                        .setDuration(ANIM_MS)
                        .withEndAction(() -> existing.setVisibility(View.GONE)).start();
            } else {
                existing.setVisibility(View.GONE);
            }
            return;
        }
        if (existing != null) {
            existing.animate().cancel();
            boolean hidden = existing.getVisibility() != View.VISIBLE;
            existing.setVisibility(View.VISIBLE);
            existing.bringToFront();
            show(existing, animate && hidden);
            return;
        }
        Context context = contentView.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(SIZE_DP * density);
        int padding = Math.round(PADDING_DP * density);
        int margin = Math.round(MARGIN_DP * density);

        ImageView badge = new ImageView(context);
        badge.setTag(TAG_BADGE);
        badge.setImageResource(R.drawable.ic_lock);
        badge.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        badge.setBackgroundResource(R.drawable.hg_task_lock_badge_bg);
        badge.setPadding(padding, padding, padding, padding);
        badge.setContentDescription(context.getString(R.string.hg_task_locked));

        ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(size, size);
        lp.topToTop = R.id.snapshot;
        lp.startToStart = R.id.snapshot;
        lp.topMargin = margin;
        lp.setMarginStart(margin);
        contentView.addView(badge, lp);
        show(badge, animate);
    }

    private static void show(View badge, boolean animate) {
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            badge.setAlpha(1f);
            badge.setScaleX(1f);
            badge.setScaleY(1f);
            return;
        }
        badge.setAlpha(0f);
        badge.setScaleX(FROM_SCALE);
        badge.setScaleY(FROM_SCALE);
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ANIM_MS).start();
    }

    /** Updates the badge on every card of {@code taskKey} ({@link HgTaskLocks#key}). */
    public static void refresh(RecentsView<?, ?> recentsView, String taskKey, boolean locked) {
        for (TaskView taskView : recentsView.getTaskViews()) {
            for (TaskContainer container : taskView.getTaskContainers()) {
                if (taskKey.equals(HgTaskLockStore.keyOf(container.getTask()))) {
                    apply(container.getTaskContentView(), locked, true);
                }
            }
        }
    }
}
