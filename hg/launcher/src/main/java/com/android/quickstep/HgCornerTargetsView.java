// HyperGestures: the two corner targets of HgCornerDrag, on the recents window while an app's
// window is dragged up: split screen top-left, floating window top-right, drawn as MiuiHome draws
// its small-window target (HgCornerGeometry). Each is a frosted circle that fades in and grows as
// the finger nears its corner; held over, it morphs on the hold spring: the floating one into a
// frame 12 dp around the card with a soft glowing pink-peach-orange rim (HyperOS 4), the split one
// into a frosted iridescent panel over the top half with the split glyph at its top centre.
// Not touchable, no accessibility (a gesture aid only).
//
// Battery: one View, everything pre-allocated; it redraws only on finger moves and spring frames
// (no time-animated shader, no loops), and is removed when the finger lifts. The panel's colours
// are static soft blobs in an AGSL shader; the glow is a blurred stroke with a sweep gradient.
// Both shaders and the blur are built once per process; draws move the canvas to follow the
// morph and the card, never the shaders' matrices.
// Without blur behind recents (HgBlurSupport: lower-end phones, battery saver) the frost would be
// unreadable over a sharp wallpaper and the effects cost the most there: the circles and the frame
// take a solid fill, the panel a plain gradient instead of the AGSL shader, and the glow layered
// translucent strokes instead of the BlurMaskFilter (neither the shader nor the blur is built).
package com.android.quickstep;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;

final class HgCornerTargetsView extends View {
    private static final String TAG = "HgCornerTargets";
    private static final long DETACH_FADE_MS = 90;
    /** [V] The circle's springs (MiuiHome SpringParamsProvider CIRCLE_SMALL_VIEW). */
    private static final float CENTER_DAMPING = 1f;
    private static final float CENTER_RESPONSE = 0.45f;
    private static final float SIZE_DAMPING = 0.93f;
    private static final float SIZE_RESPONSE = 0.55f;
    private static final float ALPHA_DAMPING = 1f;
    private static final float ALPHA_RESPONSE = 0.25f;
    /** [V] new_smallwindow_rect's fill over a dark wallpaper, and over a light one. */
    private static final int FILL_DARK_WALLPAPER = 0x33FFFFFF;
    private static final int FILL_LIGHT_WALLPAPER = 0x26000000;
    /** [G] The floating frame's rim, from the recording: pink, peach, orange, back to pink. */
    private static final int[] GLOW_COLORS = {0xFFFF8FC7, 0xFFFFB38A, 0xFFFF9A4D, 0xFFFF8FC7};
    private static final float STROKE_DP = 2f;
    private static final float GLOW_STROKE_DP = 4f;
    private static final float GLOW_BLUR_DP = 9f;
    /** [G] The panel's fill if its shader is missing. */
    private static final int PANEL_FALLBACK = 0x59FFFFFF;
    /** [G] Without blur: the circles' and the frame's fill (hg_solid_fill, MiuiHome's disc). */
    private static final int SOLID_FILL = 0xE63B3B3B;
    /** [G] Without blur: the panel's plain gradient, warm top-left to cool bottom-right. */
    private static final int PANEL_SOLID_START = 0xE65A4840;
    private static final int PANEL_SOLID_END = 0xE6404A5E;
    /** [G] Without blur: the glow's layered strokes, outermost first (width factor, alpha). */
    private static final float[] GLOW_LAYER_WIDTHS = {3.5f, 2.25f};
    private static final float[] GLOW_LAYER_ALPHAS = {0.12f, 0.25f};
    /** [G] The panel's 1 dp rim. */
    private static final int PANEL_RIM = 0x4DFFFFFF;
    private static final float PANEL_RIM_DP = 1f;
    /**
     * [G] The split panel: five soft colour blobs (orange, peach, blue, pink, green) over a
     * translucent white, in the panel's unit square (the local matrix maps it onto the panel).
     * Static colours: nothing here changes with time.
     */
    private static final String PANEL_SHADER = ""
            + "half3 blob(float2 p, float2 c, float r, half3 col, inout float w) {\n"
            + "  float k = exp(-dot(p - c, p - c) / (r * r));\n"
            + "  w += k;\n"
            + "  return col * half(k);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  float w = 0.0;\n"
            + "  half3 c = blob(p, float2(0.12, 0.18), 0.42, half3(1.0, 0.69, 0.44), w);\n"
            + "  c += blob(p, float2(0.55, 0.08), 0.38, half3(1.0, 0.83, 0.72), w);\n"
            + "  c += blob(p, float2(0.92, 0.35), 0.40, half3(0.56, 0.72, 1.0), w);\n"
            + "  c += blob(p, float2(0.30, 0.85), 0.42, half3(1.0, 0.62, 0.82), w);\n"
            + "  c += blob(p, float2(0.80, 0.90), 0.36, half3(0.66, 0.90, 0.72), w);\n"
            + "  half k = half(clamp(w, 0.0, 1.0));\n"
            + "  half3 col = mix(half3(1.0), c / half(max(w, 0.001)), k);\n"
            + "  half a = 0.2 + 0.22 * k;\n"
            + "  return half4(col * a, a);\n"
            + "}\n";

    private final float mWidth;
    private final float mHeight;
    private final float mDensity;
    private final Corner mSplit;
    private final Corner mFloating;
    private float mSplitHold;
    private float mFloatingHold;
    /** The card's screen rect and corner radius (the floating frame goes around it). */
    private final float[] mCard = new float[4];
    private float mCardRadius;
    private boolean mHasCard;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Built once per process on the main thread, on the first corner shown (not per swipe):
    // the panel's shader (null if it did not compile: a plain frosted fill instead), with its
    // local matrix set only when the panel's size changes; the glow's sweep gradient about
    // (0, 0) and its blur. Draws move the canvas instead of the shaders' matrices (setting a
    // local matrix rebuilds the native shader).
    private static boolean sPanelShaderTried;
    @Nullable private static RuntimeShader sPanelShader;
    private static float sPanelShaderWidth;
    private static float sPanelShaderHeight;
    @Nullable private static SweepGradient sSweep;
    @Nullable private static BlurMaskFilter sGlowBlur;
    private static float sGlowBlurDensity;

    @Nullable private final RuntimeShader mPanelShader;
    /** Whether recents blurs behind the targets (HgBlurSupport): the frosted look and effects. */
    private final boolean mFrosted;
    private final float mStatusBar;
    private final int mFillColor;
    private final float[] mPanel = new float[4];
    private final float[] mTmp = new float[4];
    private final float[] mFrom = new float[4];
    private final float[] mTo = new float[4];
    private final RectF mRect = new RectF();

    private HgCornerTargetsView(Context context, float width, float height, float statusBar) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mWidth = width;
        mHeight = height;
        mDensity = context.getResources().getDisplayMetrics().density;
        mStatusBar = statusBar;
        // MiuiHome's WallpaperUtil.hasAppliedLightWallpaper: dark text = a light wallpaper.
        mFrosted = HgBlurSupport.isFrosted(context);
        if (!mFrosted) {
            mFillColor = SOLID_FILL;
        } else {
            mFillColor = Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)
                    ? FILL_LIGHT_WALLPAPER : FILL_DARK_WALLPAPER;
        }
        mSplit = new Corner(context, R.drawable.ic_split_horizontal);
        mFloating = new Corner(context, R.drawable.hg_ic_task_floating_window);
        HgCornerGeometry.panel(width, height, mPanel);
        mPanelShader = mFrosted ? panelShader(mPanel[2] - mPanel[0], mPanel[3] - mPanel[1])
                : null;
        mFillPaint.setStyle(Paint.Style.FILL);
        mPanelPaint.setStyle(Paint.Style.FILL);
        if (mPanelShader != null) {
            mPanelPaint.setShader(mPanelShader);
        } else if (!mFrosted) {
            // Laid out for the full panel, as the shader: drawSplit scales the canvas.
            mPanelPaint.setShader(new LinearGradient(0, 0, mPanel[2] - mPanel[0],
                    mPanel[3] - mPanel[1], PANEL_SOLID_START, PANEL_SOLID_END,
                    Shader.TileMode.CLAMP));
        } else {
            mPanelPaint.setColor(PANEL_FALLBACK);
        }
        mRimPaint.setStyle(Paint.Style.STROKE);
        mRimPaint.setStrokeWidth(PANEL_RIM_DP * mDensity);
        mRimPaint.setColor(PANEL_RIM);
        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setStrokeWidth(GLOW_STROKE_DP * mDensity);
        if (mFrosted) mGlowPaint.setMaskFilter(glowBlur(mDensity));
        mGlowPaint.setShader(sweep());
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(STROKE_DP * mDensity);
        mStrokePaint.setShader(sweep());
    }

    /** The shared panel shader, mapping its unit square onto a {@code w} x {@code h} panel. */
    @Nullable
    private static RuntimeShader panelShader(float w, float h) {
        if (!sPanelShaderTried) {
            sPanelShaderTried = true;
            try {
                sPanelShader = new RuntimeShader(PANEL_SHADER);
            } catch (RuntimeException e) {
                Log.w(TAG, "panel shader failed to compile; a plain fill instead", e);
            }
        }
        if (sPanelShader != null && (w != sPanelShaderWidth || h != sPanelShaderHeight)) {
            sPanelShaderWidth = w;
            sPanelShaderHeight = h;
            Matrix m = new Matrix();
            m.setScale(Math.max(1f, w), Math.max(1f, h));
            sPanelShader.setLocalMatrix(m);
        }
        return sPanelShader;
    }

    private static SweepGradient sweep() {
        if (sSweep == null) sSweep = new SweepGradient(0, 0, GLOW_COLORS, null);
        return sSweep;
    }

    private static BlurMaskFilter glowBlur(float density) {
        if (sGlowBlur == null || sGlowBlurDensity != density) {
            sGlowBlurDensity = density;
            sGlowBlur = new BlurMaskFilter(GLOW_BLUR_DP * density, BlurMaskFilter.Blur.NORMAL);
        }
        return sGlowBlur;
    }

    /** Adds the targets over {@code recentsView}'s drag layer; null if it has none yet. */
    @Nullable
    static HgCornerTargetsView attach(RecentsView<?, ?> recentsView, float width, float height) {
        RecentsViewContainer container =
                RecentsViewContainer.containerFromContext(recentsView.getContext());
        BaseDragLayer<?> dragLayer = container.getDragLayer();
        if (dragLayer == null) return null;
        // The status bar's height, read once (the layer is attached; this view is not yet).
        WindowInsets insets = dragLayer.getRootWindowInsets();
        float statusBar = insets == null ? 0f
                : insets.getInsets(WindowInsets.Type.statusBars()).top;
        HgCornerTargetsView view =
                new HgCornerTargetsView(recentsView.getContext(), width, height, statusBar);
        // Drag-layer coordinates: no inset margins (InsettableFrameLayout adds them otherwise).
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                BaseDragLayer.LayoutParams.MATCH_PARENT, BaseDragLayer.LayoutParams.MATCH_PARENT);
        lp.ignoreInsets = true;
        dragLayer.addView(view, lp);
        return view;
    }

    /**
     * The finger's approach to each corner (0 for a corner whose action can't be offered: its
     * target stays hidden). The circles spring toward their places, sizes and alphas.
     */
    void update(float splitApproach, float floatingApproach) {
        mSplit.update(false, splitApproach, mStatusBar);
        mFloating.update(true, floatingApproach, mStatusBar);
        invalidate();
    }

    /** Hold progress per corner (HgCornerDrag's springs): the morph. */
    void setHold(float split, float floating) {
        if (split == mSplitHold && floating == mFloatingHold) return;
        mSplitHold = split;
        mFloatingHold = floating;
        invalidate();
    }

    /** The card's screen rect and corner radius, after the window was applied. */
    void setCard(float left, float top, float right, float bottom, float radius) {
        mCard[0] = left;
        mCard[1] = top;
        mCard[2] = right;
        mCard[3] = bottom;
        mCardRadius = radius;
        mHasCard = true;
        if (mFloatingHold > 0f) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawSplit(canvas);
        drawFloating(canvas);
    }

    /** The split corner: circle to panel, the glyph moving to the panel's top centre. */
    private void drawSplit(Canvas canvas) {
        Corner c = mSplit;
        float p = mSplitHold;
        float alpha = Math.max(c.alpha(), p);
        if (alpha <= 0.004f) return;
        c.circle(mFrom);
        HgCornerGeometry.lerp(mFrom, mPanel, p, mTo);
        mRect.set(mTo[0], mTo[1], mTo[2], mTo[3]);
        float radius = HgCornerGeometry.lerp(c.size() / 2f,
                HgCornerGeometry.PANEL_RADIUS_DP * mDensity, p);
        if (p < 1f) {
            mFillPaint.setColor(mFillColor);
            mFillPaint.setAlpha(Math.round(Color.alpha(mFillColor) * alpha * (1f - p)));
            canvas.drawRoundRect(mRect, radius, radius, mFillPaint);
        }
        float panelW = mPanel[2] - mPanel[0];
        float panelH = mPanel[3] - mPanel[1];
        if (p > 0f && mRect.width() >= 1f && mRect.height() >= 1f && panelW > 0 && panelH > 0) {
            // The shader is laid out for the full panel: scale the canvas to the morphing rect
            // (the radii divided back, so the corners stay round) instead of its matrix.
            float sx = mRect.width() / panelW;
            float sy = mRect.height() / panelH;
            int panelAlpha = mFrosted && mPanelShader == null ? Color.alpha(PANEL_FALLBACK) : 255;
            mPanelPaint.setAlpha(Math.round(panelAlpha * alpha * p));
            int save = canvas.save();
            canvas.translate(mRect.left, mRect.top);
            canvas.scale(sx, sy);
            canvas.drawRoundRect(0, 0, panelW, panelH, radius / sx, radius / sy, mPanelPaint);
            canvas.restoreToCount(save);
            mRimPaint.setAlpha(Math.round(Color.alpha(PANEL_RIM) * alpha * p));
            canvas.drawRoundRect(mRect, radius, radius, mRimPaint);
        }
        float glyph = HgCornerGeometry.lerp(c.glyphSize(),
                HgCornerGeometry.PANEL_GLYPH_DP * mDensity, p);
        float cy = HgCornerGeometry.lerp(mRect.centerY(),
                mRect.top + HgCornerGeometry.PANEL_GLYPH_TOP_DP * mDensity, p);
        c.drawGlyph(canvas, mRect.centerX(), cy, glyph, alpha);
    }

    /** The floating corner: circle to a glowing frame around the card, the glyph fading. */
    private void drawFloating(Canvas canvas) {
        Corner c = mFloating;
        float p = mHasCard ? mFloatingHold : 0f;
        float alpha = Math.max(c.alpha(), p);
        if (alpha <= 0.004f) return;
        c.circle(mFrom);
        float radius = c.size() / 2f;
        if (p > 0f) {
            HgCornerGeometry.frame(mCard, mDensity, mTmp);
            HgCornerGeometry.lerp(mFrom, mTmp, p, mTo);
            radius = HgCornerGeometry.lerp(radius,
                    mCardRadius + HgCornerGeometry.FRAME_OUTSET_DP * mDensity, p);
        } else {
            System.arraycopy(mFrom, 0, mTo, 0, 4);
        }
        mRect.set(mTo[0], mTo[1], mTo[2], mTo[3]);
        mFillPaint.setColor(mFillColor);
        mFillPaint.setAlpha(Math.round(Color.alpha(mFillColor) * alpha));
        canvas.drawRoundRect(mRect, radius, radius, mFillPaint);
        float cx = mRect.centerX();
        float cy = mRect.centerY();
        if (p > 0f) {
            // The sweep is centred on (0, 0): move the canvas to the frame's centre.
            int save = canvas.save();
            canvas.translate(cx, cy);
            mRect.offset(-cx, -cy);
            if (mFrosted) {
                mGlowPaint.setAlpha(Math.round(255 * alpha * p));
                canvas.drawRoundRect(mRect, radius, radius, mGlowPaint);
            } else {
                // The cheap glow: wider, fainter strokes under the crisp one.
                for (int i = 0; i < GLOW_LAYER_WIDTHS.length; i++) {
                    mGlowPaint.setStrokeWidth(GLOW_LAYER_WIDTHS[i] * GLOW_STROKE_DP * mDensity);
                    mGlowPaint.setAlpha(Math.round(255 * GLOW_LAYER_ALPHAS[i] * alpha * p));
                    canvas.drawRoundRect(mRect, radius, radius, mGlowPaint);
                }
            }
            mStrokePaint.setAlpha(Math.round(255 * alpha * p));
            canvas.drawRoundRect(mRect, radius, radius, mStrokePaint);
            canvas.restoreToCount(save);
        }
        c.drawGlyph(canvas, cx, cy, c.glyphSize(), alpha * (1f - p));
    }

    /**
     * The finger lifted: a short fade (the frame and panel no longer follow the card, which
     * grows back over the end animation).
     */
    void detach() {
        animate().alpha(0f).setDuration(DETACH_FADE_MS).withEndAction(() -> {
            mSplit.cancel();
            mFloating.cancel();
            if (getParent() instanceof BaseDragLayer<?> parent) parent.removeView(this);
        }).start();
    }

    /** One corner's circle: springs on its centre, size and alpha, and its glyph. */
    private final class Corner {
        @Nullable private final Drawable mGlyph;
        private final HgCornerDrag.Spring mX;
        private final HgCornerDrag.Spring mY;
        private final HgCornerDrag.Spring mSize;
        private final HgCornerDrag.Spring mAlpha;
        private float mApproach;
        private boolean mPlaced;

        Corner(Context context, int glyph) {
            Drawable d = context.getDrawable(glyph);
            mGlyph = d == null ? null : d.mutate();
            if (mGlyph != null) mGlyph.setTint(Color.WHITE);
            Runnable redraw = HgCornerTargetsView.this::invalidate;
            float center = HgCornerDrag.stiffness(CENTER_RESPONSE);
            mX = new HgCornerDrag.Spring(CENTER_DAMPING, center, 1f, redraw);
            mY = new HgCornerDrag.Spring(CENTER_DAMPING, center, 1f, redraw);
            mSize = new HgCornerDrag.Spring(SIZE_DAMPING, HgCornerDrag.stiffness(SIZE_RESPONSE),
                    1f, redraw);
            mAlpha = new HgCornerDrag.Spring(ALPHA_DAMPING,
                    HgCornerDrag.stiffness(ALPHA_RESPONSE), 1f / 256, redraw);
        }

        void update(boolean right, float approach, float statusBar) {
            mApproach = approach;
            HgCornerGeometry.circle(right, approach, mWidth, mHeight, mDensity, statusBar, mTmp);
            float x = (mTmp[0] + mTmp[2]) / 2f;
            float y = (mTmp[1] + mTmp[3]) / 2f;
            float size = mTmp[2] - mTmp[0];
            if (!mPlaced) {
                // First showing: start where the finger already has it (no fly-in), faded out.
                mPlaced = true;
                mX.snap(x);
                mY.snap(y);
                mSize.snap(size);
                mAlpha.snap(0f);
            }
            mX.to(x);
            mY.to(y);
            mSize.to(size);
            mAlpha.to(HgCornerGeometry.circleAlpha(approach));
        }

        float alpha() {
            return HgCornerGeometry.clamp01(mAlpha.value());
        }

        float size() {
            return Math.max(0f, mSize.value());
        }

        float glyphSize() {
            return HgCornerGeometry.glyphSize(mApproach, mDensity);
        }

        void circle(float[] out) {
            float half = size() / 2f;
            out[0] = mX.value() - half;
            out[1] = mY.value() - half;
            out[2] = mX.value() + half;
            out[3] = mY.value() + half;
        }

        void drawGlyph(Canvas canvas, float cx, float cy, float size, float alpha) {
            if (mGlyph == null || alpha <= 0.004f) return;
            int half = Math.round(size / 2f);
            int x = Math.round(cx);
            int y = Math.round(cy);
            mGlyph.setBounds(x - half, y - half, x + half, y + half);
            mGlyph.setAlpha(Math.round(255 * HgCornerGeometry.clamp01(alpha)));
            mGlyph.draw(canvas);
        }

        void cancel() {
            mX.cancel();
            mY.cancel();
            mSize.cancel();
            mAlpha.cancel();
        }
    }
}
