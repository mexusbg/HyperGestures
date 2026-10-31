/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under PolyForm Noncommercial 1.0.0; see LICENSE and
 * NOTICE.
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
     * then a space and the scopes they were installed in, comma-separated ({@link #hookedValue}:
     * {@link #SCOPE_SYSTEMUI} first, {@link #SCOPE_AOD} once the always-on display's classes
     * were hooked inside SystemUI). The settings page, which runs in the main app where the hooks
     * never do, can so tell whether LSPosed loaded them this boot ({@link #hookedThisBoot}) and
     * where ({@link #hookedScopes}). An older APK wrote the id alone: SystemUI.
     */
    public static final String HOOKED_KEY = "hypergestures_notif_hooked";

    /** The hooks run in SystemUI (its notification icons). */
    public static final String SCOPE_SYSTEMUI = "systemui";
    /** The always-on display's icon hooks are installed (inside SystemUI, where it is drawn). */
    public static final String SCOPE_AOD = "aod";

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
        String v = value.trim();
        int space = v.indexOf(' ');
        return !bootId.isEmpty() && bootId.equals(space < 0 ? v : v.substring(0, space));
    }

    /**
     * The scopes {@link #HOOKED_KEY}'s {@code value} names for this boot ({@code bootId}), in
     * order; empty when it is not this boot's. The id alone (an older APK) means SystemUI; names
     * this APK does not know are dropped.
     */
    public static java.util.List<String> hookedScopes(String value, String bootId) {
        java.util.List<String> scopes = new java.util.ArrayList<>();
        if (!hookedThisBoot(value, bootId)) return scopes;
        String v = value.trim();
        int space = v.indexOf(' ');
        if (space < 0) {
            scopes.add(SCOPE_SYSTEMUI);
            return scopes;
        }
        for (String s : v.substring(space + 1).split(",")) {
            if ((SCOPE_SYSTEMUI.equals(s) || SCOPE_AOD.equals(s)) && !scopes.contains(s)) {
                scopes.add(s);
            }
        }
        return scopes;
    }

    /** {@link #HOOKED_KEY}'s value for {@code bootId} and {@code scopes} (in order). */
    public static String hookedValue(String bootId, java.util.Collection<String> scopes) {
        return bootId + " " + String.join(",", scopes);
    }
}
