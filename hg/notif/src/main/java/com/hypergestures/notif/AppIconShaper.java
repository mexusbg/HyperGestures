/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.LruCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Produces the notification header's app icon: the app's own icon, clipped to the mask we choose.
 *
 * <p>Why this is needed. The framework's {@code NotificationRowIconView.loadAppIcon()} asks
 * SystemUI's {@code NotificationIconProvider.getLauncherIcon()} for an icon whenever a row uses
 * the launcher-icon style, and {@code adjustViewForIconStyle()} then strips that view's padding
 * and background. So whatever the provider returns is painted edge to edge with nothing behind
 * it. SystemUI returns a launcher3 {@code FastBitmapDrawable} built from a pre-rasterized bitmap
 * that already carries an opaque plate — and that plate is the hard rectangle behind icons such
 * as Reddit's, Gmail's and Google's.
 *
 * <p>Handing back a bare {@link AdaptiveIconDrawable} does not help: this view draws it
 * <em>unmasked</em>, so the artwork still reaches the corners as a square (measured on device —
 * the corner pixel held Takeaway's orange and Gmail's white). The clip therefore has to happen
 * here, where we control it.
 *
 * <p>Two source shapes exist in the wild and both are handled:
 *
 * <ol>
 *   <li><b>Adaptive icons.</b> Bounded, then drawn through our own clip.
 *   <li><b>Legacy icons</b> — a plain PNG in {@code mipmap/}, still common (banking apps in
 *       particular). The platform gives these no mask at all, so MIUI's plated bitmap was
 *       surviving and the row stayed square. The artwork is trimmed of its transparent margin and
 *       then either scaled to fill the mask (when it carries its own opaque body, so the mask has
 *       something to cut) or centered at glyph size on transparency (when it is a bare logo, which
 *       blown up to full bleed would look cropped).
 * </ol>
 */
final class AppIconShaper {

    /** Edge length of the icon we produce, matching MIUI's own notification app icons. */
    private static final int MASKED_SIZE = 201;

    /** Corner radius of the fallback mask, as a fraction of the icon edge. */
    private static final float FALLBACK_RADIUS = 0.22f;

    /**
     * Opaque fraction of a trimmed legacy icon above which it counts as having its own body, so it
     * can be scaled to fill the mask instead of being centered as a glyph.
     */
    private static final float BODY_COVERAGE = 0.70f;

    /** Edge of a bare legacy logo as a fraction of the icon edge, when it is centered instead. */
    private static final float GLYPH_SCALE = 0.78f;

    /**
     * How far artwork may reach towards the mask edge, as a fraction of the mask radius, before it
     * counts as crowding it. Icons designed for the shape stay well inside this.
     */
    private static final float MAX_REACH = 0.86f;

    /**
     * How far from square artwork must be before crowding the edge is treated as a defect. A round
     * logo filling the circle follows the mask and looks correct; a thin wide one reads as a bar.
     */
    private static final float MIN_REFIT_ASPECT = 1.6f;

    /**
     * Opaque coverage above which an adaptive foreground counts as full-bleed artwork, which is
     * *meant* to run past the viewport, so it is cropped rather than shrunk.
     */
    private static final float FULL_BLEED_COVERAGE = 0.85f;

    /** Edge of a refitted adaptive foreground, as a fraction of the icon edge. */
    private static final float FIT_SCALE = 0.72f;

    /**
     * HyperGestures: what the masked-icon cache may hold, in bytes. A masked icon is 201x201
     * ARGB, ~158 KB, so this keeps the icons of the ~25 most recent apps.
     */
    private static final int MASKED_BUDGET = 4 * 1024 * 1024;

    /** HyperGestures: the cost of remembering "nothing better" for an app. */
    private static final int NONE_SIZE = 64;

    /** HyperGestures: cached for an app with nothing better than what the system produced. */
    private static final Object NONE = new Object();

    /**
     * HyperGestures: masked bitmaps (or {@link #NONE}) by {@code user:package:mask}. A holder
     * class, so the cache (an Android class) is only built on first use, never in unit tests.
     */
    private static final class Masked {
        static final LruCache<String, Object> CACHE = new LruCache<String, Object>(MASKED_BUDGET) {
            @Override
            protected int sizeOf(String key, Object value) {
                return value instanceof Bitmap
                        ? ((Bitmap) value).getAllocationByteCount() : NONE_SIZE;
            }
        };
    }

    private AppIconShaper() {
    }

    /**
     * @param circle true for a circular mask (matching the conversation avatars and system rows),
     *     false for the platform icon shape (matching launcher icons)
     * @return a masked icon, or null when we have nothing better than what the system produced, in
     *     which case the caller must leave the original result untouched. HyperGestures: a new
     *     drawable per call over the cached bitmap, so one view's alpha, tint, bounds or callback
     *     never reaches another (shade rows and AOD views share cache entries).
     */
    static Drawable maskedFor(Context ctx, String pkg, int userId, boolean circle) {
        if (ctx == null || pkg == null) {
            return null;
        }
        PackageWatcher.ensure(ctx);
        String key = userId + ":" + pkg + (circle ? ":circle" : ":shape");
        Object cached = Masked.CACHE.get(key);
        if (cached == null) {
            cached = build(ctx, pkg, userId, circle);
            Masked.CACHE.put(key, cached);
        }
        return cached instanceof Bitmap
                ? new BitmapDrawable(Resources.getSystem(), (Bitmap) cached) : null;
    }

    /**
     * Builds the masked icon, or {@link #NONE}. HyperGestures: a failed lookup is remembered too,
     * so a bind does not repeat the binder calls and the decode; {@link PackageWatcher} drops it
     * when the app is installed, updated or changed.
     */
    private static Object build(Context ctx, String pkg, int userId, boolean circle) {
        Drawable raw = appIcon(ctx, pkg, userId, circle);
        if (raw == null) {
            return NONE;
        }

        Bitmap out = Bitmap.createBitmap(MASKED_SIZE, MASKED_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        String how;

        canvas.save();
        if (raw instanceof AdaptiveIconDrawable) {
            how = drawAdaptive(canvas, (AdaptiveIconDrawable) raw, circle, pkg);
        } else {
            how = drawLegacy(canvas, raw, circle, pkg);
            if (how == null) {
                canvas.restore();
                return NONE;
            }
        }
        canvas.restore();

        out = badged(ctx, out, userId, pkg);
        Bitmaps.dump(pkg + "-app-out", out);
        Logger.i("masked(" + pkg + "): " + how + " clipped to "
                + (circle ? "circle" : "platform icon shape") + " at " + MASKED_SIZE + "px");
        return out;
    }

    /**
     * HyperGestures: adds the profile badge (the work profile's briefcase, for instance) that the
     * system's own icon carries for an app of another user, and that replacing the icon would
     * otherwise drop. It is drawn into the cached bitmap, so this runs once per app and user; the
     * main user (0) is never badged and costs no lookup.
     */
    private static Bitmap badged(Context ctx, Bitmap masked, int userId, String pkg) {
        if (userId <= 0) {
            return masked;
        }
        try {
            Drawable plain = new BitmapDrawable(Resources.getSystem(), masked);
            Drawable badged = ctx.getPackageManager()
                    .getUserBadgedIcon(plain, UserHandle.of(userId));
            if (badged == null || badged == plain) {
                return masked;
            }
            Bitmap out = Bitmaps.pixels(badged, MASKED_SIZE);
            return out != null ? out : masked;
        } catch (Throwable t) {
            Logger.v("badge(" + pkg + ", user " + userId + ") failed: " + t);
            return masked;
        }
    }

    /**
     * Draws an adaptive icon through the mask, refitting artwork that does not fit the mask.
     *
     * <p>The launcher shape assumes an icon keeps its content inside the safe zone. Some do not:
     * {@code bg.epay.app}'s foreground is the wordmark "ePay.bg" spanning the full width, whose ink
     * reaches 95.6px from the centre of a 100px-radius circle — geometrically inside, visually
     * jammed against the edge, and at notification-row size indistinguishable from a black bar in a
     * light circle. MIUI's own rasterizer avoids this by scaling the whole icon down inside its
     * plate, and this does the same.
     *
     * <p>Two measures that sound right are both wrong here, and were tried on device first:
     *
     * <ul>
     *   <li>Ink lost to the platform's 1.5× viewport crop — measured {@code 0%}: this wordmark is
     *       not cropped at all, it merely fills the width.
     *   <li>Ink falling outside the mask — also {@code 0.0%}: the ink at x 8…192 hugs the
     *       92px-radius safe circle (which spans 8.5…192.5) without crossing it.
     * </ul>
     *
     * <p>What actually distinguishes the defect is <b>reach plus elongation</b>. Artwork that
     * reaches the mask edge is only a problem when it is elongated: a round logo filling the circle
     * looks right because it follows the mask, whereas a thin wide one reads as a bar. So a refit
     * needs the artwork to both reach the edge and be far from square — epay's ink reaches 92.5 of
     * 100 with an aspect of 3.5, while a round logo at the same reach has an aspect near 1.
     *
     * <p>Full-bleed artwork is exempt: it covers everything by design.
     */
    private static String drawAdaptive(Canvas canvas, AdaptiveIconDrawable aid, boolean circle,
                                       String pkg) {
        // Bounds first: getIconMask() reports the mask in the drawable's current bounds space.
        aid.setBounds(0, 0, MASKED_SIZE, MASKED_SIZE);
        Path mask = maskPath(MASKED_SIZE, circle, aid);
        canvas.clipPath(mask);

        Drawable fg = aid.getForeground();
        if (fg == null) {
            aid.draw(canvas);
            return "adaptive(no fg)";
        }

        // The foreground's bounds are the 1.5x layer rect in the icon's own coordinate space, so
        // drawing it straight into a mask-sized canvas yields exactly the visible viewport.
        Bitmap shown = Bitmap.createBitmap(
                MASKED_SIZE, MASKED_SIZE, Bitmap.Config.ARGB_8888);
        fg.draw(new Canvas(shown));

        float coverage = Bitmaps.coverage(shown);
        Rect ink = Bitmaps.opaqueBounds(shown);
        float reach = Bitmaps.maxInkRadius(shown) / (MASKED_SIZE / 2f);
        float aspect = ink == null ? 1f : aspect(ink.width(), ink.height());
        boolean crowded = crowded(reach, aspect, coverage);
        Logger.v("adaptive(" + pkg + "): ink=" + ink
                + " coverage=" + Math.round(coverage * 100) + "%"
                + " reach=" + Math.round(reach * 100) + "% of the radius"
                + " aspect=" + Math.round(aspect * 10) / 10f
                + (crowded ? " -> refit" : " -> as-is"));

        if (!crowded) {
            aid.draw(canvas);
            return "adaptive fits (reach=" + Math.round(reach * 100)
                    + "% aspect=" + Math.round(aspect * 10) / 10f + ")";
        }

        // Refit. The background layer keeps its own geometry — it is meant to bleed, and is flat.
        Drawable bg = aid.getBackground();
        if (bg != null) {
            bg.draw(canvas);
        }
        // Trim the layer-space render, not the viewport one, so nothing the viewport cut is lost.
        Bitmap trimmed = Bitmaps.trim(layerArt(fg));
        if (trimmed == null) {
            aid.draw(canvas);
            return "adaptive(empty fg)";
        }
        float edge = MASKED_SIZE * FIT_SCALE;
        float scale = Math.min(edge / trimmed.getWidth(), edge / trimmed.getHeight());
        float w = trimmed.getWidth() * scale;
        float h = trimmed.getHeight() * scale;
        RectF dst = new RectF((MASKED_SIZE - w) / 2f, (MASKED_SIZE - h) / 2f,
                (MASKED_SIZE + w) / 2f, (MASKED_SIZE + h) / 2f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(trimmed, null, dst, paint);

        return "adaptive/fit (reach=" + Math.round(reach * 100)
                + "% aspect=" + Math.round(aspect * 10) / 10f + ")";
    }

    // HyperGestures: the gates as plain functions, so the unit tests check them without bitmaps.

    /** Longer over shorter side of a box; a degenerate side counts as 1. */
    static float aspect(int width, int height) {
        return (float) Math.max(width, height) / Math.max(1, Math.min(width, height));
    }

    /**
     * Whether adaptive artwork crowds the mask and is refitted: it reaches the edge, is far from
     * square, and is not full-bleed (see {@link #drawAdaptive}).
     */
    static boolean crowded(float reach, float aspect, float coverage) {
        return reach > MAX_REACH && aspect > MIN_REFIT_ASPECT && coverage < FULL_BLEED_COVERAGE;
    }

    /** Whether a trimmed legacy icon has its own body (fills the mask) or is a bare logo. */
    static boolean hasBody(float coverage) {
        return coverage >= BODY_COVERAGE;
    }

    /** Renders a layer across its full 1.5x extent, so artwork the viewport would cut survives. */
    private static Bitmap layerArt(Drawable layer) {
        Rect b = layer.copyBounds();
        if (b.width() <= 0 || b.height() <= 0) {
            return null;
        }
        Bitmap out = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.translate(-b.left, -b.top);
        layer.draw(c);
        return out;
    }

    /**
     * Draws a legacy (non-adaptive) icon through the mask.
     *
     * @return a short description of the path taken, or null if the artwork was unusable
     */
    private static String drawLegacy(Canvas canvas, Drawable raw, boolean circle, String pkg) {
        // HyperGestures: at most twice the output edge, enough detail to scale a trimmed logo up
        // from without rendering a huge legacy PNG or vector at full size.
        Bitmap art = Bitmaps.trim(Bitmaps.pixels(raw, MASKED_SIZE * 2));
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) {
            Logger.v("legacy(" + pkg + "): nothing visible in the icon, leaving it alone");
            return null;
        }
        float coverage = Bitmaps.coverage(art);
        boolean hasBody = hasBody(coverage);

        float edge = hasBody ? MASKED_SIZE : MASKED_SIZE * GLYPH_SCALE;
        // Fill for a body — the overflow is what the mask trims; fit for a bare logo.
        float scale = hasBody
                ? Math.max(edge / art.getWidth(), edge / art.getHeight())
                : Math.min(edge / art.getWidth(), edge / art.getHeight());
        float w = art.getWidth() * scale;
        float h = art.getHeight() * scale;
        RectF dst = new RectF((MASKED_SIZE - w) / 2f, (MASKED_SIZE - h) / 2f,
                (MASKED_SIZE + w) / 2f, (MASKED_SIZE + h) / 2f);

        canvas.clipPath(maskPath(MASKED_SIZE, circle, null));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(art, null, dst, paint);

        return "legacy/" + (hasBody ? "body" : "glyph")
                + " coverage=" + Math.round(coverage * 100) + "%";
    }

    /**
     * Drops cached icons for a package in every user, so an app update is picked up.
     * HyperGestures: called by {@link PackageWatcher}.
     */
    static void forget(String pkg) {
        String part = ":" + pkg + ":";
        for (String k : Masked.CACHE.snapshot().keySet()) {
            if (k.contains(part)) {
                Masked.CACHE.remove(k);
            }
        }
    }

    /**
     * Loads the app's icon straight from the app's own resources, which bypasses MIUI's icon
     * theming and rasterization and gives us the real artwork.
     *
     * <p>When we are masking to a circle, {@code ApplicationInfo.roundIconRes} is preferred if the
     * app declares one ({@code android:roundIcon}): that is the variant the app drew for a round
     * mask, so it survives the clip better than the square one.
     */
    private static Drawable appIcon(Context ctx, String pkg, int userId, boolean circle) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = appInfo(pm, pkg, userId);
            if (ai == null) {
                return null;
            }
            Resources res = pm.getResourcesForApplication(ai);

            if (circle) {
                int round = roundIconRes(ai);
                if (round != 0 && round != ai.icon) {
                    Drawable d = load(res, round, pkg, "roundIcon");
                    if (d != null) {
                        return d;
                    }
                }
            }
            return ai.icon == 0 ? null : load(res, ai.icon, pkg, "icon");
        } catch (Throwable t) {
            Logger.v("appIcon(" + pkg + ") failed: " + t);
            return null;
        }
    }

    /**
     * Resolves an app across users where possible. {@code getApplicationInfoAsUser} is hidden API
     * so it goes through reflection, and the plain lookup is the fallback — the AOD process may not
     * be able to reach other users at all, and its notifications are the current user's anyway.
     */
    private static ApplicationInfo appInfo(PackageManager pm, String pkg, int userId) {
        try {
            Method asUser = PackageManager.class.getMethod(
                    "getApplicationInfoAsUser", String.class, int.class, int.class);
            ApplicationInfo ai = (ApplicationInfo) asUser.invoke(
                    pm, pkg, 0, userId < 0 ? 0 : userId);
            if (ai != null) {
                return ai;
            }
        } catch (Throwable t) {
            Logger.v("appInfo(" + pkg + "): getApplicationInfoAsUser unavailable: " + t);
        }
        try {
            return pm.getApplicationInfo(pkg, 0);
        } catch (Throwable t) {
            Logger.v("appInfo(" + pkg + ") failed: " + t);
            return null;
        }
    }

    private static Drawable load(Resources res, int resId, String pkg, String what) {
        try {
            Drawable d = res.getDrawable(resId, null);
            if (d != null) {
                Logger.v("appIcon(" + pkg + "): " + what + " -> " + d.getClass().getSimpleName());
            }
            return d;
        } catch (Throwable t) {
            Logger.v("appIcon(" + pkg + "): " + what + " did not load: " + t);
            return null;
        }
    }

    /** {@code ApplicationInfo.roundIconRes} is hidden API, so it is read reflectively. */
    private static int roundIconRes(ApplicationInfo ai) {
        try {
            Field f = ApplicationInfo.class.getField("roundIconRes");
            return f.getInt(ai);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Prefers the platform's own icon mask so results match launcher icons; falls back to a
     * rounded square if that call is unavailable or there is no adaptive icon to ask.
     */
    private static Path maskPath(int size, boolean circle, Drawable adaptive) {
        Path path = new Path();
        if (circle) {
            path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            return path;
        }
        if (adaptive != null) {
            try {
                // getIconMask() is an instance method: invoking it with a null receiver throws
                // "null receiver" and silently drops us to the fallback shape.
                Path mask = (Path) AdaptiveIconDrawable.class
                        .getMethod("getIconMask").invoke(adaptive);
                if (mask != null && !mask.isEmpty()) {
                    path.set(mask);
                    return path;
                }
            } catch (Throwable t) {
                Logger.v("getIconMask unavailable (" + t + "), using a rounded square");
            }
        }
        path.reset();
        float r = size * FALLBACK_RADIUS;
        path.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
        return path;
    }
}
