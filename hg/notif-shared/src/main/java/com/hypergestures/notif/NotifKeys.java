/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under CC BY-NC-SA 4.0; see LICENSE and NOTICE.
 */
package com.hypergestures.notif;

/**
 * HyperGestures: what the two APKs share about the notification icon fix. The hooks live in
 * their own small APK (com.hypergestures.notif, the LSPosed module: LSPosed copies every
 * classes*.dex of a module's APK into each process it hooks), the switch and its status line in
 * the main app's settings page; they talk only through these two Settings.Global keys. This
 * source is compiled into both APKs (hg/notif-shared), so the keys and their parsing cannot
 * drift apart. Plain Java, no Android or app classes.
 */
public final class NotifKeys {

    /**
     * The on/off switch, Settings.Global {@code hypergestures_notification_icons}: on only when
     * "1" ({@link #isOn}). The settings page writes it; the hooks watch it.
     */
    public static final String KEY = "hypergestures_notification_icons";

    /**
     * Settings.Global key SystemUI writes this boot's id to once our hooks are installed there,
     * so the settings page, which runs in the main app where the hooks never do, can tell
     * whether LSPosed loaded them this boot ({@link #hookedThisBoot}).
     */
    public static final String HOOKED_KEY = "hypergestures_notif_hooked";

    /** A boot id is a 36-character UUID; anything longer in the setting is not ours. */
    public static final int HOOKED_VALUE_MAX = 64;

    private NotifKeys() {
    }

    /** The switch's value: on only when exactly "1" (unset, "0" or anything else: off). */
    public static boolean isOn(String value) {
        return "1".equals(value);
    }

    /**
     * Whether {@link #HOOKED_KEY}'s {@code value} says the hooks loaded in this boot
     * ({@code bootId}; null if unreadable: then nothing can be said, so false).
     */
    public static boolean hookedThisBoot(String value, String bootId) {
        if (value == null || bootId == null || value.length() > HOOKED_VALUE_MAX) return false;
        return !bootId.isEmpty() && bootId.equals(value.trim());
    }
}
