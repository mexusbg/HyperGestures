// HyperGestures: the lock badge on a locked recents card (HgTaskLockStore): Launcher3's ic_lock
// on a small dark disc in the top-end corner of the card's thumbnail, added to the card's
// TaskContentView (a ConstraintLayout holding the @id/snapshot thumbnail) on first use.
package com.android.quickstep;

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

    /** Shows or hides the lock badge on one card's content view. */
    public static void apply(ConstraintLayout contentView, boolean locked) {
        View existing = contentView.findViewWithTag(TAG_BADGE);
        if (!locked) {
            if (existing != null) existing.setVisibility(View.GONE);
            return;
        }
        if (existing != null) {
            existing.setVisibility(View.VISIBLE);
            existing.bringToFront();
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
        lp.endToEnd = R.id.snapshot;
        lp.topMargin = margin;
        lp.setMarginEnd(margin);
        contentView.addView(badge, lp);
    }

    /** Updates the badge on every card of {@code taskKey} ({@link HgTaskLocks#key}). */
    public static void refresh(RecentsView<?, ?> recentsView, String taskKey, boolean locked) {
        for (TaskView taskView : recentsView.getTaskViews()) {
            for (TaskContainer container : taskView.getTaskContainers()) {
                if (taskKey.equals(HgTaskLockStore.keyOf(container.getTask()))) {
                    apply(container.getTaskContentView(), locked);
                }
            }
        }
    }
}
