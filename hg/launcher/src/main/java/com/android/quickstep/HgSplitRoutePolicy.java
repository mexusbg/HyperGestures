// HyperGestures: which Shell split-screen interface starts a split pair, extracted so it is
// testable.
//
// HyperOS 4 SystemUI hands the recents provider two split interfaces in the onInitialize bundle:
// AOSP's ISplitScreen and Xiaomi's ISoScSplitScreen (key "extra_shell_sosc_split_screen"). With
// SoSc on (Settings.System "wmshell.sosc_enable", default 1; 1 on the Xiaomi 17 Ultra), AOSP's
// StageCoordinator does not make its two stage roots adjacent (updateAdjacentRoots and
// onRootTaskAppeared skip setAdjacentRoots when SoScUtils.isSoScSupported()), so a pair started
// through ISplitScreen does not lay out as split screen; MiuiHome starts splits through SoSc.
//
// ISoScSplitScreen has no AIDL here: HgSoScSplitScreen writes its transaction codes and parcels
// as one HyperOS build's generated proxy does. A later build may renumber them, and a oneway call
// with a stale code silently runs whatever Shell method now has it, so SoSc is used only on the
// build the APK was made for (HgBuildConfig.EXPECTED_FINGERPRINT) and only if the binder names
// the expected interface.
package com.android.quickstep;

public final class HgSplitRoutePolicy {
    private HgSplitRoutePolicy() {}

    /** Shell's default for "wmshell.sosc_enable" (SoScUtilsImpl reads it with default 1). */
    public static final int SOSC_ENABLE_DEFAULT = 1;

    /**
     * @param hasSoScBinder SystemUI passed an ISoScSplitScreen binder
     * @param soscEnable the value of Settings.System "wmshell.sosc_enable" (or
     *     {@link #SOSC_ENABLE_DEFAULT} when it is unset or cannot be read)
     * @return true to start split pairs through ISoScSplitScreen, false for AOSP's ISplitScreen
     */
    public static boolean useSoSc(boolean hasSoScBinder, int soscEnable) {
        return hasSoScBinder && soscEnable == 1;
    }

    /**
     * Whether SystemUI's ISoScSplitScreen binder may be used at all.
     *
     * @param deviceFingerprint the phone's Build.FINGERPRINT
     * @param expectedFingerprint the build the transaction codes were taken from
     *     (HgBuildConfig.EXPECTED_FINGERPRINT)
     * @param descriptor the binder's interface descriptor, or null if it could not be read
     * @param expectedDescriptor the descriptor the pinned proxy writes as its interface token
     */
    public static boolean soscBinderUsable(String deviceFingerprint, String expectedFingerprint,
            String descriptor, String expectedDescriptor) {
        return expectedFingerprint != null && !expectedFingerprint.isEmpty()
                && expectedFingerprint.equals(deviceFingerprint)
                && expectedDescriptor != null && expectedDescriptor.equals(descriptor);
    }
}
