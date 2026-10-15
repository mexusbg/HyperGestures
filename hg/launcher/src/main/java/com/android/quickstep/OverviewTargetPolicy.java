// HyperGestures: overview target decision, extracted so it is testable and so a package with no
// HOME activity (a recents-only provider) always uses the fallback RecentsActivity.
package com.android.quickstep;

public final class OverviewTargetPolicy {
    private OverviewTargetPolicy() {}

    public static boolean useFallback(boolean hasOwnHome, boolean isHomeDisabled,
            boolean defaultHomeIsNull, boolean isDefaultHome) {
        if (!hasOwnHome) return true;
        return isHomeDisabled || !(defaultHomeIsNull || isDefaultHome);
    }
}
