package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.quickstep.HgShellCalls.Call;
import com.android.quickstep.HgShellCalls.Feature;
import com.android.quickstep.HgShellCalls.Result;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HgShellCallsTest {
    private static final String SOSC = "com.android.wm.shell.sosc.ISoScSplitScreen";
    private static final String MTSM =
            "com.android.wm.shell.multitasking.common.IMultiTaskingStateManager";
    private static final String MTSL =
            "com.android.wm.shell.multitasking.common.IMultiTaskingStateChangeListener";
    private static final String RUNNER = "com.android.wm.shell.recents.IRecentsAnimationRunner";
    private static final String RT = "Landroid/window/RemoteTransition;";
    private static final String B = "Landroid/os/Bundle;";
    private static final String PI = "Landroid/app/PendingIntent;";
    private static final String SI = "Landroid/content/pm/ShortcutInfo;";
    private static final String R = "Landroid/graphics/Rect;";
    private static final String TI = "Landroid/app/ActivityManager$RunningTaskInfo;";

    /** The phone's Shell code: TRANSACTION_ constants and declared methods, as on OS4.0.0.13. */
    private static final class FakeShell implements HgShellCalls.Source {
        final Map<String, Integer> codes = new HashMap<>();
        final Map<String, List<String>> params = new HashMap<>();

        FakeShell put(String iface, String method, int code, String desc) {
            codes.put(iface + "#" + method, code);
            params.computeIfAbsent(iface + "#" + method, k -> new ArrayList<>()).add(desc);
            return this;
        }

        @Override
        public Integer code(String iface, String method) {
            return codes.get(iface + "#" + method);
        }

        @Override
        public List<String> params(String iface, String method) {
            List<String> p = params.get(iface + "#" + method);
            return p == null ? Collections.emptyList() : p;
        }
    }

    private static FakeShell os13() {
        return new FakeShell()
                .put(SOSC, "startTask", 8, "(II" + B + ")")
                .put(SOSC, "startTasks", 11, "(I" + B + "I" + B + "II" + RT + ")")
                .put(SOSC, "startIntentAndTask", 17, "(" + PI + "I" + B + "I" + B + "II" + RT + ")")
                .put(SOSC, "startShortcutAndTask", 18, "(" + SI + B + "I" + B + "II" + RT + ")")
                .put(SOSC, "startIntents", 20,
                        "(" + PI + "I" + SI + B + PI + "I" + SI + B + "II" + RT + ")")
                .put(SOSC, "registerSoScSplitScreenListener", 2,
                        "(Lcom/android/wm/shell/sosc/ISoScSplitScreenListener;)")
                .put(SOSC, "unregisterSoScSplitScreenListener", 3,
                        "(Lcom/android/wm/shell/sosc/ISoScSplitScreenListener;)")
                .put(MTSM, "registerMultiTaskingStateListener", 1,
                        "(L" + MTSL.replace('.', '/') + ";)")
                .put(MTSM, "unregisterMultiTaskingStateListener", 2,
                        "(L" + MTSL.replace('.', '/') + ";)")
                .put(MTSL, "onSoScStateChanged", 1, "(" + R + R + R + "I" + TI + TI + "ZII)")
                .put(RUNNER, "onAnimationCanceledWithToHome", 8,
                        "([I[Landroid/window/TaskSnapshot;Z)");
    }

    @Test public void allPresent_allUsable() {
        Result r = HgShellCalls.resolve(os13());
        assertEquals(8, r.code(Call.SOSC_START_TASK));
        assertEquals(11, r.code(Call.SOSC_START_TASKS));
        assertEquals(17, r.code(Call.SOSC_START_INTENT_AND_TASK));
        assertEquals(18, r.code(Call.SOSC_START_SHORTCUT_AND_TASK));
        assertEquals(20, r.code(Call.SOSC_START_INTENTS));
        assertEquals(2, r.code(Call.SOSC_REGISTER_LISTENER));
        assertEquals(3, r.code(Call.SOSC_UNREGISTER_LISTENER));
        assertEquals(1, r.code(Call.MTSM_REGISTER_LISTENER));
        assertEquals(2, r.code(Call.MTSM_UNREGISTER_LISTENER));
        assertEquals(1, r.code(Call.MTSM_ON_SOSC_STATE_CHANGED));
        assertEquals(8, r.code(Call.RUNNER_CANCELED_WITH_TO_HOME));
        for (Feature f : Feature.values()) assertTrue(f.name(), r.usable(f));
        for (Call c : Call.values()) assertEquals("", r.reason(c));
    }

    /** An OTA renumbers a method: the phone's number is used, not the one we knew. */
    @Test public void renumbered_usesNewCode() {
        FakeShell shell = os13();
        shell.codes.put(SOSC + "#startTask", 9);
        assertEquals(9, HgShellCalls.resolve(shell).code(Call.SOSC_START_TASK));
    }

    @Test public void missingCode_unusable() {
        FakeShell shell = os13();
        shell.codes.remove(SOSC + "#startTask");
        Result r = HgShellCalls.resolve(shell);
        assertFalse(r.usable(Call.SOSC_START_TASK));
        assertFalse(r.usable(Feature.SOSC_SPLIT));
        assertTrue(r.reason(Call.SOSC_START_TASK).contains("no TRANSACTION_startTask"));
    }

    /** A changed outgoing layout disables only the feature that sends it. */
    @Test public void changedParams_unusable() {
        FakeShell shell = os13();
        shell.params.put(SOSC + "#startTasks",
                new ArrayList<>(List.of("(I" + B + "I" + B + "II)")));
        Result r = HgShellCalls.resolve(shell);
        assertFalse(r.usable(Call.SOSC_START_TASKS));
        assertFalse(r.usable(Feature.SOSC_SPLIT));
        assertTrue(r.usable(Feature.SPLIT_STATE));
        assertTrue(r.usable(Feature.RECENTS_CANCEL));
    }

    /** Two methods share the name: which layout the number belongs to is unknown. */
    @Test public void overloaded_unusable() {
        FakeShell shell = os13().put(SOSC, "startTask", 8, "(I" + B + ")");
        assertFalse(HgShellCalls.resolve(shell).usable(Call.SOSC_START_TASK));
    }

    /** Incoming: values appended after the ones we read are never read; still safe. */
    @Test public void incomingExtraTrailingParams_usable() {
        assertTrue(HgShellCalls.resolve(os13()).usable(Call.MTSM_ON_SOSC_STATE_CHANGED));
    }

    @Test public void incomingInsertedParam_unusable() {
        FakeShell shell = os13();
        shell.params.put(MTSL + "#onSoScStateChanged",
                new ArrayList<>(List.of("(" + R + "I" + R + R + "I" + TI + TI + "ZII)")));
        Result r = HgShellCalls.resolve(shell);
        assertFalse(r.usable(Call.MTSM_ON_SOSC_STATE_CHANGED));
        assertFalse(r.usable(Feature.SPLIT_STATE));
    }

    /** Outgoing calls must match exactly: an appended parameter changes what Shell reads. */
    @Test public void outgoingExtraTrailingParam_unusable() {
        FakeShell shell = os13();
        shell.params.put(SOSC + "#startTask", new ArrayList<>(List.of("(II" + B + "Z)")));
        assertFalse(HgShellCalls.resolve(shell).usable(Call.SOSC_START_TASK));
    }

    @Test public void unavailable_everythingUnusable() {
        Result r = HgShellCalls.unavailable("HyperOS split code unreadable: x");
        for (Feature f : Feature.values()) assertFalse(r.usable(f));
        assertEquals("HyperOS split code unreadable: x", r.reason(Call.SOSC_START_TASK));
        assertThrows(IllegalStateException.class, () -> r.code(Call.SOSC_START_TASK));
    }

    @Test public void describe_oneLinePerCall() {
        String d = HgShellCalls.resolve(os13()).describe();
        assertEquals(Call.values().length, d.split("\n").length);
        assertTrue(d.contains("ISoScSplitScreen.startTask code=8 ok"));
    }
    /** HyperOS's cancel: the phone's code when confirmed. */
    @Test public void recentsCancelCode_confirmed_phonesCode() {
        assertEquals(8, HgShellCalls.resolve(os13()).recentsCancelCode());
        FakeShell shell = os13();
        shell.codes.put(RUNNER + "#onAnimationCanceledWithToHome", 9);
        assertEquals(9, HgShellCalls.resolve(shell).recentsCancelCode());
    }

    /** Read and changed: nothing is parsed by hand. */
    @Test public void recentsCancelCode_changed_none() {
        FakeShell shell = os13();
        shell.params.put(RUNNER + "#onAnimationCanceledWithToHome",
                new ArrayList<>(List.of("(Z[I[Landroid/window/TaskSnapshot;)")));
        assertEquals(-1, HgShellCalls.resolve(shell).recentsCancelCode());
    }

    /**
     * Not read at all (Shell jar unreadable): keep the cancel code every HyperOS 4.0 build so far
     * uses, as before; dropping it leaves the gesture with a dead controller.
     */
    @Test public void recentsCancelCode_unreadable_knownCode() {
        assertEquals(8, HgShellCalls.unavailable("HyperOS split code unreadable: x")
                .recentsCancelCode());
    }
}
