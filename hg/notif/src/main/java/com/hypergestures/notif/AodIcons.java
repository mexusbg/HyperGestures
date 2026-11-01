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
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Fixes the always-on display's notification icons.
 *
 * <p>Finding where these come from took eliminating every AOSP path — {@code IconPack.mAodIcon} is
 * null, {@code ActiveNotificationIconModel.aodIcon} is null for every notification, and
 * {@code AlwaysOnDisplayNotificationIconViewStore.iconView} is never called. Walking SystemUI's
 * live view tree while dozing found the icon at
 * {@code AppCompatImageView#first < LinearLayout#icons < NotificationContainer#aod_icons_container
 * < LinkageStyleClockView}: all {@code com.miui.aod} classes, inflated <b>inside SystemUI</b>
 * through a separate ClassLoader. Hooking them in the {@code com.miui.aod} process installs cleanly
 * and never fires, because that process is not what draws the AOD.
 *
 * <p>MIUI composes each icon as a {@link LayerDrawable}: an oval plate coloured from the
 * notification's accent, with the app's small icon on top tinted to contrast. That works for a real
 * glyph (Gmail's "M", Messenger's bolt) and fails for an opaque image — {@code bg.epay.app} passes
 * its wordmark, which tints into a solid black square covering the plate.
 *
 * <p>So the composed icon is inspected rather than trusted: if its top layer is a solid block, the
 * whole thing is swapped for the app's real icon masked to a circle — the same icon the shade
 * shows, which suits this row since these icons are drawn in full colour rather than flat-tinted.
 */
final class AodIcons {

    /** Opaque coverage of the glyph layer above which it would swallow the plate. */
    private static final float BLOCK_COVERAGE = 0.80f;

    /**
     * The icon currently being bound, set by the bind hook just before the setter: its package,
     * and what the setter already did with it.
     */
    private static final ThreadLocal<Bind> BINDING = new ThreadLocal<>();

    /** HyperGestures: view classes already hooked, so a second loader never hooks one twice. */
    private static final java.util.Set<Class<?>> HOOKED = java.util.Collections.newSetFromMap(
            new java.util.WeakHashMap<>());

    /** HyperGestures: getNotificationDatas() per AOD view class; one lookup each. */
    private static final ConcurrentHashMap<Class<?>, Method> DATAS = new ConcurrentHashMap<>();

    /** Stands for "no such method" in {@link #DATAS}, which cannot hold null. */
    private static final Method NO_METHOD = noMethod();

    /** One icon bind. */
    private static final class Bind {
        final String pkg;
        /** The drawable the setter measured and passed on, or null if it did not run. */
        Drawable checked;

        Bind(String pkg) {
            this.pkg = pkg;
        }
    }

    private AodIcons() {
    }

    /**
     * HyperGestures: returns how many methods were hooked, 0 when this loader has none or they
     * are hooked already. Never throws: the caller's own hooks stand either way.
     */
    static int install(ClassLoader loader, boolean circle) {
        int hooked = 0;
        for (String name : new String[] {
            "com.miui.aod.AODView",
            "com.miui.aod.components.view.AodContainerView",
        }) {
            Class<?> c;
            try {
                c = loader.loadClass(name);
            } catch (Throwable t) {
                Logger.v("AOD view " + name + " unavailable: " + t);
                continue;
            }
            synchronized (HOOKED) {
                if (!HOOKED.add(c)) {
                    continue;
                }
            }
            try {
                hooked += hookBind(c, circle);
                hooked += hookSetter(c, circle);
            } catch (Throwable t) {
                Logger.e("AOD view " + name + " not hooked", t);
            }
        }
        return hooked;
    }

    /**
     * Records which notification is being bound. The setter itself only gets an {@link ImageView}
     * and a {@link Drawable}, so the package has to come from here: the index identifies the entry
     * in the view's own notification list.
     */
    private static int hookBind(Class<?> c, boolean circle) {
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            String n = m.getName();
            if ((!"bindNotificationIconView".equals(n) && !"bindView".equals(n))
                    || m.getParameterCount() != 2
                    || !ImageView.class.isAssignableFrom(m.getParameterTypes()[0])) {
                continue;
            }
            Xp.hook(m, chain -> {
                // HyperGestures: off binds MIUI's icon untouched.
                Object view = chain.getArg(0);
                if (Gate.passThrough(view instanceof ImageView
                        ? ((ImageView) view).getContext() : null)) {
                    return chain.proceed();
                }
                // The setter hook below fires inside proceed() and reads BINDING for the package.
                Bind bind = new Bind(packageAt(chain.getThisObject(), chain.getArg(1)));
                BINDING.set(bind);
                try {
                    Object result = chain.proceed();
                    try {
                        // Correct the view directly: whether MIUI routes through
                        // setNotificationIconDrawable or calls setImageDrawable itself, by this
                        // point the icon is on the view and can be measured for real.
                        fixBoundView(chain.getArg(0), bind, circle);
                    } catch (Throwable t) {
                        Logger.e("AOD bound icon fix failed", t);
                    }
                    return result;
                } finally {
                    BINDING.remove();
                }
            });
            hooked++;
            Logger.i("hooked " + c.getSimpleName() + "." + n + "(ImageView,int)");
        }
        return hooked;
    }

    private static void fixBoundView(Object viewObj, Bind bind, boolean circle) {
        if (!(viewObj instanceof ImageView)) {
            return;
        }
        ImageView view = (ImageView) viewObj;
        Drawable current = view.getDrawable();
        // HyperGestures: what the setter already measured (and replaced if needed) is not
        // rendered and measured a second time.
        if (current == null || current == bind.checked) {
            return;
        }
        String pkg = bind.pkg;
        float glyph = glyphCoverage(current);
        if (glyph < BLOCK_COVERAGE) {
            if (Logger.VERBOSE) {
                Logger.v("aodIcon(" + pkg + "): bound " + current.getClass().getSimpleName()
                        + " glyph coverage=" + Math.round(glyph * 100) + "%, leaving it alone");
            }
            return;
        }
        if (pkg == null) {
            Logger.v("aodIcon: bound icon is a solid block but the package is unknown");
            return;
        }
        Drawable masked = AppIconShaper.maskedFor(view.getContext(), pkg, 0, circle);
        if (masked == null) {
            return;
        }
        view.setImageDrawable(masked);
        // HyperGestures: verbose, as it runs per AOD icon bind.
        if (Logger.VERBOSE) {
            Logger.v("aodIcon(" + pkg + "): bound glyph was a " + Math.round(glyph * 100)
                    + "% solid block -> replaced with the app icon");
        }
    }

    private static int hookSetter(Class<?> c, boolean circle) {
        int hooked = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!"setNotificationIconDrawable".equals(m.getName())
                    || m.getParameterCount() != 2) {
                continue;
            }
            Xp.hook(m, chain -> {
                // HyperGestures: off calls the original with the original arguments.
                if (Gate.passThrough(contextOf(chain.getArg(0)))) {
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                try {
                    fix(args, circle);
                } catch (Throwable t) {
                    Logger.e("AOD icon fix failed", t);
                }
                return chain.proceed(args);
            });
            hooked++;
            Logger.i("hooked " + c.getSimpleName() + ".setNotificationIconDrawable");
        }
        return hooked;
    }

    /** Swaps the drawable in {@code args} in place before the call proceeds. */
    private static void fix(Object[] args, boolean circle) {
        if (args.length < 2 || !(args[1] instanceof Drawable)) {
            return;
        }
        Drawable composed = (Drawable) args[1];
        Bind bind = BINDING.get();
        String pkg = bind == null ? null : bind.pkg;
        if (bind != null) {
            bind.checked = composed;
        }
        float glyph = glyphCoverage(composed);
        if (glyph < BLOCK_COVERAGE) {
            if (Logger.VERBOSE) {
                Logger.v("aodIcon(" + pkg + "): " + composed.getClass().getSimpleName()
                        + " glyph coverage=" + Math.round(glyph * 100) + "%, leaving it alone");
            }
            return;
        }
        if (pkg == null) {
            Logger.v("aodIcon: a solid block, but the package is unknown; leaving it alone");
            return;
        }
        Context ctx = contextOf(args[0]);
        Drawable masked = AppIconShaper.maskedFor(ctx, pkg, 0, circle);
        if (masked == null) {
            return;
        }
        args[1] = masked;
        bind.checked = masked;
        // HyperGestures: verbose, as it runs per AOD icon bind.
        if (Logger.VERBOSE) {
            Logger.v("aodIcon(" + pkg + "): glyph was a " + Math.round(glyph * 100)
                    + "% solid block that would cover MIUI's plate -> using the app icon instead");
        }
    }

    /**
     * Opaque coverage of the glyph MIUI put on top of its plate. For a {@link LayerDrawable} that
     * is the upper layer; anything else is measured whole.
     */
    private static float glyphCoverage(Drawable composed) {
        Drawable glyph = composed;
        if (composed instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) composed;
            int n = layers.getNumberOfLayers();
            if (n >= 2) {
                Drawable top = layers.getDrawable(n - 1);
                if (top != null) {
                    glyph = top;
                }
            }
        }
        Bitmap b = Bitmaps.pixels(glyph, 96);
        return b == null ? 0f : Bitmaps.coverage(b);
    }

    private static Context contextOf(Object view) {
        return view instanceof ImageView ? ((ImageView) view).getContext() : null;
    }

    /** Reads {@code mPackageName} of the notification at {@code index} in the view's own list. */
    private static String packageAt(Object aodView, Object index) {
        if (aodView == null || !(index instanceof Integer)) {
            return null;
        }
        try {
            Method get = DATAS.computeIfAbsent(aodView.getClass(), AodIcons::datasMethod);
            if (get == NO_METHOD) {
                return null;
            }
            Object list = get.invoke(aodView);
            if (!(list instanceof List)) {
                return null;
            }
            List<?> datas = (List<?>) list;
            int i = (Integer) index;
            if (i < 0 || i >= datas.size()) {
                return null;
            }
            return Hidden.readField(datas.get(i), "mPackageName", String.class);
        } catch (Throwable t) {
            Logger.v("could not read the AOD notification package: " + t);
        }
        return null;
    }

    private static Method datasMethod(Class<?> c) {
        try {
            return c.getMethod("getNotificationDatas");
        } catch (Throwable t) {
            Logger.v("AOD view " + c.getName() + " has no getNotificationDatas: " + t);
            return NO_METHOD;
        }
    }

    private static Method noMethod() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
