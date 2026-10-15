package com.android.quickstep;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public class HgLogFilterTest {
    private final HgLogFilter mFilter = new HgLogFilter(Set.of(7450));

    private static String line(int pid, String tag, String message) {
        return String.format("10-10 12:00:00.123 %5d %5d I %-8s: %s", pid, pid + 3, tag, message);
    }

    /** Every line of our own process is kept, whatever its tag. */
    @Test public void keepsOurProcess() {
        assertTrue(mFilter.keep(line(7450, "RecentsView", "onAttachedToWindow")));
        assertTrue(mFilter.keep(line(7450, "chatty", "anything")));
    }

    @Test public void dropsOtherAppsLines() {
        assertFalse(mFilter.keep(line(1234, "WhatsApp", "message from Alice")));
        assertFalse(mFilter.keep(line(1234, "ActivityManager", "Start proc 99:com.example/u0a1")));
    }

    @Test public void keepsRecentsAndGestureTagsOfOtherProcesses() {
        assertTrue(mFilter.keep(line(2311, "HgBack", "x")));
        assertTrue(mFilter.keep(line(2311, "SoScSplitScreen", "x")));
        assertTrue(mFilter.keep(line(2311, "MiuiFreeformModeTaskRepository", "x")));
        assertTrue(mFilter.keep(line(2311, "ShellTransitions", "x")));
        assertTrue(mFilter.keep(line(2311, "RecentsTransitionHandler", "x")));
        assertTrue(mFilter.keep(line(2311, "LauncherProxyService", "x")));
        assertTrue(mFilter.keep(line(0, "HyperGestures", "service: module version 1")));
        assertTrue(mFilter.keep(line(1555, "wm_task_moved", "[42,1,0]")));
        assertTrue(mFilter.keep(line(1555, "am_kill", "[0,4242,com.example,900,cleared]")));
    }

    /** ActivityTaskManager and WindowManager only for starts, moves to back and transitions. */
    @Test public void keepsOnlyTheRelevantSystemServerLines() {
        assertTrue(mFilter.keep(line(1555, "ActivityTaskManager",
                "START u0 {act=android.intent.action.MAIN cmp=com.example/.Main} from uid 10187")));
        assertTrue(mFilter.keep(line(1555, "ActivityTaskManager", "moveTaskToBack: Task{42}")));
        assertFalse(mFilter.keep(line(1555, "ActivityTaskManager", "Displayed com.example")));
        assertTrue(mFilter.keep(line(1555, "WindowManager", "Transition 812 created")));
        assertTrue(mFilter.keep(line(1555, "WindowManager", "Finish transition 812")));
        assertFalse(mFilter.keep(line(1555, "WindowManager", "Changing focus to Window{...}")));
    }

    /** Toast lines, so a suppressed toast shows in Save logs; not other notifications. */
    @Test public void keepsToastLines() {
        assertTrue(mFilter.keep(line(1555, "NotificationService",
                "Suppressing toast from package com.example by user request.")));
        assertFalse(mFilter.keep(line(1555, "NotificationService",
                "enqueueNotificationInternal: pkg=com.example id=1")));
        assertTrue(mFilter.keep(line(1555, "ToastPresenter", "Showing toast")));
        assertTrue(mFilter.keep(line(9000, "Toast", "show: caller = x")));
    }

    @Test public void keepsLinesNamingOurPackage() {
        assertTrue(mFilter.keep(line(1555, "ActivityManager",
                "Start proc 7450:com.hypergestures/u0a187 for service")));
        assertTrue(mFilter.keep("Process: com.hypergestures"));
    }

    @Test public void keepsBufferSeparatorsAndDropsOtherUnparsedLines() {
        assertTrue(mFilter.keep("--------- beginning of crash"));
        assertFalse(mFilter.keep(""));
        assertFalse(mFilter.keep("garbage line"));
    }

    /** Two passes, as HgLogCollector runs them. */
    private static boolean[] filter(HgLogFilter f, String... lines) {
        for (String l : lines) f.scan(l);
        boolean[] kept = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) kept[i] = f.keep(lines[i]);
        return kept;
    }

    /** Java crashes: ours, SystemUI's and system_server's whole, other apps' not at all. */
    @Test public void keepsOnlyOurAndSystemJavaCrashes() {
        boolean[] kept = filter(new HgLogFilter(Set.of(7450)),
                line(7450, "AndroidRuntime", "FATAL EXCEPTION: main"),
                line(2311, "AndroidRuntime", "FATAL EXCEPTION: main"),
                line(2311, "AndroidRuntime", "Process: com.android.systemui, PID: 2311"),
                line(2311, "AndroidRuntime", "java.lang.NullPointerException"),
                line(1555, "AndroidRuntime", "*** FATAL EXCEPTION IN SYSTEM PROCESS: android.ui"),
                line(1555, "AndroidRuntime", "java.lang.IllegalStateException"),
                line(9000, "AndroidRuntime", "FATAL EXCEPTION: main"),
                line(9000, "AndroidRuntime", "Process: com.example.bank, PID: 9000"),
                line(9000, "AndroidRuntime", "java.lang.RuntimeException: secret"));
        assertArrayEquals(new boolean[] {true, true, true, true, true, true, false, false, false},
                kept);
    }

    /** Native crashes: crash_dump's lines for SystemUI and system_server, not other apps'. */
    @Test public void keepsOnlySystemNativeCrashes() {
        boolean[] kept = filter(new HgLogFilter(Set.of(7450)),
                line(3001, "DEBUG", "*** *** *** *** *** *** *** *** *** *** *** *** ***"),
                line(3001, "DEBUG", "pid: 2311, tid: 2311, name: ndroid.systemui"
                        + "  >>> com.android.systemui <<<"),
                line(3001, "DEBUG", "      #00 pc 0004 /system/lib64/libc.so"),
                line(3002, "DEBUG", "pid: 9000, tid: 9000, name: example  >>> com.example <<<"),
                line(3002, "DEBUG", "      #00 pc 0004 /system/lib64/libc.so"));
        assertArrayEquals(new boolean[] {true, true, true, false, false}, kept);
    }

    /** system_server's pid is learnt from its own tags, so its crash lines are kept. */
    @Test public void learnsSystemServerFromItsTags() {
        boolean[] kept = filter(new HgLogFilter(Set.of(7450)),
                line(1555, "AndroidRuntime", "java.lang.IllegalStateException"),
                line(1555, "ActivityTaskManager", "Displayed com.example"));
        assertArrayEquals(new boolean[] {true, false}, kept);
    }

    /** A tag with no message (no colon) still parses. */
    @Test public void lineWithoutColon() {
        assertTrue(mFilter.keep("10-10 12:00:00.123  2311  2312 I HgEdgeBack"));
        assertFalse(mFilter.keep("10-10 12:00:00.123  2311  2312 I Other"));
    }
}
