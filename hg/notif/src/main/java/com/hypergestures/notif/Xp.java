/*
 * HyperNotificationFix - restores real notification icons on HyperOS / Android 17.
 * Ported into HyperGestures as com.hypergestures.notif (switch: hypergestures_notification_icons).
 * Idea from howard20181's Notification Icon Fix (io.github.howard20181.notificationiconfix); no
 * code taken from it.
 * Copyright (C) 2026 mexus. Licensed under CC BY-NC-SA 4.0; see LICENSE and NOTICE.
 */
package com.hypergestures.notif;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Holds the module instance the framework constructed, so hooks and logging can be installed
 * from static helper classes. The libxposed API is instance-based (unlike XposedBridge's
 * statics), and the {@link XposedModule} instance is the only object that can hook or log.
 *
 * <p>Typed as {@link XposedModule} rather than {@link XposedInterface} on purpose: calls
 * compile to invokevirtual against the class the entry point demonstrably extends, which keeps
 * the compile-only stubs honest about what must resolve at runtime.
 */
final class Xp {

    /** Set once from {@code onModuleLoaded}, before any hook is installed. */
    static volatile XposedModule module;

    private Xp() {
    }

    /** Installs {@code hooker} around {@code target} with default priority. */
    static XposedInterface.HookHandle hook(Executable target, XposedInterface.Hooker hooker) {
        return module.hook(target).intercept(hooker);
    }
}
