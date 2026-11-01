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
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Restores colored notification icons on HyperOS 4 / Android 17.
 *
 * <p>The icon is substituted in {@code IconManager.createIcons} / {@code updateIcons}, before
 * the original body runs. Two things are essential on Android 17:
 *
 * <ol>
 *   <li>{@code IconManager.getIconDescriptor} returns a cached {@code IconPack
 *       .mSmallIconDescriptor} before it ever looks at the notification. Android 16 introduced
 *       a flow-based icon pipeline that can populate that cache first, so mutating the
 *       notification alone is not enough — the cached descriptor must be cleared too.
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

    /**
     * The always-on display's app. Its views are drawn inside SystemUI, through a ClassLoader of
     * their own (see {@link #watchForAodClasses}); it also runs as a process of its own.
     */
    private static final String AOD = "com.miui.aod";

    private static final String AOD_UTILS = "com.miui.aod.notification.NotificationUtils";

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
     * <p>The AOD is {@code com.miui.aod}'s own code, drawn inside SystemUI through a separate
     * ClassLoader, and it builds its icons on its own path rather than through
     * {@code IconManager} — so the shade hooks do not reach it. See {@link #installAodHook}.
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

    /**
     * HyperGestures: what the generated small icons may hold, in bytes. Only icons that were
     * solid blocks get one (at most ~190 KB, usually far less), so this is plenty.
     */
    private static final int SMALL_ICON_BUDGET = 1024 * 1024;

    /** HyperGestures: the cost of an entry that holds no bitmap of its own. */
    private static final int ENTRY_SIZE = 64;

    /** HyperGestures: cached for a small icon that is fine as it is (or cannot be improved). */
    private static final Object NO_FIX = new Object();

    private Field fSbn;
    private Field fIcons;
    private Field fSmallIconDescriptor;
    private Class<?> entryClass;

    private volatile Context sysCtx;

    /** HyperGestures: this process's name, from {@link #onModuleLoaded}; null if not given. */
    private volatile String processName;

    /** HyperGestures: true in SystemUI, where the hooked mark may be written. */
    private volatile boolean inSystemUi;

    /**
     * Loaders already tried. There can be more than one: the loader from
     * {@code createPackageContextAsUser} hosts the AOD classes but is <em>not</em> the one MIUI
     * inflates its views with, and hooks only apply to the Class objects of the loader they were
     * installed on. So every distinct loader is tried rather than stopping at the first success.
     */
    private final java.util.Set<ClassLoader> hookedAodLoaders =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * HyperGestures: the {@code NotificationUtils} classes hooked. Two loaders can resolve to the
     * same class (one delegating to the other), which must not be hooked twice.
     */
    private final java.util.Set<Class<?>> hookedAodClasses =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** HyperGestures: the {@code ViewGroup.addView} hook, unhooked once it has done its job. */
    private volatile XposedInterface.HookHandle addViewHook;

    /** For handing the live AOD ClassLoader back from the view-tree scan. */
    private static volatile HgNotifModule instance;

    /**
     * Generated small icons ({@link Fixed}) or {@link #NO_FIX}, by user, package and icon
     * ({@link #smallIconKey}). HyperGestures: keyed by the icon rather than the package, so an
     * app's other icons are measured for themselves; a failure to load or draw one is not cached
     * at all; bounded, and dropped per package by {@link PackageWatcher}.
     */
    private final LruCache<String, Object> smallIcons =
            new LruCache<String, Object>(SMALL_ICON_BUDGET) {
                @Override
                protected int sizeOf(String key, Object value) {
                    return value instanceof Fixed ? ((Fixed) value).bytes : ENTRY_SIZE;
                }
            };

    /** A generated small icon and what it costs the cache. */
    private static final class Fixed {
        final Icon icon;
        final int bytes;

        Fixed(Icon icon, int bytes) {
            this.icon = icon;
            this.bytes = bytes;
        }
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.module = this;
        processName = param.getProcessName();
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
        // HyperGestures: only the app's main process (named after the package). A secondary
        // process of SystemUI must not get the global addView hook, the AOD sweep or the mark.
        String proc = processName;
        if (proc != null && !proc.equals(pkg)) {
            Logger.i("not hooking " + pkg + " in its secondary process " + proc);
            return;
        }
        try {
            if (SYSTEMUI.equals(pkg)) {
                instance = this;
                inSystemUi = true;
                if (install(param.getDefaultClassLoader())) {
                    // HyperGestures: tell our settings page the hooks run this boot (fails soft).
                    NotifSetting.markHooked(NotifKeys.SCOPE_SYSTEMUI);
                }
            } else if (AOD.equals(pkg) && FIX_AOD_ICON) {
                tryInstallAodHook(param.getDefaultClassLoader(), "the " + AOD + " process", false);
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
                                    chain.getExecutable().getName(), false);
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

    /**
     * Fallback: the AOD's clock and icon views get added to SystemUI's own hierarchy.
     * HyperGestures: this taxes every view added in SystemUI, so it is unhooked once the live
     * loader is hooked ({@link #stopViewDiscovery}).
     */
    private void hookAddView() {
        try {
            Method addView = ViewGroup.class.getDeclaredMethod(
                    "addView", View.class, int.class, ViewGroup.LayoutParams.class);
            addViewHook = Xp.hook(addView, chain -> {
                try {
                    Object child = chain.getArg(0);
                    if (child instanceof View) {
                        Class<?> c = child.getClass();
                        if (c.getName().startsWith("com.miui.aod.")) {
                            tryInstallAodHook(c.getClassLoader(), c.getName(), true);
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

    /**
     * HyperGestures: the loader behind the live AOD views is hooked, so the view-based discovery
     * (the window sweep and the addView hook) ends. The createPackageContext hook stays, cheap and
     * rare, for an AOD update that brings a new loader.
     */
    private void stopViewDiscovery() {
        AodLoaderFinder.stop();
        XposedInterface.HookHandle h = addViewHook;
        if (h == null) {
            return;
        }
        addViewHook = null;
        try {
            // Posted: this may run inside the addView hook itself.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    h.unhook();
                    Logger.i("AOD loader hooked; no longer watching ViewGroup.addView");
                } catch (Throwable t) {
                    Logger.v("addView unhook failed: " + t);
                }
            });
        } catch (Throwable t) {
            Logger.v("addView unhook not scheduled: " + t);
        }
    }

    /** Lets the view-tree scan hand over the loader that owns the live AOD views. */
    static void offerAodLoader(ClassLoader loader, String via, boolean liveView) {
        HgNotifModule self = instance;
        if (self != null) {
            self.tryInstallAodHook(loader, via, liveView);
        }
    }

    /** @param liveView true when the loader comes from a view on screen (addView or the sweep) */
    private void tryInstallAodHook(ClassLoader loader, String via, boolean liveView) {
        if (loader == null) {
            return;
        }
        synchronized (this) {
            if (!hookedAodLoaders.add(loader)) {
                return;
            }
            Class<?> utils;
            try {
                utils = loader.loadClass(AOD_UTILS);
            } catch (Throwable t) {
                // A loader that sees some com.miui.aod classes but not the icon code. It stays in
                // the set so it is not retried.
                Logger.v("loader via " + via + " has no AOD NotificationUtils: " + t);
                return;
            }
            String loaderId = Integer.toHexString(System.identityHashCode(loader));
            try {
                String hooked = installAodHook(utils, loader);
                if (hooked == null) {
                    Logger.v("loader via " + via + " (loader " + loaderId
                            + ") shares AOD classes already hooked");
                } else {
                    if (inSystemUi) {
                        // HyperGestures: the page lists the AOD among the hooked scopes (fails
                        // soft).
                        NotifSetting.markHooked(NotifKeys.SCOPE_AOD);
                    }
                    Logger.i("AOD hooks installed (" + hooked + "), loader found via " + via
                            + " (loader " + loaderId + ")");
                }
                if (liveView) {
                    stopViewDiscovery();
                }
            } catch (Throwable t) {
                Logger.e("AOD hooks not installed through the loader found via " + via
                        + " (loader " + loaderId + ")", t);
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
     *
     * <p>HyperGestures: every lookup comes before the first hook, so a missing method leaves
     * nothing hooked. The view hooks ({@link AodIcons}) are a second line and do not decide
     * whether this succeeded.
     *
     * @return what was hooked, or null when {@code utils} is hooked already (through another
     *     loader that resolves to the same class)
     */
    private String installAodHook(Class<?> utils, ClassLoader cl) throws Throwable {
        Class<?> data = cl.loadClass(
                "com.miui.aod.notification.NotificationController$NotificationData");
        Method getAppIcon = utils.getDeclaredMethod(
                "getAppIcon", Context.class, data, Notification.class);
        if (!hookedAodClasses.add(utils)) {
            return null;
        }
        Logger.i("loading into the AOD, sdk=" + android.os.Build.VERSION.SDK_INT);
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
        // and is the only place the AOD's small icon can be reached: MIUI's AOD code builds it
        // from the notification on its own path, never through SystemUI's IconManager, so the
        // shade's replacement does not reach it.
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
        int views = AodIcons.install(cl, MASK_AS_CIRCLE);
        if (views == 0) {
            Logger.i("WARNING: no AOD view bind/setter methods hooked through this loader;"
                    + " getAppIcon and createIconWithBackground still are");
        }
        return "getAppIcon, " + composed + " createIconWithBackground, " + views
                + " view methods";
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
            if (Logger.VERBOSE) {
                Logger.v("aodGlyph: in=" + in.getClass().getSimpleName()
                        + " " + in.getIntrinsicWidth() + "x" + in.getIntrinsicHeight()
                        + (fixed == null ? " -> left as is" : " -> replaced with a legible glyph"));
            }
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
            if (Logger.VERBOSE) {
                Logger.v("aodIcon(" + Hidden.readField(args.get(1), "mPackageName", String.class)
                        + "): MIUI composed its own oval; glyph handled separately");
            }
            return null;
        }
        String pkg = Hidden.readField(args.get(1), "mPackageName", String.class);
        if (pkg == null) {
            return null;
        }
        Drawable masked = AppIconShaper.maskedFor((Context) args.get(0), pkg, 0, MASK_AS_CIRCLE);
        if (masked == null) {
            return null;
        }
        if (Logger.VERBOSE) {
            Logger.v("aodIcon(" + pkg + "): "
                    + (old == null ? "null" : old.getClass().getSimpleName()) + " -> masked icon");
        }
        return masked;
    }

    /**
     * Installs the SystemUI hooks. HyperGestures: each feature on its own, so a lookup one of
     * them cannot make leaves the others installed.
     *
     * @return true when the shade's icon hooks (app icon or small icon) are installed
     */
    private boolean install(ClassLoader cl) {
        Logger.i("loading into SystemUI, sdk=" + android.os.Build.VERSION.SDK_INT
                + " miosVersion=" + sysProp("ro.mi.os.version.code")
                + " region=" + sysProp("ro.miui.build.region"));
        boolean hooked = false;
        if (FIX_APP_ICON) {
            try {
                hooked |= installLauncherIconHook(cl) > 0;
            } catch (Throwable t) {
                Logger.e("app icon hooks not installed", t);
            }
        }
        if (FIX_AOD_ICON) {
            try {
                watchForAodClasses();
                AodLoaderFinder.start();
            } catch (Throwable t) {
                Logger.e("AOD discovery not started", t);
            }
        }
        if (FIX_SMALL_ICON) {
            try {
                hooked |= installSmallIconHook(cl) > 0;
            } catch (Throwable t) {
                Logger.e("small icon hooks not installed", t);
            }
        }
        return hooked;
    }

    /** @return how many IconManager methods were hooked */
    private int installSmallIconHook(ClassLoader cl) throws Throwable {
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
        return hooked;
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
     *
     * @return how many provider variants were hooked
     */
    private int installLauncherIconHook(ClassLoader cl) {
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
        return hooked;
    }

    /** Returns the replacement icon, or null to keep {@code old} as the result. */
    private Drawable replaceLauncherIcon(Object self, Object old) {
        if (self == null) {
            return null;
        }
        StatusBarNotification sbn = Hidden.readField(self, "$sbn", StatusBarNotification.class);
        if (sbn == null) {
            return null;
        }
        Context ctx = Hidden.readField(self, "$context", Context.class);
        if (ctx == null) {
            ctx = sysCtx;
        }
        int userId = Hidden.userId(sbn.getUser());
        String pkg = sbn.getPackageName();

        // HyperGestures: another user's app (a work profile's, for instance) keeps its badge.
        Drawable masked = AppIconShaper.maskedFor(ctx, pkg, userId, MASK_AS_CIRCLE);
        if (masked == null) {
            return null;
        }
        if (Logger.VERBOSE) {
            Logger.v("launcherIcon(" + pkg + "): "
                    + (old == null ? "null" : old.getClass().getSimpleName())
                    + " -> masked icon");
        }
        return masked;
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
        Icon small = n.getSmallIcon();
        if (small == null) {
            return;
        }

        Context ctx = resolveContext(chain.getThisObject());
        if (ctx == null) {
            Logger.v("no SystemUI context yet");
            return;
        }
        PackageWatcher.ensure(ctx);
        int userId = Hidden.userId(sbn.getUser());
        String pkg = sbn.getPackageName();
        String label = userId + ":" + pkg;

        Icon generated = fixedSmallIcon(sbn, ctx, small, userId, pkg, label);
        if (generated == null) {
            return; // left alone: whatever SystemUI cached for it stands
        }
        // Already ours when this notification was substituted on an earlier pass.
        if (generated != small && !Hidden.setSmallIcon(n, generated)) {
            return;
        }

        // Clear the cached descriptor whenever our icon is to show: on Android 17
        // getIconDescriptor() returns it before consulting the notification, so an entry cached
        // before our substitution (an earlier bind, or the flow pipeline) would mask it.
        // HyperGestures: only then, and only the small icon's. The people avatar's descriptor is
        // not ours (rebuilding it costs SystemUI a shortcut lookup), and a notification we leave
        // alone keeps what SystemUI cached for it.
        clearSmallIconDescriptor(entry, label);
        Bundle extras = n.extras;
        if (extras != null) {
            // Stops HyperOS from replacing our icon with the launcher icon.
            extras.putBoolean("miui.isGrayscaleIcon", true);
        }
    }

    /**
     * The replacement for {@code small}, cached; null to leave it alone. HyperGestures: a failure
     * to load or draw the icon (the app's package not ready, or mid-update) is not cached, so the
     * next update tries again.
     */
    private Icon fixedSmallIcon(StatusBarNotification sbn, Context ctx, Icon small, int userId,
                                String pkg, String label) {
        String key = smallIconKey(userId, pkg, small);
        Object cached = key == null ? null : smallIcons.get(key);
        if (cached instanceof Fixed) {
            return ((Fixed) cached).icon;
        }
        if (cached != null) {
            return null; // NO_FIX
        }
        Bitmap glyph;
        try {
            Context appCtx = Hidden.packageContext(sbn, ctx);
            glyph = SmallIconFixer.fix(appCtx != null ? appCtx : ctx, small, label);
        } catch (Throwable t) {
            Logger.v(label + ": small icon not measured (" + t + "), tried again next time");
            return null;
        }
        if (glyph == null) {
            if (key != null) {
                smallIcons.put(key, NO_FIX);
            }
            return null;
        }
        Icon icon = Icon.createWithBitmap(glyph);
        if (key != null) {
            smallIcons.put(key, new Fixed(icon, glyph.getAllocationByteCount()));
        }
        // Our own icon maps to itself (its bitmap is counted once, above): a later pass over a
        // notification that already carries it finds it without measuring it again.
        String own = smallIconKey(userId, pkg, icon);
        if (own != null) {
            smallIcons.put(own, new Fixed(icon, ENTRY_SIZE));
        }
        return icon;
    }

    /**
     * HyperGestures: the small-icon cache key, {@code user:package:icon}, where the icon part
     * names the artwork: the resource (package and id), the bitmap (its generation id, unique in
     * the process) or the URI. Null for an icon with no stable identity (raw data), which is then
     * measured every time rather than cached.
     */
    private static String smallIconKey(int userId, String pkg, Icon icon) {
        String id = null;
        try {
            switch (icon.getType()) {
                case Icon.TYPE_RESOURCE:
                    id = "res:" + icon.getResPackage() + ":" + Integer.toHexString(icon.getResId());
                    break;
                case Icon.TYPE_BITMAP:
                case Icon.TYPE_ADAPTIVE_BITMAP:
                    Bitmap b = icon.getBitmap();
                    id = b == null ? null : "bmp:" + b.getGenerationId();
                    break;
                case Icon.TYPE_URI:
                case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                    id = "uri:" + icon.getUri();
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Logger.v("no identity for " + icon + ": " + t);
        }
        return id == null ? null : userId + ":" + pkg + ":" + id;
    }

    /** HyperGestures: from {@link PackageWatcher}; drops the package's generated small icons. */
    static void forgetPackage(String pkg) {
        HgNotifModule self = instance;
        if (self == null) {
            return;
        }
        // The package is matched as a whole key segment: as the poster, or as the owner of an
        // icon resource.
        String part = ":" + pkg + ":";
        for (String k : self.smallIcons.snapshot().keySet()) {
            if (k.contains(part)) {
                self.smallIcons.remove(k);
            }
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

    private void clearSmallIconDescriptor(Object entry, String label) {
        try {
            Object icons = fIcons.get(entry);
            if (icons != null) {
                fSmallIconDescriptor.set(icons, null);
            }
        } catch (Throwable t) {
            Logger.v(label + ": could not clear descriptor cache: " + t);
        }
    }

    private Context resolveContext(Object iconManager) {
        Context c = sysCtx;
        if (c != null) {
            return c;
        }
        if (iconManager != null) {
            c = Hidden.readField(iconManager, "context", Context.class);
        }
        if (c == null) {
            c = Hidden.systemUiContext();
        }
        if (c != null) {
            sysCtx = c;
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
