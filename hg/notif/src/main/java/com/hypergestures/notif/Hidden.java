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

import java.lang.reflect.Method;

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
}
