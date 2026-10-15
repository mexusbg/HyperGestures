/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under CC BY-NC-SA 4.0; see LICENSE and NOTICE.
 */
package com.hypergestures.notif;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Bitmap measurement and reshaping helpers shared by the app-icon and small-icon fixes. */
final class Bitmaps {

    /** Alpha at or below this counts as transparent. */
    static final int ALPHA_FLOOR = 12;

    /** Alpha at or above this counts as solid coverage. */
    static final int ALPHA_SOLID = 128;

    private Bitmaps() {
    }

    /**
     * Renders any drawable into a software bitmap.
     *
     * <p>{@code BitmapDrawable.getBitmap()} is deliberately not returned directly: launcher3's
     * {@code FastBitmapDrawable} and MIUI's icon pipeline hand out <b>hardware</b> bitmaps, and
     * reading pixels from or drawing those into a software canvas throws
     * {@code Software rendering doesn't support hardware bitmaps}.
     */
    static Bitmap render(Drawable d, int fallbackSize) {
        if (d == null) {
            return null;
        }
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : fallbackSize;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : fallbackSize;
        // Bounds must be restored: these drawables are live and on screen. Leaving our own bounds
        // behind wrecks the geometry the owner set up — measured on device as MIUI's AOD icons
        // sitting off-centre inside their plates, because a LayerDrawable's setLayerInset is
        // expressed relative to bounds we had overwritten.
        Rect previous = d.copyBounds();
        try {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(out));
            return out;
        } catch (Throwable t) {
            Logger.v("render(" + d.getClass().getSimpleName() + ") failed: " + t);
            return null;
        } finally {
            d.setBounds(previous);
        }
    }

    /** Copies a possibly-hardware bitmap into a readable software one. */
    static Bitmap software(Bitmap src) {
        if (src == null) {
            return null;
        }
        if (src.getConfig() != null && !src.isRecycled() && !isHardware(src)) {
            return src;
        }
        try {
            return src.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            Logger.v("software copy failed: " + t);
            return null;
        }
    }

    private static boolean isHardware(Bitmap b) {
        return b.getConfig() == Bitmap.Config.HARDWARE;
    }

    /** Pixels of a drawable, going through {@link #render} unless it is already readable. */
    static Bitmap pixels(Drawable d, int fallbackSize) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = software(((BitmapDrawable) d).getBitmap());
            if (b != null) {
                return b;
            }
        }
        return render(d, fallbackSize);
    }

    /** Crops away a fully transparent border. Returns null when nothing is visible. */
    static Bitmap trim(Bitmap src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int left = w;
        int top = h;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (Color.alpha(px[row + x]) > ALPHA_FLOOR) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < left || bottom < top) {
            return null;
        }
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) {
            return src;
        }
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1);
    }

    /** Bounding box of the visible pixels, or null when nothing is visible. */
    static Rect opaqueBounds(Bitmap b) {
        if (b == null) {
            return null;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int left = w;
        int top = h;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (Color.alpha(px[row + x]) > ALPHA_FLOOR) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        return right < left ? null : new Rect(left, top, right + 1, bottom + 1);
    }

    /**
     * Distance from the bitmap's centre to its furthest solid pixel — how far the artwork actually
     * reaches, as opposed to how far its bounding box reaches. The difference matters for
     * non-rectangular artwork: a wide wordmark's bounding-box corners are empty, so a corner-based
     * measure would overstate its reach, while its true extreme sits at mid-height.
     */
    static float maxInkRadius(Bitmap b) {
        if (b == null) {
            return 0f;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return 0f;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        double worst = 0;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (Color.alpha(px[row + x]) < ALPHA_SOLID) {
                    continue;
                }
                double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                if (d > worst) {
                    worst = d;
                }
            }
        }
        return (float) worst;
    }

    /** Fraction of pixels that are solidly opaque, 0..1. */
    static float coverage(Bitmap b) {
        if (b == null) {
            return 0f;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return 0f;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int solid = 0;
        for (int p : px) {
            if (Color.alpha(p) >= ALPHA_SOLID) {
                solid++;
            }
        }
        return (float) solid / px.length;
    }

    /** True when the bitmap's visible pixels carry no meaningful chroma. */
    static boolean isMonochrome(Bitmap b) {
        if (b == null) {
            return true;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p : px) {
            if (Color.alpha(p) <= ALPHA_FLOOR) {
                continue;
            }
            int r = Color.red(p);
            int g = Color.green(p);
            int bl = Color.blue(p);
            if (Math.abs(r - g) >= 20 || Math.abs(r - bl) >= 20 || Math.abs(g - bl) >= 20) {
                return false;
            }
        }
        return true;
    }

    /**
     * Most common color along the bitmap's outer border, which for a plated icon is the plate.
     * Returns 0 when the border is not solid enough to call.
     */
    static int borderColor(Bitmap b) {
        if (b == null) {
            return 0;
        }
        int w = b.getWidth();
        int h = b.getHeight();
        if (w < 4 || h < 4) {
            return 0;
        }
        // 5-bit-per-channel histogram: tolerant of gradients and antialiasing, still specific
        // enough to separate a white plate from a colored logo.
        int buckets = 32 * 32 * 32;
        int[] count = new int[buckets];
        long[] sumR = new long[buckets];
        long[] sumG = new long[buckets];
        long[] sumB = new long[buckets];
        int considered = 0;

        // Sample a ring just inside the edge, skipping the outermost row: legacy icons often
        // carry a soft antialiased or shadowed outline there.
        int inset = Math.max(1, Math.min(w, h) / 24);
        for (int i = 0; i < 4; i++) {
            for (int t = inset; t < (i < 2 ? w : h) - inset; t++) {
                int x = i == 0 ? t : i == 1 ? t : i == 2 ? inset : w - 1 - inset;
                int y = i == 0 ? inset : i == 1 ? h - 1 - inset : t;
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < ALPHA_SOLID) {
                    continue;
                }
                int r = Color.red(p);
                int g = Color.green(p);
                int bl = Color.blue(p);
                int idx = ((r >> 3) << 10) | ((g >> 3) << 5) | (bl >> 3);
                count[idx]++;
                sumR[idx] += r;
                sumG[idx] += g;
                sumB[idx] += bl;
                considered++;
            }
        }
        if (considered == 0) {
            return 0;
        }
        int bestIdx = -1;
        int best = 0;
        for (int i = 0; i < buckets; i++) {
            if (count[i] > best) {
                best = count[i];
                bestIdx = i;
            }
        }
        // A border that is not dominated by one color is not a plate.
        if (bestIdx < 0 || best < considered * 0.6f) {
            return 0;
        }
        return Color.rgb(
                (int) (sumR[bestIdx] / count[bestIdx]),
                (int) (sumG[bestIdx] / count[bestIdx]),
                (int) (sumB[bestIdx] / count[bestIdx]));
    }

    /** Largest per-channel difference between two colors, ignoring alpha. */
    static int distance(int a, int b) {
        // HyperGestures: the channels by shifts (what Color.red/green/blue do), so the unit tests
        // run without Android's Color.
        int dr = Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
        int dg = Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff));
        int db = Math.abs((a & 0xff) - (b & 0xff));
        return Math.max(dr, Math.max(dg, db));
    }

    /** Writes a PNG for visual inspection when {@link Logger#dumpDir} is set. */
    static void dump(String name, Bitmap b) {
        String dir = Logger.dumpDir;
        if (dir == null || b == null) {
            return;
        }
        String path = dir + "/" + name.replace('/', '_') + ".png";
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable t) {
            Logger.v("dump " + path + " failed: " + t);
        }
    }
}
