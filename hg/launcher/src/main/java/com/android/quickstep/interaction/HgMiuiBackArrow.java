// HyperGestures: stock HyperOS's back indicator (MiuiHome 7.50's GestureBackArrowView, ported
// as is to HOS4's Rust core), drawn by EdgeBackGesturePanel (aosp-patch) instead of AOSP's
// arrow unless Settings.Global hypergestures_back_style is "0". A dark Gaussian bulge at the
// edge whose WIDTH alone stretches with the swipe (HgBackArrowMath.stretchScale), anchored 20 px
// above the touch-down and not following the finger, and a white "<" chevron centred in it that
// fades in once the swipe is armed (past 180 px, with HgBackHaptics' ready tick). On release
// (back or cancel) the chevron goes at once and the bulge collapses to the edge in 100 ms; the
// panel is then GONE at once (MiuiHome waits 500 ms more with nothing left to draw; GONE
// sooner unmaps the overlay window sooner).
// Both shapes are our own Paths (no Xiaomi bitmaps), built once per density; MiuiHome's right
// edge rotates the bulge 180 deg (a mirror: the Gaussian is symmetric) but not the arrow, so the
// chevron points "<" on both edges. Nothing animates or draws while the panel is GONE.
package com.android.quickstep.interaction;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class HgMiuiBackArrow {
    /** Rows sampled along the Gaussian edge (one lineTo each; ~9 px apart at 480 dpi). */
    private static final int BULGE_SAMPLES = 96;
    private static final int BULGE_COLOR = 0xCC000000;
    private static final int CHEVRON_COLOR = 0xFFFFFFFF;

    private final View mHost;
    private final Paint mBulgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The bulge at scale 1, from x = 0 (the edge) to the right, y = 0..height. */
    private final Path mBulge = new Path();
    /** The chevron centred on (0, 0). */
    private final Path mChevron = new Path();
    private final ValueAnimator mAlphaAnim = ValueAnimator.ofInt(0, 0);
    private final ValueAnimator mCollapseAnim = ValueAnimator.ofFloat(0f, 0f);

    private float mDensity = -1;
    private float mBulgeWidth;
    private int mViewWidth;
    private int mViewHeight;

    private float mScale;
    private int mArrowAlpha;
    private boolean mArmed;
    private boolean mCollapseCanceled;

    HgMiuiBackArrow(View host) {
        mHost = host;
        mBulgePaint.setStyle(Paint.Style.FILL);
        mBulgePaint.setColor(BULGE_COLOR);
        mChevronPaint.setStyle(Paint.Style.STROKE);
        mChevronPaint.setStrokeCap(Paint.Cap.ROUND);
        mChevronPaint.setStrokeJoin(Paint.Join.ROUND);
        mChevronPaint.setColor(CHEVRON_COLOR);

        mAlphaAnim.setInterpolator(new DecelerateInterpolator(HgBackArrowMath.FADE_DECELERATE));
        mAlphaAnim.addUpdateListener(a -> {
            mArrowAlpha = (int) a.getAnimatedValue();
            mHost.invalidate();
        });
        mCollapseAnim.setDuration(HgBackArrowMath.COLLAPSE_MS);
        mCollapseAnim.setInterpolator(new DecelerateInterpolator());
        mCollapseAnim.addUpdateListener(a -> {
            mScale = (float) a.getAnimatedValue();
            mHost.invalidate();
        });
        mCollapseAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mCollapseCanceled = false;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCollapseCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCollapseCanceled) mHost.setVisibility(View.GONE);
            }
        });
        loadDimens(host.getResources().getDisplayMetrics().density);
    }

    /** Builds the Paths for this density; a no-op if it did not change (configuration changes). */
    void loadDimens(float density) {
        if (density == mDensity) return;
        mDensity = density;
        mBulgeWidth = HgBackArrowMath.BULGE_WIDTH_DP * density;
        float height = HgBackArrowMath.BULGE_HEIGHT_DP * density;
        float sigma = HgBackArrowMath.BULGE_SIGMA_DP * density;
        mViewWidth = (int) Math.ceil(mBulgeWidth * HgBackArrowMath.MAX_SCALE + 2 * density);
        mViewHeight = Math.round(height);

        mBulge.rewind();
        mBulge.moveTo(0, 0);
        for (int i = 0; i <= BULGE_SAMPLES; i++) {
            float y = height * i / BULGE_SAMPLES;
            mBulge.lineTo(HgBackArrowMath.bulgeProfile(y, height, mBulgeWidth, sigma), y);
        }
        mBulge.lineTo(0, height);
        mBulge.close();

        // The bitmap's outer size includes the stroke, so the centre line is inset by half of it.
        float stroke = HgBackArrowMath.CHEVRON_STROKE_DP * density;
        float hw = (HgBackArrowMath.CHEVRON_WIDTH_DP * density - stroke) / 2f;
        float hh = (HgBackArrowMath.CHEVRON_HEIGHT_DP * density - stroke) / 2f;
        mChevronPaint.setStrokeWidth(stroke);
        mChevron.rewind();
        mChevron.moveTo(hw, -hh);
        mChevron.lineTo(-hw, 0);
        mChevron.lineTo(hw, hh);
    }

    int viewWidth() {
        return mViewWidth;
    }

    int viewHeight() {
        return mViewHeight;
    }

    /** A touch-down: stop the last gesture's animations and start flat at the edge. */
    void onDown() {
        mAlphaAnim.cancel();
        mCollapseAnim.cancel();
        mScale = 0;
        mArrowAlpha = 0;
        mArmed = false;
    }

    /** Whether the last MOVE armed the chevron (shown or fading in, with the ready tick). */
    boolean isArmed() {
        return mArmed;
    }

    /** The finger moved inwardTravelPx away from the edge; triggerBack is the AOSP state. */
    void onMove(float inwardTravelPx, boolean triggerBack) {
        float scale = HgBackArrowMath.stretchScale(inwardTravelPx);
        boolean armed = HgBackArrowMath.isArmed(inwardTravelPx, triggerBack);
        if (armed != mArmed) {
            mArmed = armed;
            mAlphaAnim.cancel();
            mAlphaAnim.setIntValues(mArrowAlpha, HgBackArrowMath.arrowTargetAlpha(armed));
            mAlphaAnim.setDuration(HgBackArrowMath.arrowFadeMs(armed));
            mAlphaAnim.start();
        }
        if (scale != mScale) {
            mScale = scale;
            mHost.invalidate();
        }
    }

    /** Release, back or cancel alike: the chevron goes at once, the bulge collapses, then GONE. */
    void onRelease(float inwardTravelPx) {
        mAlphaAnim.cancel();
        mArmed = false;
        mArrowAlpha = 0;
        mCollapseAnim.cancel();
        mScale = HgBackArrowMath.stretchScale(inwardTravelPx);
        if (mScale <= 0) {
            mHost.setVisibility(View.GONE);
            return;
        }
        mCollapseAnim.setFloatValues(mScale, 0f);
        mCollapseAnim.start();
    }

    /** The panel is torn down: no animator may outlive it. */
    void cancel() {
        mAlphaAnim.cancel();
        mCollapseAnim.cancel();
    }

    void draw(Canvas canvas, boolean leftEdge, int width, int height) {
        if (mScale <= 0) return;
        float edgeX = leftEdge ? 0 : width;
        canvas.save();
        canvas.translate(edgeX, (height - mViewHeight) / 2f);
        canvas.scale(leftEdge ? mScale : -mScale, 1f);
        canvas.drawPath(mBulge, mBulgePaint);
        canvas.restore();

        if (!HgBackArrowMath.drawsArrow(mArrowAlpha, mScale)) return;
        float half = HgBackArrowMath.bulgeWidthPx(mDensity, mScale) / 2f;
        mChevronPaint.setAlpha(HgBackArrowMath.arrowPaintAlpha(mArrowAlpha));
        canvas.save();
        canvas.translate(leftEdge ? half : width - half, height / 2f);
        canvas.drawPath(mChevron, mChevronPaint);
        canvas.restore();
    }
}
