/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under CC BY-NC-SA 4.0; see LICENSE and NOTICE.
 */
package com.hypergestures.notif;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

/**
 * Turns an unusable notification small icon into a proper alpha glyph.
 *
 * <p>The status bar tints small icons to a single flat foreground color, keeping only their alpha
 * channel. That works for the monochrome glyph the platform asks apps for, but plenty of apps pass
 * a full-color image instead — {@code bg.ubb.mobile}, for instance, passes its launcher PNG
 * verbatim ({@code icon=Icon(typ=RESOURCE pkg=bg.ubb.mobile id=0x7f100025)}, which resolves to
 * {@code mipmap/ic_launcher}). Every pixel of such an image is opaque, so the tint flattens it into
 * a solid white square and the logo disappears.
 *
 * <p>The fix separates figure from ground and keeps only the figure's alpha: the plate color is
 * measured from the artwork's own border, then each pixel's alpha is set from how far its color is
 * from that plate. A white plate with a dark logo becomes a transparent field with the logo's
 * silhouette, which is exactly what the tint expects.
 *
 * <p>Note this is the opposite approach to generating a <em>colored</em> icon, which was tried and
 * fails on HyperOS 3.3: the tint flattens color, so any plate we draw returns as a white blob. This
 * works <em>with</em> the tint instead of against it.
 */
final class SmallIconFixer {

    /** Largest icon we generate; small icons are displayed far below this. */
    private static final int MAX_EDGE = 192;

    /**
     * Opaque fraction above which an icon would tint to a solid blob. A real glyph covers well
     * under half its box; a plated logo covers nearly all of it.
     */
    private static final float BLOB_COVERAGE = 0.80f;

    /**
     * Color distance from the plate below which a pixel is still background. Legacy icons commonly
     * bake in a drop shadow and an antialiased outline, whose grey sits a little off the plate
     * color; without this floor those pixels survive as a thin frame around the glyph.
     */
    private static final int NOISE_FLOOR = 48;

    /** Color distance from the plate at which a pixel counts as fully part of the figure. */
    private static final int FIGURE_DISTANCE = 120;

    /** A silhouette outside this coverage range means the separation failed. */
    private static final float MIN_RESULT_COVERAGE = 0.02f;
    private static final float MAX_RESULT_COVERAGE = 0.60f;

    /**
     * How far from square a silhouette may be before it is treated as a wordmark rather than a
     * glyph. A stacked logo-plus-name (UBB's, which reads fine) stays near 1; a single line of text
     * does not.
     */
    private static final float MAX_GLYPH_ASPECT = 2.2f;

    /**
     * Smallest share of a wordmark's width its leading element may hold and still be used
     * alone.
     */
    private static final float MIN_LEAD_WIDTH = 0.10f;

    /** Transparent margin left around the finished glyph, per side. */
    private static final float SQUARE_MARGIN = 0.06f;

    /** Gap between the two lines of a stacked wordmark, as a fraction of a line's height. */
    private static final float LINE_GAP = 0.12f;

    private SmallIconFixer() {
    }

    /**
     * @param appCtx a context for the posting app, so the icon's resource resolves
     * @return a replacement icon, or null when the original is already fine or cannot be improved,
     *     in which case the caller must leave the notification alone
     */
    static Icon fix(Context appCtx, Icon small, String key) {
        if (appCtx == null || small == null) {
            return null;
        }
        Drawable d;
        try {
            d = small.loadDrawable(appCtx);
        } catch (Throwable t) {
            Logger.v(key + ": small icon did not load (" + t + "), leaving it alone");
            return null;
        }
        Bitmap glyph = fixArtwork(d, key);
        return glyph == null ? null : Icon.createWithBitmap(glyph);
    }

    /**
     * Same fix applied to a drawable rather than an {@link Icon}, for the always-on display.
     *
     * <p>The AOD lives in {@code com.miui.aod}, a separate process with its own
     * {@code NotificationListenerService}, so it reads the app's original small icon and never sees
     * the replacement made inside SystemUI. Its {@code NotificationUtils.createIconWithBackground}
     * composes an oval plate with the glyph tinted SRC_ATOP — which turns an opaque image into a
     * solid block. Fixing the glyph on the way in lets MIUI keep drawing its own plate and tint.
     *
     * @return a replacement drawable, or null to leave the original alone
     */
    static Drawable fixDrawable(Drawable original, String key) {
        Bitmap glyph = fixArtwork(original, key);
        return glyph == null ? null : new BitmapDrawable(Resources.getSystem(), glyph);
    }

    /** @return the corrected glyph, or null when the artwork is already fine or unusable */
    private static Bitmap fixArtwork(Drawable drawable, String key) {
        Bitmap art;
        try {
            art = Bitmaps.trim(Bitmaps.pixels(drawable, MAX_EDGE));
        } catch (Throwable t) {
            Logger.v(key + ": small icon did not render (" + t + "), leaving it alone");
            return null;
        }
        if (art == null || art.getWidth() < 8 || art.getHeight() < 8) {
            return null;
        }

        float coverage = Bitmaps.coverage(art);
        if (!wouldBlob(coverage)) {
            Logger.v(key + ": small icon is a glyph already (coverage="
                    + Math.round(coverage * 100) + "%), leaving it alone");
            return null;
        }

        int plate = Bitmaps.borderColor(art);
        if (plate == 0) {
            Logger.v(key + ": small icon is solid (coverage=" + Math.round(coverage * 100)
                    + "%) but has no single plate color to subtract, leaving it alone");
            return null;
        }

        Bitmap glyph = legible(silhouette(art, plate), key);
        float result = Bitmaps.coverage(glyph);
        if (!usableSilhouette(result)) {
            Logger.v(key + ": silhouette against plate " + Logger.hex(plate) + " came out at "
                    + Math.round(result * 100) + "% coverage, outside the usable range,"
                    + " leaving it alone");
            return null;
        }

        Bitmaps.dump(key + "-small-src", art);
        Bitmaps.dump(key + "-small-out", glyph);
        Logger.i(key + ": small icon was a " + Math.round(coverage * 100) + "% solid block on "
                + Logger.hex(plate) + " -> silhouette at " + Math.round(result * 100)
                + "% coverage");
        return glyph;
    }

    /**
     * Makes a silhouette readable at the size it is actually displayed.
     *
     * <p>A small icon is drawn into a box about 24dp across (on the lock screen and AOD, inside a
     * circular plate). Two things then matter and neither is handled by the silhouette itself:
     *
     * <ol>
     *   <li>The ink sits wherever it sat in the source icon, usually with a wide transparent
     *       margin, so it is scaled down further than necessary. Trimming and recentering fixes
     *       that.
     *   <li>Some brands are <b>wordmarks</b>: {@code bg.epay.app}'s icon is the text "ePay.bg",
     *       whose ink is 184×52 — an aspect of 3.5. Shrunk into a 24dp circle that is a bar,
     *       whatever mask is used, because the content itself is a line of text. For those, the
     *       leading element of the artwork is used instead: for "ePay.bg" the "e", which is the
     *       brand's own initial and fills the box legibly.
     * </ol>
     */
    private static Bitmap legible(Bitmap glyph, String key) {
        Bitmap ink = Bitmaps.trim(glyph);
        if (ink == null) {
            return glyph;
        }
        float aspect = AppIconShaper.aspect(ink.getWidth(), ink.getHeight());
        if (isWordmark(aspect)) {
            // Keep as much of the wordmark as possible: stacking it over two lines squares it up
            // without dropping any of it, which reads better than a single leading letter. Only if
            // there is no usable split does it fall back to the leading element.
            Bitmap stacked = stackTwoLines(ink);
            if (stacked != null) {
                Logger.i(key + ": small icon artwork is a " + ink.getWidth() + "x" + ink.getHeight()
                        + " band (aspect " + Math.round(aspect * 10) / 10f
                        + "); stacked it over two lines as " + stacked.getWidth() + "x"
                        + stacked.getHeight());
                ink = stacked;
            } else {
                Bitmap lead = leadingElement(ink);
                if (lead != null) {
                    Logger.i(key + ": small icon artwork is a " + ink.getWidth() + "x"
                            + ink.getHeight() + " band (aspect " + Math.round(aspect * 10) / 10f
                            + ") with no usable split; using its leading " + lead.getWidth() + "x"
                            + lead.getHeight() + " element instead");
                    ink = lead;
                } else {
                    Logger.v(key + ": small icon is a band (aspect " + Math.round(aspect * 10) / 10f
                            + ") that cannot be split; keeping all of it");
                }
            }
        }
        return square(ink);
    }

    /**
     * Splits a wordmark into two lines at the gap nearest its middle and stacks them, which squares
     * up the artwork while keeping all of it.
     *
     * @return the stacked artwork, or null when there is no gap to split at
     */
    private static Bitmap stackTwoLines(Bitmap ink) {
        int w = ink.getWidth();
        int h = ink.getHeight();
        int[][] runs = inkRuns(ink);
        if (runs == null || runs.length < 2) {
            return null;
        }
        int best = splitIndex(runs, w);
        if (best < 0) {
            return null;
        }

        Bitmap top = Bitmaps.trim(Bitmap.createBitmap(ink, 0, 0, runs[best - 1][1], h));
        Bitmap bottom = Bitmaps.trim(
                Bitmap.createBitmap(ink, runs[best][0], 0, w - runs[best][0], h));
        if (top == null || bottom == null) {
            return null;
        }

        int gap = Math.max(1, Math.round(Math.max(top.getHeight(), bottom.getHeight()) * LINE_GAP));
        int outW = Math.max(top.getWidth(), bottom.getWidth());
        int outH = top.getHeight() + gap + bottom.getHeight();
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(top, (outW - top.getWidth()) / 2f, 0, paint);
        canvas.drawBitmap(bottom, (outW - bottom.getWidth()) / 2f,
                top.getHeight() + (float) gap, paint);

        return isWordmark(AppIconShaper.aspect(outW, outH)) ? null : out;
    }

    /**
     * Splits the artwork at its vertical gaps and returns the first element — the initial letter of
     * a wordmark. Falls back to the element carrying the most ink when the first one is too small
     * or too thin to stand alone (a stray dot or bar), and to null when there is nothing to split.
     */
    private static Bitmap leadingElement(Bitmap ink) {
        int[][] runs = inkRuns(ink);
        if (runs == null || runs.length < 2) {
            return null;
        }
        int w = ink.getWidth();
        int h = ink.getHeight();

        int start = runs[0][0];
        int end = runs[0][1];
        int width = end - start;
        float leadAspect = (float) Math.max(width, h) / Math.max(1, Math.min(width, h));
        if ((float) width / w < MIN_LEAD_WIDTH || leadAspect > MAX_GLYPH_ASPECT) {
            int[] richest = runs[0];
            for (int[] run : runs) {
                if (run[2] > richest[2]) {
                    richest = run;
                }
            }
            start = richest[0];
            end = richest[1];
        }
        return Bitmaps.trim(Bitmap.createBitmap(ink, start, 0, end - start, h));
    }

    /**
     * Columns of ink separated by blank gaps, as {@code {start, end, ink}} triples — the letters,
     * or the separable elements, of the artwork.
     *
     * <p>A gap has to be a couple of pixels wide so a single glyph is not split at, say, the waist
     * of an "n".
     */
    private static int[][] inkRuns(Bitmap ink) {
        int w = ink.getWidth();
        int h = ink.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] px = new int[w * h];
        ink.getPixels(px, 0, w, 0, 0, w, h);

        long[] column = new long[w];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int a = Color.alpha(px[row + x]);
                if (a > Bitmaps.ALPHA_FLOOR) {
                    column[x] += a;
                }
            }
        }
        return runs(column);
    }

    /**
     * HyperGestures: {@link #inkRuns}' gap scan over the per-column ink sums, as a plain function
     * (unit-tested).
     */
    static int[][] runs(long[] column) {
        int w = column.length;
        int minGap = Math.max(2, Math.round(w * 0.015f));
        java.util.List<int[]> runs = new java.util.ArrayList<>();
        int runStart = -1;
        int gap = 0;
        long runInk = 0;
        for (int x = 0; x <= w; x++) {
            boolean inked = x < w && column[x] > 0;
            if (inked) {
                if (runStart < 0) {
                    runStart = x;
                    runInk = 0;
                }
                runInk += column[x];
                gap = 0;
            } else if (runStart >= 0) {
                gap++;
                if (gap >= minGap || x == w) {
                    runs.add(new int[] {runStart, x - gap + 1,
                            (int) Math.min(runInk, Integer.MAX_VALUE)});
                    runStart = -1;
                }
            }
        }
        return runs.isEmpty() ? null : runs.toArray(new int[0][]);
    }

    /**
     * Centres artwork in a square canvas with a small margin, so nothing scales it down
     * further.
     */
    private static Bitmap square(Bitmap ink) {
        int edge = Math.round(
                Math.max(ink.getWidth(), ink.getHeight()) / (1f - 2f * SQUARE_MARGIN));
        Bitmap out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float usable = edge * (1f - 2f * SQUARE_MARGIN);
        float scale = Math.min(usable / ink.getWidth(), usable / ink.getHeight());
        float w = ink.getWidth() * scale;
        float h = ink.getHeight() * scale;
        RectF dst = new RectF((edge - w) / 2f, (edge - h) / 2f, (edge + w) / 2f, (edge + h) / 2f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(ink, null, dst, paint);
        return out;
    }

    // HyperGestures: the gates and the per-pixel rule as plain functions (unit-tested).

    /** Whether an icon is opaque enough to tint to a solid block. */
    static boolean wouldBlob(float coverage) {
        return coverage >= BLOB_COVERAGE;
    }

    /** Whether a silhouette's coverage shows the figure was separated from the plate. */
    static boolean usableSilhouette(float coverage) {
        return coverage >= MIN_RESULT_COVERAGE && coverage <= MAX_RESULT_COVERAGE;
    }

    /** Whether artwork is too far from square to read as a glyph (a line of text). */
    static boolean isWordmark(float aspect) {
        return aspect > MAX_GLYPH_ASPECT;
    }

    /**
     * The run index to split a wordmark before: the gap nearest the middle, so the two lines come
     * out even. -1 with fewer than two runs.
     */
    static int splitIndex(int[][] runs, int width) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 1; i < runs.length; i++) {
            int cut = (runs[i - 1][1] + runs[i][0]) / 2;
            int distance = Math.abs(cut - width / 2);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * A silhouette pixel's alpha from its color distance to the plate: 0 up to the noise floor,
     * opaque from {@link #FIGURE_DISTANCE}, linear between; never more opaque than the source.
     */
    static int figureAlpha(int plateDistance, int srcAlpha) {
        int dist = plateDistance - NOISE_FLOOR;
        if (dist <= 0) {
            return 0;
        }
        int span = FIGURE_DISTANCE - NOISE_FLOOR;
        int alpha = dist >= span ? 255 : dist * 255 / span;
        // A translucent source pixel cannot become more opaque than it started.
        return alpha * srcAlpha / 255;
    }

    /**
     * Keeps only how far each pixel is from the plate color, as alpha on white. White because the
     * status bar replaces the color anyway, and it reads correctly in the shade header too.
     */
    private static Bitmap silhouette(Bitmap art, int plate) {
        int w = art.getWidth();
        int h = art.getHeight();
        int[] px = new int[w * h];
        art.getPixels(px, 0, w, 0, 0, w, h);

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int srcAlpha = Color.alpha(p);
            if (srcAlpha <= Bitmaps.ALPHA_FLOOR) {
                px[i] = Color.TRANSPARENT;
                continue;
            }
            int alpha = figureAlpha(Bitmaps.distance(p, plate), srcAlpha);
            px[i] = alpha == 0 ? Color.TRANSPARENT : Color.argb(alpha, 255, 255, 255);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
}
