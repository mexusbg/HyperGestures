package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.quickstep.HgShellCalls.Call;
import com.android.quickstep.HgShellCalls.Feature;
import com.android.quickstep.HgShellCalls.Result;

import org.junit.Test;

import java.util.Map;

public class HgShellInterfacesTest {
    @Test public void jvmParams_primitivesObjectsArraysNested() {
        assertEquals("(IZ[ILjava/lang/String;[Ljava/util/Map$Entry;)",
                HgShellInterfaces.jvmParams(new Class<?>[] {
                        int.class, boolean.class, int[].class, String.class,
                        Map.Entry[].class}));
        assertEquals("(JFDBCS)", HgShellInterfaces.jvmParams(new Class<?>[] {
                long.class, float.class, double.class, byte.class, char.class, short.class}));
        assertEquals("()", HgShellInterfaces.jvmParams(new Class<?>[0]));
    }

    /** No Shell jar on this phone: every call falls back, nothing throws. */
    @Test public void loadFailure_noJar_allUnusable() {
        Result r = HgShellInterfaces.resolveFrom(null);
        for (Feature f : Feature.values()) assertFalse(r.usable(f));
        assertTrue(r.reason(Call.SOSC_START_TASK).startsWith("HyperOS split code unreadable"));
    }

    @Test public void loadFailure_missingFile_allUnusable() {
        Result r = HgShellInterfaces.resolveFrom("/no/such/Miui-WindowManager-Shell.jar");
        for (Feature f : Feature.values()) assertFalse(r.usable(f));
        assertTrue(r.reason(Call.SOSC_START_TASK).startsWith("HyperOS split code unreadable"));
    }

    /** Ours vs the phone's codes for one interface: differences and ours-only names reported. */
    @Test public void compareCodes_reportsChangedAndMissing() {
        Map<String, Integer> ours = new java.util.TreeMap<>(Map.of(
                "startRecentsTransition", 6, "getRecentTasks", 3, "removeTask", 8));
        Map<String, Integer> phone = Map.of(
                "startRecentsTransition", 7, "getRecentTasks", 3, "xiaomiExtra", 30);
        assertEquals(java.util.List.of(
                        "IRecentTasks.removeTask: missing on the phone",
                        "IRecentTasks.startRecentsTransition: ours 6, phone 7"),
                HgShellInterfaces.compareCodes("IRecentTasks", ours, phone));
    }

    @Test public void compareCodes_identical_nothing() {
        Map<String, Integer> codes = Map.of("a", 1, "b", 2);
        assertTrue(HgShellInterfaces.compareCodes("IX", codes, codes).isEmpty());
    }

    @Test public void featureLabels_forTheStatusRow() {
        assertEquals("HyperOS split screen",
                HgShellInterfaces.featureLabel(Feature.SOSC_SPLIT));
        assertEquals("split card on swipe up",
                HgShellInterfaces.featureLabel(Feature.SPLIT_STATE));
        assertEquals("recents cancel handling",
                HgShellInterfaces.featureLabel(Feature.RECENTS_CANCEL));
    }
    /** R8 left no TRANSACTION_ fields on our side: say so, never "all match". */
    @Test public void aospSummary_nothingCompared_notChecked() {
        assertEquals("not checked (no compiled-in codes found)",
                HgShellInterfaces.aospSummary(0, java.util.List.of()));
    }

    @Test public void aospSummary_countsAndMismatches() {
        assertEquals("all 120 codes match", HgShellInterfaces.aospSummary(120, java.util.List.of()));
        assertEquals("120 codes compared; IX.a: ours 1, phone 2",
                HgShellInterfaces.aospSummary(120, java.util.List.of("IX.a: ours 1, phone 2")));
    }
    /** The status row's detail: one line when the Shell code could not be read at all. */
    @Test public void fallbackLabels_unreadable_oneLine() {
        assertEquals(java.util.List.of("HyperOS Shell code unreadable"),
                HgShellInterfaces.fallbackLabels(
                        HgShellCalls.unavailable("HyperOS split code unreadable: x")));
    }

    /** Read, and something changed: each feature that fell back, with why. */
    @Test public void fallbackLabels_changed_featureWithReason() {
        HgShellCalls.Source onlyCancel = new HgShellCalls.Source() {
            @Override public Integer code(String iface, String method) {
                return method.equals("onAnimationCanceledWithToHome") ? 8 : null;
            }

            @Override public java.util.List<String> params(String iface, String method) {
                return java.util.List.of("([I[Landroid/window/TaskSnapshot;Z)");
            }
        };
        assertEquals(java.util.List.of(
                        "HyperOS split screen (ISoScSplitScreen.startTask: no TRANSACTION_startTask)",
                        "split card on swipe up (ISoScSplitScreen.registerSoScSplitScreenListener:"
                                + " no TRANSACTION_registerSoScSplitScreenListener)"),
                HgShellInterfaces.fallbackLabels(HgShellCalls.resolve(onlyCancel)));
    }
}
