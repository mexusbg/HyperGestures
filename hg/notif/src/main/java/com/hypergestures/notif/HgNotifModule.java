/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Restores colored notification icons on HyperOS 3.3 / Android 17.
 *
 * <p>The icon is substituted in {@code IconManager.createIcons} / {@code updateIcons}, before
 * the original body runs. Two things are essential on Android 17:
 *
 * <ol>
 *   <li>{@code IconManager.getIconDescriptor} returns a cached {@code IconPack
 *       .mSmallIconDescriptor} before it ever looks at the notification. Android 16 introduced
 *       a flow-based icon pipeline that can populate that cache first, so mutating the
 *       notification alone is not enough — the cached descriptors must be cleared too.
 *   <li>HyperOS routes the icon through {@code NotifImageUtil.getSmallIcon}, which substitutes
 *       the launcher icon unless {@code miui.isGrayscaleIcon} is set. We set it so our icon is
 *       the one that survives.
 * </ol>
 *
 * <p>Credits: the idea comes from howard20181's Notification Icon Fix
 * ({@code io.github.howard20181.notificationiconfix}), which solved this on AOSP, MIUI and
 * HyperOS for years. HyperNotificationFix is an independent reimplementation for Android 17, and
 * this is its port into HyperGestures.
 *
 * <p>HyperGestures: this is HyperGestures' LSPosed entry point (META-INF/xposed/java_init.list),
 * scoped to SystemUI and the AOD. It only runs when LSPosed loads our APK into those processes,
 * never in our own app. Every hook is installed as HyperNotificationFix installs it, but passes
 * through untouched (the original result) unless Settings.Global
 * {@code hypergestures_notification_icons} is "1" ({@link NotifSetting}, cached per process and
 * followed by a ContentObserver), and while HyperNotificationFix's own hooks run ahead of ours in
 * the same process ({@link HnfCoexistence}). See {@link Gate}.
 */
public class HgNotifModule extends XposedModule {

    private static final String SYSTEMUI = "com.android.systemui";

    /** The always-on display is a separate app in its own process, with its own icon pipeline. */
    private static final String AOD = "com.miui.aod";

    /**
     * Reshape notification app icons that HyperOS left as unmasked squares. This is the fix for
     * the hard rectangles in the shade.
     */
    private static final boolean FIX_APP_ICON = true;

    /**
     * Replace small icons that would tint to a solid block with a silhouette of their artwork.
     *
     * <p>Only icons that are <em>already broken</em> are touched: apps that pass an opaque
     * full-color image (often their launcher PNG) where the platform expects a monochrome glyph.
     * The status bar keeps only alpha, so such an icon arrives as a plain white square. See
     * {@link SmallIconFixer}.
     *
     * <p>Generating a <em>colored</em> icon here was tried first and does not work on HyperOS 3.3
     * — the tint flattens it back to a white blob.
     */
    private static final boolean FIX_SMALL_ICON = true;

    /**
     * Reshape the always-on display's notification icons the same way as the shade's.
     *
     * <p>The AOD is {@code com.miui.aod}, a separate app in a separate process that reads
     * notifications through its own listener service — so nothing the SystemUI hooks do reaches
     * it. See {@link #installAodHook}.
     */
    private static final boolean FIX_AOD_ICON = true;

    /**
     * Shape used for the notification app icon. Circle, chosen to match the conversation avatars
     * and system rows that HyperOS already renders circular. Set false for the platform icon
     * shape (the launcher's squircle) instead.
     */
    private static final boolean MASK_AS_CIRCLE = true;

    /**
     * HyperGestures: writes every generated bitmap as a PNG into SystemUI's cache (see
     * {@link Logger#dumpDir}), on SystemUI's main thread. A tuning aid only, so off; flip it for a
     * debugging build.
     */
    private static final boolean DUMP_BITMAPS = false;

    private Field fSbn;
    private Field fIcons;
    private Field fSmallIconDescriptor;
    private Field fPeopleAvatarDescriptor;
    private Class<?> entryClass;
    private Method mGetSystemUiContext;

    private volatile Context sysCtx;
    private volatile PackageManager pm;

    /**
     * Loaders already hooked. There can be more than one: the loader from
     * {@code createPackageContextAsUser} hosts the AOD classes but is <em>not</em> the one MIUI
     * inflates its views with, and hooks only apply to the Class objects of the loader they were
     * installed on. So every distinct loader is hooked rather than stopping at the first success.
     */
    private final java.util.Set<ClassLoader> hookedAodLoaders =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** For handing the live AOD ClassLoader back from the view-tree scan. */
    private static volatile HgNotifModule instance;

    /** Generated icons are stable per app; key is user:package. */
    private final ConcurrentHashMap<String, Icon> cache = new ConcurrentHashMap<>();

    /** Packages we decided not to touch, so we do not redo the work on every update. */
    private final ConcurrentHashMap<String, Boolean> skip = new ConcurrentHashMap<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.module = this;
        Logger.i("module loaded in " + param.getProcessName() + ", framework "
                + getFrameworkName() + " " + getFrameworkVersion()
                + " (api " + getApiVersion() + ")");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) {
            return;
        }
        String pkg = param.getPackageName();
        try {
            if (SYSTEMUI.equals(pkg)) {
                instance = this;
                install(param.getDefaultClassLoader());
                // HyperGestures: tell our settings page the hooks run this boot (fails soft).
                NotifSetting.markHooked(NotifKeys.SCOPE_SYSTEMUI);
            } else if (AOD.equals(pkg) && FIX_AOD_ICON) {
                installAodHook(param.getDefaultClassLoader());
            }
        } catch (Throwable t) {
            Logger.e("install failed in " + pkg, t);
        }
    }

    /**
     * Installs the AOD hooks against the ClassLoader that actually owns the AOD classes.
     *
     * <p>The always-on display is drawn <b>inside SystemUI</b>, not in the {@code com.miui.aod}
     * process: MIUI inflates the AOD app's own views there through a separate ClassLoader. Measured
     * by walking SystemUI's live view tree while dozing, which found the icon at
     * {@code AppCompatImageView#first < LinearLayout#icons <
     * NotificationContainer#aod_icons_container
     * < LinkageStyleClockView} — all {@code com.miui.aod} classes, in SystemUI's window.
     *
     * <p>So hooking those classes through SystemUI's own loader fails, and hooking them in the AOD
     * process succeeds but never fires. The loader has to be caught as it loads them.
     */
    private void watchForAodClasses() {
        // HyperGestures: finding the loaders is not gated on the setting. It changes nothing by
        // itself, and the hooks it installs pass through while the fix is off, so turning the fix
        // on later still finds the AOD hooked.
        // Note ClassLoader.loadClass is useless here: references resolved from bytecode are linked
        // natively and never reach it. These two are places MIUI's own code must pass through.
        hookCreatePackageContext();
        hookAddView();
        Logger.i("watching for the AOD classes to be loaded into SystemUI");
    }

    /** The AOD apk has to be opened with code before its views can be inflated. */
    private void hookCreatePackageContext() {
        try {
            Class<?> impl = Class.forName("android.app.ContextImpl");
            for (Method m : impl.getDeclaredMethods()) {
                if (!m.getName().startsWith("createPackageContext")) {
                    continue;
                }
                Xp.hook(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && AOD.equals(args.get(0))
                                && result instanceof Context) {
                            tryInstallAodHook(((Context) result).getClassLoader(),
                                    chain.getExecutable().getName());
                        }
                    } catch (Throwable t) {
                        Logger.v("createPackageContext probe failed: " + t);
                    }
                    return result;
                });
            }
        } catch (Throwable t) {
            Logger.v("createPackageContext unavailable: " + t);
        }
    }

    /** Fallback: the AOD's clock and icon views get added to SystemUI's own hierarchy. */
    private void hookAddView() {
        try {
            Method addView = ViewGroup.class.getDeclaredMethod(
                    "addView", View.class, int.class, ViewGroup.LayoutParams.class);
            Xp.hook(addView, chain -> {
                try {
                    Object child = chain.getArg(0);
                    if (child instanceof View) {
                        Class<?> c = child.getClass();
                        if (c.getName().startsWith("com.miui.aod.")) {
                            tryInstallAodHook(c.getClassLoader(), c.getName());
                        }
                    }
                } catch (Throwable t) {
                    Logger.v("addView probe failed: " + t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Logger.v("ViewGroup.addView hook unavailable: " + t);
        }
    }

    /** Lets the view-tree scan hand over the loader that owns the live AOD views. */
    static void offerAodLoader(ClassLoader loader, String via) {
        HgNotifModule self = instance;
        if (self != null) {
            self.tryInstallAodHook(loader, via);
        }
    }

    private void tryInstallAodHook(ClassLoader loader, String via) {
        if (loader == null) {
            return;
        }
        synchronized (this) {
            if (!hookedAodLoaders.add(loader)) {
                return;
            }
            try {
                installAodHook(loader);
                // HyperGestures: the page lists the AOD among the hooked scopes (fails soft).
                NotifSetting.markHooked(NotifKeys.SCOPE_AOD);
                Logger.i("AOD hooks installed, loader found via " + via
                        + " (loader " + Integer.toHexString(System.identityHashCode(loader)) + ")");
            } catch (Throwable t) {
                // A loader that sees some com.miui.aod classes but not the icon methods. Nothing to
                // undo: it stays in the set so it is not retried.
                Logger.v("loader via " + via + " cannot host the AOD hooks: " + t);
            }
        }
    }

    /**
     * Reshapes the always-on display's notification icons.
     *
     * <p>{@code com.miui.aod.notification.NotificationUtils.getAppIcon()} picks between three
     * sources, and the one it lands on for an ordinary app is the problem:
     *
     * <pre>
     * if (!data.mIsDefaultTheme)   -&gt; getAppIconInner(...)
     * else if (data.mUseSmallIcon) -&gt; LayerDrawable: oval GradientDrawable + tinted glyph
     * else                         -&gt; getCustomAppIcon(...)
     * </pre>
     *
     * {@code getCustomAppIcon} only works for packages on the AOD's own
     * {@code mCustomAppIconPackages} whitelist, and falls through to {@code getAppIconInner} →
     * {@code getAppIconByPackageName} → {@code PackageManager.getApplicationIcon()}. That last one
     * returns the <b>MIUI-themed</b> icon: a plated square, drawn unmasked. Hence the white square.
     *
     * <p>The {@code mUseSmallIcon} branch composes its own oval and is left alone — it is already
     * round, and its glyph is tinted, so replacing it would only undo AOD's own styling.
     */
    private void installAodHook(ClassLoader cl) throws Throwable {
        Logger.i("loading into the AOD, sdk=" + android.os.Build.VERSION.SDK_INT);
        Class<?> utils = cl.loadClass("com.miui.aod.notification.NotificationUtils");
        Class<?> data = cl.loadClass(
                "com.miui.aod.notification.NotificationController$NotificationData");
        Method getAppIcon = utils.getDeclaredMethod(
                "getAppIcon", Context.class, data, Notification.class);
        Xp.hook(getAppIcon, chain -> {
            Object result = chain.proceed();
            try {
                // HyperGestures: off returns MIUI's own icon.
                Object ctx = chain.getArg(0);
                if (Gate.passThrough(ctx instanceof Context ? (Context) ctx : null)) {
                    return result;
                }
                Drawable replaced = replaceAodIcon(chain.getArgs(), result);
                if (replaced != null) {
                    return replaced;
                }
            } catch (Throwable t) {
                Logger.e("AOD icon replacement failed", t);
            }
            return result;
        });
        Logger.i("hooked NotificationUtils.getAppIcon(Context,NotificationData,Notification)");

        // The branch that actually runs on this configuration composes MIUI's own oval plate with
        // the glyph tinted SRC_ATOP. Fixing the glyph on the way in keeps MIUI's plate and tint,
        // and is the only place the AOD's copy of the small icon can be reached: this process has
        // its own NotificationListenerService, so it never sees SystemUI's replacement.
        int composed = 0;
        for (Method m : utils.getDeclaredMethods()) {
            if (!"createIconWithBackground".equals(m.getName())) {
                continue;
            }
            Xp.hook(m, chain -> {
                // HyperGestures: off calls the original with the original arguments.
                if (Gate.passThrough(null)) {
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                try {
                    fixAodGlyph(args);
                } catch (Throwable t) {
                    Logger.e("AOD glyph fix failed", t);
                }
                return chain.proceed(args);
            });
            composed++;
            Logger.i("hooked NotificationUtils." + m.getName() + describeParams(m));
        }
        if (composed == 0) {
            Logger.i("WARNING: NotificationUtils.createIconWithBackground not found;"
                    + " AOD small icons stay as the app supplied them");
        }

        // Belt and braces: the composed icon is bound to its view through IAodView, the one point
        // every AOD icon passes through whichever of MIUI's paths built it.
        AodIcons.install(cl, MASK_AS_CIRCLE);
    }

    /** Corrects the glyph drawable in place inside {@code args} before the call proceeds. */
    private void fixAodGlyph(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof Drawable)) {
                continue;
            }
            Drawable in = (Drawable) args[i];
            Drawable fixed = SmallIconFixer.fixDrawable(in, "aod");
            // HyperGestures: verbose, as it runs per AOD icon.
            Logger.v("aodGlyph: in=" + in.getClass().getSimpleName()
                    + " " + in.getIntrinsicWidth() + "x" + in.getIntrinsicHeight()
                    + (fixed == null ? " -> left as is" : " -> replaced with a legible glyph"));
            if (fixed != null) {
                args[i] = fixed;
            }
            return;
        }
    }

    /** Returns the replacement icon, or null to keep {@code old} as the result. */
    private Drawable replaceAodIcon(List<Object> args, Object old) {
        if (args == null || args.size() < 2 || !(args.get(0) instanceof Context)) {
            return null;
        }
        if (old instanceof LayerDrawable) {
            // AOD's own oval-plus-glyph composition: already round, and its glyph was corrected on
            // the way in by the createIconWithBackground hook, so leave the composition alone.
            Logger.v("aodIcon(" + readField(args.get(1), "mPackageName", String.class)
                    + "): MIUI composed its own oval; glyph handled separately");
            return null;
        }
        String pkg = readField(args.get(1), "mPackageName", String.class);
        if (pkg == null) {
            return null;
        }
        Drawable masked = AppIconShaper.maskedFor((Context) args.get(0), pkg, 0, MASK_AS_CIRCLE);
        if (masked == null) {
            return null;
        }
        Logger.v("aodIcon(" + pkg + "): "
                + (old == null ? "null" : old.getClass().getSimpleName()) + " -> masked icon");
        return masked;
    }

    private void install(ClassLoader cl) throws Throwable {
        Logger.i("loading into SystemUI, sdk=" + android.os.Build.VERSION.SDK_INT
                + " miosVersion=" + sysProp("ro.mi.os.version.code")
                + " region=" + sysProp("ro.miui.build.region"));

        entryClass = cl.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry");
        fSbn = entryClass.getDeclaredField("mSbn");
        fSbn.setAccessible(true);
        fIcons = entryClass.getDeclaredField("mIcons");
        fIcons.setAccessible(true);

        Class<?> iconPack =
                cl.loadClass("com.android.systemui.statusbar.notification.icon.IconPack");
        fSmallIconDescriptor = iconPack.getDeclaredField("mSmallIconDescriptor");
        fSmallIconDescriptor.setAccessible(true);
        fPeopleAvatarDescriptor = iconPack.getDeclaredField("mPeopleAvatarDescriptor");
        fPeopleAvatarDescriptor.setAccessible(true);

        try {
            mGetSystemUiContext = cl.loadClass("android.app.ActivityThread")
                    .getDeclaredMethod("getSystemUiContext");
            mGetSystemUiContext.setAccessible(true);
        } catch (Throwable t) {
            Logger.v("getSystemUiContext unavailable: " + t);
        }

        if (FIX_APP_ICON) {
            installLauncherIconHook(cl);
        }
        if (FIX_AOD_ICON) {
            watchForAodClasses();
            AodLoaderFinder.start();
        }
        if (!FIX_SMALL_ICON) {
            Logger.i("small-icon substitution disabled; only reshaping app icons");
            return;
        }

        Class<?> iconManager =
                cl.loadClass("com.android.systemui.statusbar.notification.icon.IconManager");

        XposedInterface.Hooker hook = chain -> {
            try {
                // HyperGestures: off (or HyperNotificationFix ahead of us) leaves the entry as is.
                if (!Gate.passThrough(null)) {
                    process(chain);
                }
            } catch (Throwable t) {
                Logger.e("process failed in " + chain.getExecutable(), t);
            }
            return chain.proceed();
        };

        int hooked = 0;
        for (Method m : iconManager.getDeclaredMethods()) {
            String n = m.getName();
            if ("createIcons".equals(n) || "updateIcons".equals(n)) {
                Xp.hook(m, hook);
                hooked++;
                Logger.i("hooked IconManager." + n + describeParams(m));
            }
        }
        if (hooked == 0) {
            Logger.i("WARNING: no IconManager.createIcons/updateIcons methods found");
        }
    }

    /**
     * Replaces the drawable returned by SystemUI's {@code NotificationIconProvider
     * .getLauncherIcon()} with the app's real adaptive icon.
     *
     * <p>This is the fix for the white rectangle. The framework's
     * {@code NotificationRowIconView.loadAppIcon()} calls this provider whenever the row uses the
     * launcher-icon style, then strips the view's padding and background — so whatever this
     * returns is drawn edge to edge with nothing behind it. SystemUI returns a launcher3
     * {@code FastBitmapDrawable} built from a pre-rasterized bitmap, which carries an opaque
     * plate; an {@link android.graphics.drawable.AdaptiveIconDrawable} instead masks itself to the
     * system icon shape when drawn. The framework explicitly anticipates that type, testing
     * {@code getDrawable() instanceof AdaptiveIconDrawable} in {@code adjustViewForIconStyle()}.
     */
    private void installLauncherIconHook(ClassLoader cl) {
        String base = "com.android.systemui.statusbar.notification.row.icon."
                + "NotificationRowIconViewInflaterFactory$createIconProvider$";
        int hooked = 0;
        for (int variant = 1; variant <= 2; variant++) {
            String name = base + variant;
            try {
                Class<?> c = cl.loadClass(name);
                Xp.hook(c.getDeclaredMethod("getLauncherIcon"), chain -> {
                    Object result = chain.proceed();
                    try {
                        // HyperGestures: off returns SystemUI's own icon.
                        if (Gate.passThrough(null)) {
                            return result;
                        }
                        Drawable masked = replaceLauncherIcon(chain.getThisObject(), result);
                        if (masked != null) {
                            return masked;
                        }
                    } catch (Throwable t) {
                        Logger.e("launcher icon replacement failed", t);
                    }
                    return result;
                });
                hooked++;
                Logger.i("hooked " + name + ".getLauncherIcon()");
            } catch (Throwable t) {
                Logger.v("provider variant " + variant + " unavailable: " + t);
            }
        }
        if (hooked == 0) {
            Logger.i("WARNING: no icon provider hooked; the white plate fix is inactive");
        }
    }

    /** Returns the replacement icon, or null to keep {@code old} as the result. */
    private Drawable replaceLauncherIcon(Object self, Object old) {
        if (self == null) {
            return null;
        }
        StatusBarNotification sbn = readField(self, "$sbn", StatusBarNotification.class);
        if (sbn == null) {
            return null;
        }
        Context ctx = readField(self, "$context", Context.class);
        if (ctx == null) {
            ctx = sysCtx;
        }
        int userId = Hidden.userId(sbn.getUser());
        String pkg = sbn.getPackageName();

        Drawable masked = AppIconShaper.maskedFor(ctx, pkg, userId, MASK_AS_CIRCLE);
        if (masked == null) {
            return null;
        }
        Logger.v("launcherIcon(" + pkg + "): "
                + (old == null ? "null" : old.getClass().getSimpleName())
                + " -> masked icon");
        return masked;
    }

    /** Reads a (possibly synthetic) field by name, walking up the class hierarchy. */
    private static <T> T readField(Object target, String name, Class<T> type) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                return type.isInstance(v) ? type.cast(v) : null;
            } catch (NoSuchFieldException e) {
                // Keep walking up.
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** SystemProperties is a hidden API, so it is read reflectively. */
    private static String sysProp(String key) {
        try {
            Method get = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, "?");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describeParams(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private void process(XposedInterface.Chain chain) throws Throwable {
        Object entry = findEntry(chain.getArgs());
        if (entry == null) {
            return;
        }

        Object sbnObj = fSbn.get(entry);
        if (!(sbnObj instanceof StatusBarNotification)) {
            return;
        }
        StatusBarNotification sbn = (StatusBarNotification) sbnObj;
        Notification n = sbn.getNotification();
        if (n == null) {
            return;
        }

        Context ctx = resolveContext(chain.getThisObject());
        if (ctx == null) {
            Logger.v("no SystemUI context yet");
            return;
        }
        int userId = Hidden.userId(sbn.getUser());
        String pkg = sbn.getPackageName();
        String key = userId + ":" + pkg;

        // Always clear the cached descriptors: on Android 17 getIconDescriptor() returns them
        // before consulting the notification, so a stale entry would mask our substitution.
        clearDescriptorCache(entry, key);

        if (skip.containsKey(key)) {
            return;
        }

        Icon generated = cache.get(key);
        if (generated == null) {
            Icon small = n.getSmallIcon();
            if (small == null) {
                Logger.v(key + ": notification has no small icon");
                skip.put(key, Boolean.TRUE);
                return;
            }
            Context appCtx = Hidden.packageContext(sbn, ctx);
            generated = SmallIconFixer.fix(appCtx != null ? appCtx : ctx, small, key);
            if (generated == null) {
                skip.put(key, Boolean.TRUE);
                return;
            }
            cache.put(key, generated);
        }

        if (!Hidden.setSmallIcon(n, generated)) {
            return;
        }
        Bundle extras = n.extras;
        if (extras != null) {
            // Stops HyperOS from replacing our icon with the launcher icon.
            extras.putBoolean("miui.isGrayscaleIcon", true);
        }
    }

    /** The entry argument's position differs between createIcons and updateIcons. */
    private Object findEntry(List<Object> args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a != null && entryClass.isInstance(a)) {
                return a;
            }
        }
        return null;
    }

    private void clearDescriptorCache(Object entry, String key) {
        try {
            Object icons = fIcons.get(entry);
            if (icons == null) {
                return;
            }
            fSmallIconDescriptor.set(icons, null);
            fPeopleAvatarDescriptor.set(icons, null);
        } catch (Throwable t) {
            Logger.v(key + ": could not clear descriptor cache: " + t);
        }
    }

    private Context resolveContext(Object iconManager) {
        Context c = sysCtx;
        if (c != null) {
            return c;
        }
        if (iconManager != null) {
            try {
                Field f = iconManager.getClass().getDeclaredField("context");
                f.setAccessible(true);
                Object v = f.get(iconManager);
                if (v instanceof Context) {
                    c = (Context) v;
                }
            } catch (Throwable ignored) {
                // Fall through to ActivityThread.
            }
        }
        if (c == null && mGetSystemUiContext != null) {
            try {
                Object at = Class.forName("android.app.ActivityThread")
                        .getMethod("currentActivityThread").invoke(null);
                Object v = mGetSystemUiContext.invoke(at);
                if (v instanceof Context) {
                    c = (Context) v;
                }
            } catch (Throwable t) {
                Logger.v("ActivityThread context lookup failed: " + t);
            }
        }
        if (c != null) {
            sysCtx = c;
            pm = c.getPackageManager();
            if (DUMP_BITMAPS) {
                setUpDumpDir(c);
            }
            Logger.i("resolved SystemUI context: " + c);
        }
        return c;
    }

    /** Generated bitmaps are written here for offline inspection; see {@link Logger#dumpDir}. */
    private void setUpDumpDir(Context c) {
        try {
            // HyperGestures: our own directory, so HyperNotificationFix's dumps stay apart.
            File dir = new File(c.getCacheDir(), "hg_notif");
            if (dir.isDirectory() || dir.mkdirs()) {
                Logger.dumpDir = dir.getAbsolutePath();
                Logger.i("bitmap dumps -> " + Logger.dumpDir);
            }
        } catch (Throwable t) {
            Logger.v("dump dir unavailable: " + t);
        }
    }
}
