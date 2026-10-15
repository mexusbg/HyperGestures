/*
 * Compile-only stub of the libxposed API (https://github.com/libxposed/api, Apache-2.0).
 * See XposedInterface.java for why this exists and why it must never be packaged.
 */
package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();

        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();

        ApplicationInfo getApplicationInfo();

        boolean isFirstPackage();

        ClassLoader getDefaultClassLoader();
    }

    default void onModuleLoaded(ModuleLoadedParam param) {
    }

    default void onPackageLoaded(PackageLoadedParam param) {
    }
}
