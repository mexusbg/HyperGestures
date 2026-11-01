// HyperGestures: every binder call into HyperOS Shell that is written by hand (no AIDL in the
// vendored sources), and the rule that decides whether this phone's Shell still takes it.
//
// The transaction codes and parcel layouts are not pinned to one HyperOS build: HgShellInterfaces
// reads them by name from the phone's own Shell code (the generated Stub's TRANSACTION_<method>
// constants and the interface's declared methods) and resolve() matches them here. An outgoing
// call is used only when its code is there and exactly one method of that name takes exactly the
// parameters we write; an incoming call is parsed by hand only when exactly one method of that
// name starts with the parameters we read (values Shell appends after them are never read). A
// stale code on a oneway call would silently run whatever Shell method has that number now, so a
// call that is not confirmed is never made: its feature falls back (Feature). Checked against
// OS4.0.0.11 and OS4.0.0.13 (2026-11-01: identical codes and layouts).
package com.android.quickstep;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class HgShellCalls {
    private static final String SOSC = "com.android.wm.shell.sosc.ISoScSplitScreen";
    private static final String MTSM =
            "com.android.wm.shell.multitasking.common.IMultiTaskingStateManager";
    private static final String MTSL =
            "com.android.wm.shell.multitasking.common.IMultiTaskingStateChangeListener";
    private static final String RUNNER = "com.android.wm.shell.recents.IRecentsAnimationRunner";

    private static final String B = "Landroid/os/Bundle;";
    private static final String RT = "Landroid/window/RemoteTransition;";
    private static final String PI = "Landroid/app/PendingIntent;";
    private static final String SI = "Landroid/content/pm/ShortcutInfo;";
    private static final String R = "Landroid/graphics/Rect;";
    private static final String TI = "Landroid/app/ActivityManager$RunningTaskInfo;";

    /** A hand-written call: interface, method, JVM parameter descriptor, direction. */
    public enum Call {
        SOSC_START_TASK(SOSC, "startTask", "(II" + B + ")", false),
        SOSC_START_TASKS(SOSC, "startTasks", "(I" + B + "I" + B + "II" + RT + ")", false),
        SOSC_START_INTENT_AND_TASK(SOSC, "startIntentAndTask",
                "(" + PI + "I" + B + "I" + B + "II" + RT + ")", false),
        SOSC_START_SHORTCUT_AND_TASK(SOSC, "startShortcutAndTask",
                "(" + SI + B + "I" + B + "II" + RT + ")", false),
        SOSC_START_INTENTS(SOSC, "startIntents",
                "(" + PI + "I" + SI + B + PI + "I" + SI + B + "II" + RT + ")", false),
        SOSC_REGISTER_LISTENER(SOSC, "registerSoScSplitScreenListener",
                "(Lcom/android/wm/shell/sosc/ISoScSplitScreenListener;)", false),
        SOSC_UNREGISTER_LISTENER(SOSC, "unregisterSoScSplitScreenListener",
                "(Lcom/android/wm/shell/sosc/ISoScSplitScreenListener;)", false),
        MTSM_REGISTER_LISTENER(MTSM, "registerMultiTaskingStateListener",
                "(L" + MTSL.replace('.', '/') + ";)", false),
        MTSM_UNREGISTER_LISTENER(MTSM, "unregisterMultiTaskingStateListener",
                "(L" + MTSL.replace('.', '/') + ";)", false),
        /** HgSoScState reads root, leftTop, rightBottom, state, both tasks, minimized. */
        MTSM_ON_SOSC_STATE_CHANGED(MTSL, "onSoScStateChanged",
                "(" + R + R + R + "I" + TI + TI + "Z", true),
        /** SystemUiProxy's runner stub reads task ids, snapshots, toHome. */
        RUNNER_CANCELED_WITH_TO_HOME(RUNNER, "onAnimationCanceledWithToHome",
                "([I[Landroid/window/TaskSnapshot;Z", true);

        public final String iface;
        public final String method;
        /** Outgoing: the whole descriptor's parameter part; incoming: the prefix we read. */
        public final String params;
        public final boolean incoming;

        Call(String iface, String method, String params, boolean incoming) {
            this.iface = iface;
            this.method = method;
            this.params = params;
            this.incoming = incoming;
        }

        String label() {
            return iface.substring(iface.lastIndexOf('.') + 1) + "." + method;
        }
    }

    /** What falls back when one of its calls is not confirmed. */
    public enum Feature {
        /** Off: split pairs through AOSP's ISplitScreen. */
        SOSC_SPLIT(Call.SOSC_START_TASK, Call.SOSC_START_TASKS, Call.SOSC_START_INTENT_AND_TASK,
                Call.SOSC_START_SHORTCUT_AND_TASK, Call.SOSC_START_INTENTS),
        /** Off: HyperOS's split state is not followed (no SplitBounds for the gesture). */
        SPLIT_STATE(Call.SOSC_REGISTER_LISTENER, Call.SOSC_UNREGISTER_LISTENER,
                Call.MTSM_REGISTER_LISTENER, Call.MTSM_UNREGISTER_LISTENER,
                Call.MTSM_ON_SOSC_STATE_CHANGED),
        /** Off: HyperOS's cancel is not parsed by hand; AOSP's cancel paths stay. */
        RECENTS_CANCEL(Call.RUNNER_CANCELED_WITH_TO_HOME);

        final Call[] calls;

        Feature(Call... calls) {
            this.calls = calls;
        }
    }

    /** The phone's Shell code. */
    public interface Source {
        /** Stub.TRANSACTION_{@code method} of {@code iface}, or null if there is none. */
        @Nullable
        Integer code(String iface, String method);

        /** The parameter descriptor, e.g. "(IILandroid/os/Bundle;)", of each method so named. */
        List<String> params(String iface, String method);
    }

    /**
     * HyperOS's recents cancel (IRecentsAnimationRunner.onAnimationCanceledWithToHome) on every
     * HyperOS 4.0 build seen (OS4.0.0.11, .13): kept when the phone's Shell code cannot be read at
     * all, since HyperOS sends every cancel this way and an unhandled one leaves the gesture with
     * a dead controller.
     */
    static final int KNOWN_RECENTS_CANCEL_CODE = 8;

    /** Per call: its code, or why it is not used. */
    public static final class Result {
        private final Map<Call, Integer> mCodes;
        private final Map<Call, String> mReasons;
        /** False when the phone's Shell code could not be read at all (unavailable). */
        private final boolean mShellRead;

        private Result(Map<Call, Integer> codes, Map<Call, String> reasons, boolean shellRead) {
            mCodes = codes;
            mReasons = reasons;
            mShellRead = shellRead;
        }

        /** Whether the phone's Shell code was read (else every call is unconfirmed). */
        public boolean shellRead() {
            return mShellRead;
        }

        /**
         * The code to parse HyperOS's recents cancel at: the phone's, when confirmed; the known
         * one, when the Shell code could not be read at all; else -1 (read, and it changed:
         * nothing is parsed by hand).
         */
        public int recentsCancelCode() {
            if (usable(Call.RUNNER_CANCELED_WITH_TO_HOME)) {
                return code(Call.RUNNER_CANCELED_WITH_TO_HOME);
            }
            return mShellRead ? -1 : KNOWN_RECENTS_CANCEL_CODE;
        }

        public boolean usable(Call call) {
            return mCodes.containsKey(call);
        }

        public boolean usable(Feature feature) {
            for (Call c : feature.calls) {
                if (!usable(c)) return false;
            }
            return true;
        }

        /** The phone's code; only for a usable call. */
        public int code(Call call) {
            Integer code = mCodes.get(call);
            if (code == null) {
                throw new IllegalStateException(call.label() + " not usable: " + reason(call));
            }
            return code;
        }

        /** Why {@code call} is not used, "" when it is. */
        public String reason(Call call) {
            String why = mReasons.get(call);
            return why == null ? "" : why;
        }

        /** The first reason {@code feature} is off, "" when it is usable. */
        public String reason(Feature feature) {
            for (Call c : feature.calls) {
                if (!usable(c)) return c.label() + ": " + reason(c);
            }
            return "";
        }

        /** One line per call: "<Interface>.<method> code=<n> ok" or "... not used: <why>". */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (Call c : Call.values()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.label());
                if (usable(c)) {
                    sb.append(" code=").append(mCodes.get(c)).append(" ok");
                } else {
                    sb.append(" not used: ").append(reason(c));
                }
            }
            return sb.toString();
        }
    }

    private HgShellCalls() {}

    public static Result resolve(Source src) {
        Map<Call, Integer> codes = new EnumMap<>(Call.class);
        Map<Call, String> reasons = new EnumMap<>(Call.class);
        for (Call c : Call.values()) {
            Integer code = src.code(c.iface, c.method);
            if (code == null) {
                reasons.put(c, "no TRANSACTION_" + c.method);
                continue;
            }
            List<String> found = src.params(c.iface, c.method);
            if (found.size() != 1) {
                reasons.put(c, found.isEmpty() ? "no method " + c.method
                        : found.size() + " methods named " + c.method);
                continue;
            }
            String params = found.get(0);
            boolean match = c.incoming ? params.startsWith(c.params) : params.equals(c.params);
            if (!match) {
                reasons.put(c, "parameters changed: " + params);
                continue;
            }
            codes.put(c, code);
        }
        return new Result(codes, reasons, true);
    }

    /** Nothing confirmed, for one reason (the Shell code could not be read). */
    public static Result unavailable(String why) {
        Map<Call, String> reasons = new EnumMap<>(Call.class);
        for (Call c : Call.values()) reasons.put(c, why);
        return new Result(Collections.emptyMap(), reasons, false);
    }
}
