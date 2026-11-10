// HyperGestures: the lock badge of a locked recents card (HgTaskLockStore): a small dark disc
// with Launcher3's ic_lock in the bottom-end corner of the card's header app icon, drawn in the
// icon view's overlay so it goes wherever the icon goes, in every recents layout (owner,
// 2026-11-10: "move the locked indicator inside the app icons"; it was a disc in the thumbnail's
// top-end corner). Locking or unlocking a card (refresh) fades and scales it in or out over
// ANIM_MS; binding a card shows it at once; a refused dismiss (nudge) makes it wobble.
package com.android.quickstep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;

public final class HgTaskLockBadge {
    private HgTaskLockBadge() {}

    static final long ANIM_MS = 150;
    /** The scale the badge grows from, and shrinks to. */
    private static final float FROM_SCALE = 0.6f;
    /** The disc's radius, of the icon's size (~12 dp across on the 30 dp header icon). */
    static final float BADGE_FRACTION = 0.21f;
    /** The lock glyph's half size, of the disc's radius. */
    private static final float GLYPH_FRACTION = 0.62f;
    private static final int DISC_COLOR = 0xE6202124;
    private static final int RING_COLOR = 0x66FFFFFF;
    private static final float RING_DP = 1f;
    /** The refused-dismiss wobble (nudge): its length, peak scale and rocking angle. */
    static final long NUDGE_MS = 480;
    static final float NUDGE_SCALE = 1.35f;
    private static final float NUDGE_DEGREES = 18f;
    private static final float[] NUDGE_ROCK = {0f, -1f, 1f, -0.6f, 0.3f, 0f};
    private static final float[] NUDGE_GROW = {1f, NUDGE_SCALE, NUDGE_SCALE, 1f};

    /**
     * The badge's disc on a square icon of size {@code size}: {centre x, centre y, radius}, in
     * the icon's coordinates. Bottom-end corner ({@code rtl}: bottom-left), placed in so far that
     * the nudge's peak (NUDGE_SCALE) still fits inside the icon.
     */
    static float[] geometry(float size, boolean rtl) {
        float r = BADGE_FRACTION * size;
        float centre = size - r * NUDGE_SCALE;
        return new float[] {rtl ? size - centre : centre, centre, r};
    }

    /** Shows or hides the lock badge on one card's header icon, at once. */
    public static void apply(TaskContainer container, boolean locked) {
        apply(container, locked, false);
    }

    /** As above; {@code animate}: fades and scales it in or out (a lock toggled now). */
    static void apply(TaskContainer container, boolean locked, boolean animate) {
        View host = container.getIconView().getHgIconView();
        if (host == null) return;
        Badge badge = (Badge) host.getTag(R.id.hg_task_lock_badge);
        if (!locked) {
            if (badge == null) return;
            if (animate && badge.mVisible > 0f && ValueAnimator.areAnimatorsEnabled()) {
                badge.animateVisible(0f);
            } else {
                badge.cancel();
                badge.setVisible(0f);
            }
            return;
        }
        if (badge == null) badge = Badge.attach(host);
        badge.mRtl = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (animate && badge.mVisible < 1f && ValueAnimator.areAnimatorsEnabled()) {
            badge.animateVisible(1f);
        } else if (badge.mAnimator == null || !animate) {
            badge.cancel();
            badge.setVisible(1f);
        }
    }

    /**
     * The badge's "this card is locked" wobble (a refused dismiss, HgTaskLocking.refuseDismiss):
     * it grows to NUDGE_SCALE and rocks side to side over NUDGE_MS, then settles. Shown first if
     * it was hidden.
     */
    static void nudge(TaskContainer container) {
        apply(container, true, false);
        View host = container.getIconView().getHgIconView();
        Badge badge = host == null ? null : (Badge) host.getTag(R.id.hg_task_lock_badge);
        if (badge == null || !ValueAnimator.areAnimatorsEnabled()) return;
        badge.cancel();
        badge.setVisible(1f);
        ValueAnimator wobble = ValueAnimator.ofFloat(0f, 1f);
        wobble.setDuration(NUDGE_MS);
        wobble.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            badge.mRotation = NUDGE_DEGREES * along(NUDGE_ROCK, f);
            badge.mScale = along(NUDGE_GROW, f);
            badge.invalidateSelf();
        });
        badge.start(wobble);
    }

    /** Updates the badge on every card of {@code taskKey} ({@link HgTaskLocks#key}). */
    public static void refresh(RecentsView<?, ?> recentsView, String taskKey, boolean locked) {
        for (TaskView taskView : recentsView.getTaskViews()) {
            for (TaskContainer container : taskView.getTaskContainers()) {
                if (taskKey.equals(HgTaskLockStore.keyOf(container.getTask()))) {
                    apply(container, locked, true);
                }
            }
        }
    }

    /** {@code points} spread evenly over 0..1, read at {@code f} (linearly between them). */
    private static float along(float[] points, float f) {
        float pos = Math.max(0f, Math.min(1f, f)) * (points.length - 1);
        int i = Math.min((int) pos, points.length - 2);
        return points[i] + (points[i + 1] - points[i]) * (pos - i);
    }

    /** The badge, drawn in its icon view's overlay (sized to the icon view). */
    private static final class Badge extends Drawable {
        private final Paint mDisc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Nullable private final Drawable mGlyph;
        /** 0 hidden .. 1 shown: alpha, and the scale from FROM_SCALE. */
        float mVisible;
        float mScale = 1f;
        float mRotation;
        boolean mRtl;
        @Nullable Animator mAnimator;

        private Badge(Context context) {
            mDisc.setColor(DISC_COLOR);
            mRing.setColor(RING_COLOR);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(RING_DP * context.getResources().getDisplayMetrics().density);
            Drawable glyph = context.getDrawable(R.drawable.ic_lock);
            mGlyph = glyph == null ? null : glyph.mutate();
            if (mGlyph != null) mGlyph.setTint(Color.WHITE);
        }

        static Badge attach(View host) {
            Badge badge = new Badge(host.getContext());
            host.setTag(R.id.hg_task_lock_badge, badge);
            badge.setBounds(0, 0, host.getWidth(), host.getHeight());
            host.getOverlay().add(badge);
            host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                    badge.setBounds(0, 0, r - l, b - t));
            return badge;
        }

        void setVisible(float visible) {
            mVisible = visible;
            mScale = 1f;
            mRotation = 0f;
            invalidateSelf();
        }

        void animateVisible(float to) {
            cancel();
            ValueAnimator fade = ValueAnimator.ofFloat(mVisible, to);
            fade.setDuration(ANIM_MS);
            fade.addUpdateListener(a -> {
                mVisible = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            start(fade);
        }

        void start(ValueAnimator animator) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mAnimator == animation) mAnimator = null;
                    mScale = 1f;
                    mRotation = 0f;
                    invalidateSelf();
                }
            });
            mAnimator = animator;
            animator.start();
        }

        void cancel() {
            Animator running = mAnimator;
            mAnimator = null;
            if (running != null) running.cancel();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (mVisible <= 0f || b.isEmpty()) return;
            float[] g = geometry(Math.min(b.width(), b.height()), mRtl);
            float cx = b.left + g[0];
            float cy = b.top + g[1];
            float r = g[2] * mScale * (FROM_SCALE + (1f - FROM_SCALE) * mVisible);
            canvas.save();
            canvas.rotate(mRotation, cx, cy);
            mDisc.setAlpha(Math.round(Color.alpha(DISC_COLOR) * mVisible));
            canvas.drawCircle(cx, cy, r, mDisc);
            mRing.setAlpha(Math.round(Color.alpha(RING_COLOR) * mVisible));
            canvas.drawCircle(cx, cy, r, mRing);
            if (mGlyph != null) {
                int half = Math.round(r * GLYPH_FRACTION);
                int x = Math.round(cx);
                int y = Math.round(cy);
                mGlyph.setBounds(x - half, y - half, x + half, y + half);
                mGlyph.setAlpha(Math.round(255 * mVisible));
                mGlyph.draw(canvas);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
