// HyperGestures: Save logs' "Recents and gestures only" filter (plan ruling R7; HgLogCollector).
// Keeps the logcat lines (-v threadtime) of our own process(es) and those of the system parts that
// recents, split screen, floating windows, transitions and our gestures go through, so a log
// posted publicly carries little of other apps. Everything else is dropped. Crash lines
// (AndroidRuntime, DEBUG) are kept only for us, SystemUI and system_server: two passes over the
// log, scan() every line first to learn which pids log those crashes, then keep() each line.
package com.android.quickstep;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HgLogFilter {
    /** Tags kept whole. */
    static final Set<String> TAGS = Set.of(
            "HyperGestures", // the module's log (hg_log)
            "LauncherProxyService", // HyperOS SystemUI's binding to the recents provider
            "OverviewProxyService", // AOSP's name for it
            "RecentTasksController",
            "ToastPresenter", "Toast", // toasts shown, or refused (Clear all's message)
            "am_crash", "am_anr", "am_kill"); // events buffer: crashes, ANRs, kills (Clear all)
    /**
     * Tag prefixes kept whole: ours (Hg*), HyperOS's split screen (SoSc*) and floating windows
     * (MiuiFreeform*, MiuiMultiWindow*), Shell's protolog groups (ShellTransitions, ShellRecents,
     * ShellSplitScreen...), recents (RecentsTransitionHandler, RecentsAnimationController...),
     * and the events buffer's task moves (wm_task_*).
     */
    static final String[] TAG_PREFIXES = {
            "Hg", "SoSc", "MiuiFreeform", "MiuiMultiWindow", "Shell", "Recents", "wm_task_"};
    /**
     * ActivityTaskManager lines kept: activity starts and tasks moved to the back. They name the
     * apps the user opened (the settings page's privacy note says so); needed for diagnosis.
     */
    static final String[] ATM_MARKERS = {"START u", "moveTaskToBack"};
    /** Any line naming our package (our process starting or dying, a freeze, a crash header). */
    static final String PACKAGE = "com.hypergestures";

    /** Crash tags: Java crashes (logged by the crashing process) and native ones (crash_dump). */
    static final Set<String> CRASH_TAGS = Set.of("AndroidRuntime", "DEBUG");
    /**
     * A crash line naming one of these makes its logging pid's crash lines kept: AndroidRuntime's
     * "Process: com.android.systemui, PID: n" and "FATAL EXCEPTION IN SYSTEM PROCESS", a
     * tombstone's ">>> com.android.systemui <<<" or ">>> system_server <<<".
     */
    static final String[] CRASH_NAMES = {
            "com.android.systemui", "system_server", "IN SYSTEM PROCESS", PACKAGE};
    /** Tags only system_server logs: their pid is system_server's. */
    static final Set<String> SYSTEM_SERVER_TAGS = Set.of("ActivityTaskManager", "WindowManager");

    /** "10-10 12:00:00.123  1234  1256 I Tag     : message" (logcat -v threadtime). */
    private static final Pattern LINE =
            Pattern.compile("^\\S+\\s+\\S+\\s+(\\d+)\\s+\\d+\\s+[A-Z]\\s(.*)$");

    private final Set<Integer> mPids;
    /** Pids whose crash lines are kept (scan()): SystemUI, system_server, their crash dumps. */
    private final Set<Integer> mCrashPids = new HashSet<>();

    /** @param ourPids the pids of our own processes: all their lines are kept */
    public HgLogFilter(Set<Integer> ourPids) {
        mPids = ourPids;
    }

    /** First pass: learns from {@code line} which pids' crash lines are kept. */
    public void scan(String line) {
        Parsed p = parse(line);
        if (p == null) return;
        if (SYSTEM_SERVER_TAGS.contains(p.tag)) {
            mCrashPids.add(p.pid);
        } else if (CRASH_TAGS.contains(p.tag)) {
            for (String name : CRASH_NAMES) {
                if (p.message.contains(name)) {
                    mCrashPids.add(p.pid);
                    return;
                }
            }
        }
    }

    /** Second pass: whether {@code line} goes into the filtered log. */
    public boolean keep(String line) {
        if (line.startsWith("--------- ")) return true; // "--------- beginning of crash"
        if (line.contains(PACKAGE)) return true;
        Parsed p = parse(line);
        if (p == null) return false;
        if (mPids.contains(p.pid)) return true;
        if (CRASH_TAGS.contains(p.tag)) return mCrashPids.contains(p.pid);
        return keepTag(p.tag, p.message);
    }

    static boolean keepTag(String tag, String message) {
        if (TAGS.contains(tag)) return true;
        for (String prefix : TAG_PREFIXES) {
            if (tag.startsWith(prefix)) return true;
        }
        switch (tag) {
            case "ActivityTaskManager":
                for (String marker : ATM_MARKERS) {
                    if (message.contains(marker)) return true;
                }
                return false;
            case "WindowManager":
                // Transition lines ("Transition", "transition", "TransitionController").
                return message.contains("ransition");
            case "NotificationService":
                // Only its toast lines ("Suppressing toast from package ...", "Toast already
                // killed ..."), not other apps' notifications.
                return message.contains("oast");
            default:
                return false;
        }
    }

    private static final class Parsed {
        final int pid;
        final String tag;
        final String message;

        Parsed(int pid, String tag, String message) {
            this.pid = pid;
            this.tag = tag;
            this.message = message;
        }
    }

    /** The pid, tag and message of a threadtime line, or null if it is not one. */
    private static Parsed parse(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        int pid;
        try {
            pid = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String rest = m.group(2);
        int colon = rest.indexOf(':');
        String tag = (colon < 0 ? rest : rest.substring(0, colon)).trim();
        String message = colon < 0 ? "" : rest.substring(colon + 1);
        return new Parsed(pid, tag, message);
    }
}
