// HyperGestures: one moving miniature on the settings page's layout picker
// (HgLayoutPickerPreference): HgLayoutPreview's cards, drawn as plain translucent white panes with
// a header bar above each (owner, 2026-10-31: minimal, "aero white", not colourful) on a dimmed
// screen. Each pane sits on a solid base in the screen's colour, so a card in front hides the
// ones behind it (the stack) instead of showing them through. It moves only while attached and shown, and only when the system runs animations
// (ValueAnimator.areAnimatorsEnabled: off when "Remove animations" is set); otherwise it shows
// the cycle's first frame; scrolled off the page, the list detaches it and it stops. Its height
// follows its width (9:16).
package com.android.quickstep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.R;

import java.util.List;

public final class HgLayoutPreviewView extends View {
    /** The screen's shape, as a phone held upright. */
    private static final float ASPECT = 16f / 9f;

    private final Paint mScreen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCard = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHeader = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mClip = new Path();
    private final float mScreenRadius;
    private final float mCardRadius;
    private final int mBaseAlpha;
    private final int mCardAlpha;
    private final int mStrokeAlpha;
    private final int mHeaderAlpha;

    private int mMode = HgLayoutPreview.STACK;
    private float mT;
    private ValueAnimator mAnimator;

    public HgLayoutPreviewView(Context context) {
        this(context, null);
    }

    public HgLayoutPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mScreenRadius = 14 * density;
        mCardRadius = 5 * density;
        mScreen.setColor(context.getColor(R.color.hg_preview_screen));
        mBase.setColor(context.getColor(R.color.hg_preview_card_base));
        mCard.setColor(context.getColor(R.color.hg_preview_card));
        mStroke.setColor(context.getColor(R.color.hg_preview_card_stroke));
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(density);
        mHeader.setColor(context.getColor(R.color.hg_preview_header));
        mBaseAlpha = mBase.getAlpha();
        mCardAlpha = mCard.getAlpha();
        mStrokeAlpha = mStroke.getAlpha();
        mHeaderAlpha = mHeader.getAlpha();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the tile speaks
    }

    /** Which layout it shows (HgLayoutPreview.VERTICAL, HORIZONTAL or STACK). */
    public void setMode(int mode) {
        if (mMode == mode) return;
        mMode = mode;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.round(width * ASPECT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mClip.reset();
        mRect.set(0, 0, w, h);
        mClip.addRoundRect(mRect, mScreenRadius, mScreenRadius, Path.Direction.CW);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && isAttachedToWindow()) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void start() {
        if (mAnimator != null || !ValueAnimator.areAnimatorsEnabled()) return;
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(HgLayoutPreview.CYCLE_MS);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> {
            mT = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    private void stop() {
        if (mAnimator == null) return;
        mAnimator.cancel();
        mAnimator = null;
        mT = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.save();
        canvas.clipPath(mClip);
        canvas.drawRect(0, 0, w, h, mScreen);
        List<HgLayoutPreview.Card> cards = HgLayoutPreview.frame(mMode, mT);
        float header = 0.03f * h;
        for (int i = 0; i < cards.size(); i++) {
            HgLayoutPreview.Card c = cards.get(i);
            mBase.setAlpha(Math.round(mBaseAlpha * c.alpha));
            mCard.setAlpha(Math.round(mCardAlpha * c.alpha));
            mStroke.setAlpha(Math.round(mStrokeAlpha * c.alpha));
            mHeader.setAlpha(Math.round(mHeaderAlpha * c.alpha));
            float left = c.x * w;
            float top = c.y * h;
            // The header: an icon-sized bar above the card, as on the real cards.
            mRect.set(left + 0.06f * c.w * w, top - header * 1.7f,
                    left + 0.32f * c.w * w, top - header * 0.7f);
            canvas.drawRoundRect(mRect, header / 2, header / 2, mHeader);
            mRect.set(left, top, left + c.w * w, top + c.h * h);
            canvas.drawRoundRect(mRect, mCardRadius, mCardRadius, mBase);
            canvas.drawRoundRect(mRect, mCardRadius, mCardRadius, mCard);
            canvas.drawRoundRect(mRect, mCardRadius, mCardRadius, mStroke);
        }
        canvas.restore();
    }
}
