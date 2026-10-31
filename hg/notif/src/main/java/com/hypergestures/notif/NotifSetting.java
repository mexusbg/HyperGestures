/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
 */
package com.hypergestures.notif;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * HyperGestures: the fix's on/off switch, Settings.Global {@code hypergestures_notification_icons}
 * ({@link NotifKeys#KEY}): on only when "1", so unset or "0" is off. The main app's settings page
 * writes it ("Fix notification icons", in System); the hooks read it here.
 *
 * <p>The hooks run in SystemUI and the AOD, per notification icon, so the value is cached per
 * process: the first hook call that finds the process's Application registers one
 * ContentObserver and reads the value once; after that only a change reads it again. A toggle
 * therefore applies to the next icon SystemUI builds, no restart needed. If the observer cannot
 * be registered the value read at first use stands until SystemUI (or the AOD) restarts. Until
 * the first read succeeds the fix counts as off.
 */
public final class NotifSetting {

    /** The Settings.Global switch ({@link NotifKeys#KEY}). */
    public static final String KEY = NotifKeys.KEY;

    static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";
    /** Tries to find SystemUI's Application before giving up on the mark, 2 s apart. */
    private static final int MARK_TRIES = 5;
    private static final long MARK_RETRY_MS = 2000;

    /** The scopes hooked so far this boot, in order (NotifKeys.hookedValue); guarded by itself. */
    private static final Set<String> sScopes = new LinkedHashSet<>();

    private static volatile boolean sOn;
    private static volatile boolean sWatching;

    private NotifSetting() {
    }

    /**
     * From SystemUI once hooks are installed for {@code scope} (NotifKeys.SCOPE_*): writes this
     * boot's id and every scope hooked so far to {@link NotifKeys#HOOKED_KEY}. At hook time
     * SystemUI has no Application yet, so this waits on the main looper until it has one (the
     * first message after the app is bound, a few retries at most), then writes off the main
     * thread. SystemUI holds WRITE_SECURE_SETTINGS. Fails soft: nothing may throw into SystemUI,
     * and a lost mark only makes the page say "not loaded".
     */
    static void markHooked(String scope) {
        synchronized (sScopes) {
            if (!sScopes.add(scope)) return;
        }
        try {
            Handler main = new Handler(Looper.getMainLooper());
            main.post(new Runnable() {
                private int mTries;

                @Override
                public void run() {
                    try {
                        Context c = processContext(null);
                        if (c == null) {
                            if (++mTries < MARK_TRIES) {
                                main.postDelayed(this, MARK_RETRY_MS);
                            } else {
                                Logger.i("hooked mark not written: no Application");
                            }
                            return;
                        }
                        ContentResolver cr = c.getContentResolver();
                        new Thread(() -> writeHooked(cr), "hg-notif-hooked").start();
                    } catch (Throwable t) {
                        Logger.w("hooked mark not written", t);
                    }
                }
            });
        } catch (Throwable t) {
            Logger.w("hooked mark not scheduled", t);
        }
    }

    private static synchronized void writeHooked(ContentResolver cr) {
        try {
            String bootId = bootId();
            List<String> scopes;
            synchronized (sScopes) {
                scopes = new ArrayList<>(sScopes);
            }
            String value = bootId == null ? null : NotifKeys.hookedValue(bootId, scopes);
            if (bootId == null) {
                Logger.i("hooked mark not written: boot id unreadable");
            } else if (Settings.Global.putString(cr, NotifKeys.HOOKED_KEY, value)) {
                Logger.i("hooked mark written for this boot: " + scopes);
            } else {
                Logger.i("hooked mark not written: the settings provider refused it");
            }
        } catch (Throwable t) {
            Logger.w("hooked mark not written", t);
        }
    }

    /** This boot's id (the kernel's random boot_id), or null if unreadable. */
    public static String bootId() {
        try (RandomAccessFile f = new RandomAccessFile(BOOT_ID_PATH, "r")) {
            byte[] bytes = new byte[NotifKeys.HOOKED_VALUE_MAX];
            int n = f.read(bytes);
            String id = n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII).trim() : "";
            return id.isEmpty() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The cached value, watching the setting from the first call that finds a context.
     *
     * @param ctx a context from the hook, used only while the process has no Application yet
     */
    static boolean on(Context ctx) {
        if (!sWatching) {
            watch(ctx);
        }
        return sOn;
    }

    private static synchronized void watch(Context ctx) {
        if (sWatching) {
            return;
        }
        Context c = processContext(ctx);
        if (c == null) {
            return; // no context yet: off for now, tried again on the next call
        }
        ContentResolver cr = c.getContentResolver();
        try {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY), false,
                    new ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange) {
                            read(cr);
                        }
                    });
            Logger.i("watching " + KEY);
        } catch (Throwable t) {
            Logger.w("cannot watch " + KEY + "; a change applies after a restart", t);
        }
        read(cr); // before sWatching, so on() never returns a value not yet read
        sWatching = true;
    }

    private static void read(ContentResolver cr) {
        try {
            boolean on = NotifKeys.isOn(Settings.Global.getString(cr, KEY));
            if (on != sOn || !sWatching) {
                Logger.i("notification icon fix " + (on ? "on" : "off"));
            }
            sOn = on;
        } catch (Throwable t) {
            Logger.w("cannot read " + KEY + "; keeping " + (sOn ? "on" : "off"), t);
        }
    }

    /**
     * The process's own Application first: a context handed to a hook may be another package's
     * (createPackageContext("com.miui.aod") inside SystemUI), whose resolver would carry the
     * wrong package for our uid.
     */
    private static Context processContext(Context hookContext) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable t) {
            Logger.v("currentApplication unavailable: " + t);
        }
        if (hookContext == null) {
            return null;
        }
        Context app = hookContext.getApplicationContext();
        return app != null ? app : hookContext;
    }
}
