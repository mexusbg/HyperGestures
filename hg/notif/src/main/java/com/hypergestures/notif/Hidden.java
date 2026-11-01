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
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access to framework methods that exist at runtime but are hidden from the public
 * {@code android.jar} we compile against. Each lookup is cached; failures degrade to a safe
 * default rather than throwing, because a single missing method must not break icon inflation
 * for every notification.
 */
final class Hidden {

    private static Method userHandleGetIdentifier;
    private static Method notificationSetSmallIcon;
    private static Method sbnGetPackageContext;
    private static boolean resolved;

    /** ActivityThread.currentApplication(), looked up once; null when unavailable. */
    private static volatile Method currentApplication;
    private static volatile boolean currentApplicationResolved;

    /** Fields by class and name, found walking up the hierarchy; {@link #MISSING} if absent. */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> FIELDS =
            new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private Hidden() {
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            userHandleGetIdentifier = UserHandle.class.getMethod("getIdentifier");
        } catch (Throwable t) {
            Logger.w("UserHandle.getIdentifier unavailable", t);
        }
        try {
            notificationSetSmallIcon = Notification.class.getMethod("setSmallIcon", Icon.class);
        } catch (Throwable t) {
            Logger.w("Notification.setSmallIcon unavailable", t);
        }
        try {
            sbnGetPackageContext =
                    StatusBarNotification.class.getMethod("getPackageContext", Context.class);
        } catch (Throwable t) {
            Logger.w("StatusBarNotification.getPackageContext unavailable", t);
        }
    }

    /** @return the user id, or 0 when it cannot be determined. */
    static int userId(UserHandle handle) {
        resolve();
        if (handle == null || userHandleGetIdentifier == null) {
            return 0;
        }
        try {
            return (Integer) userHandleGetIdentifier.invoke(handle);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** @return true when the icon was actually replaced. */
    static boolean setSmallIcon(Notification n, Icon icon) {
        resolve();
        if (notificationSetSmallIcon == null) {
            return false;
        }
        try {
            notificationSetSmallIcon.invoke(n, icon);
            return true;
        } catch (Throwable t) {
            Logger.w("setSmallIcon failed", t);
            return false;
        }
    }

    /** @return the notifying app's context, or {@code fallback} when unavailable. */
    static Context packageContext(StatusBarNotification sbn, Context fallback) {
        resolve();
        if (sbnGetPackageContext == null) {
            return fallback;
        }
        try {
            Object c = sbnGetPackageContext.invoke(sbn, fallback);
            return c instanceof Context ? (Context) c : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * HyperGestures: the process's Application ({@code ActivityThread.currentApplication()}), or
     * null before it is bound or when the lookup is unavailable. One lookup per process.
     */
    static Context currentApplication() {
        if (!currentApplicationResolved) {
            try {
                currentApplication = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication");
            } catch (Throwable t) {
                Logger.v("currentApplication unavailable: " + t);
            }
            currentApplicationResolved = true;
        }
        Method m = currentApplication;
        if (m == null) {
            return null;
        }
        try {
            Object app = m.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** HyperGestures: SystemUI's own context ({@code ActivityThread.getSystemUiContext()}). */
    static Context systemUiContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getMethod("currentActivityThread");
            Method get = at.getDeclaredMethod("getSystemUiContext");
            get.setAccessible(true);
            Object v = get.invoke(current.invoke(null));
            return v instanceof Context ? (Context) v : null;
        } catch (Throwable t) {
            Logger.v("ActivityThread context lookup failed: " + t);
            return null;
        }
    }

    /**
     * HyperGestures: a (possibly private or synthetic) field by name, walking up from {@code c};
     * null when there is none. Cached per class, so hooks that run per notification reflect once.
     */
    static Field field(Class<?> c, String name) {
        ConcurrentHashMap<String, Object> byName =
                FIELDS.computeIfAbsent(c, k -> new ConcurrentHashMap<>());
        Object f = byName.get(name);
        if (f == null) {
            f = MISSING;
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    Field found = k.getDeclaredField(name);
                    found.setAccessible(true);
                    f = found;
                    break;
                } catch (NoSuchFieldException e) {
                    // Keep walking up.
                } catch (Throwable t) {
                    break;
                }
            }
            byName.put(name, f);
        }
        return f instanceof Field ? (Field) f : null;
    }

    /** HyperGestures: reads {@link #field}; null when absent, unreadable or not a {@code type}. */
    static <T> T readField(Object target, String name, Class<T> type) {
        if (target == null) {
            return null;
        }
        Field f = field(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            Object v = f.get(target);
            return type.isInstance(v) ? type.cast(v) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
