// HyperGestures: reads HyperOS Shell's binder interfaces from the phone itself, so the hand-written
// calls (HgShellCalls) follow the firmware instead of one build's numbers.
//
// Shell's code is Miui-WindowManager-Shell.jar in /system_ext/framework (world-readable; Shell runs
// it in SystemUI). Its generated Stubs keep TRANSACTION_<method> constants and its interfaces keep
// their declared methods: both are read through reflection over a PathClassLoader on the jar,
// whose parent is the boot class loader so none of our own copies of these classes can stand in
// for Shell's. Classes are loaded without initialising them; no Shell code runs. Measured on the
// phone (OS4.0.0.13, 2026-11-01): 2 ms. Done once per process and cached; TouchInteractionService
// warms it up on a helper thread, and a caller that comes first waits for the same one-time pass.
//
// The same jar also checks the Shell AIDL this APK compiles in (AOSP's IRecentTasks, ISplitScreen
// and the rest, AOSP_INTERFACES): their codes cannot follow the phone at runtime, so a difference
// is only reported (status card, Save logs). SystemUI's own ILauncherProxy and ISystemUiProxy
// cannot be checked here: the phone's SystemUI has their names stripped by R8; the developer
// tool tools/hgtools/firmware_diff.py compares those after an OTA.
package com.android.quickstep;

import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dalvik.system.PathClassLoader;

public final class HgShellInterfaces {
    private static final String TAG = "HgShellCompat";
    private static final String FRAMEWORK_DIR = "/system_ext/framework";
    private static final String SHELL_JAR = FRAMEWORK_DIR + "/Miui-WindowManager-Shell.jar";

    @Nullable private static HgShellCalls.Result sResult;
    @Nullable private static ClassLoader sShellLoader;

    /** Shell AIDL interfaces this APK compiles in (com.android.wm.shell. + name). */
    private static final String[] AOSP_INTERFACES = {
            "recents.IRecentTasks", "recents.IRecentTasksListener",
            "recents.IRecentsAnimationController", "recents.IRecentsAnimationRunner",
            "splitscreen.ISplitScreen", "splitscreen.ISplitScreenListener",
            "splitscreen.ISplitSelectListener", "back.IBackAnimation",
            "shared.IShellTransitions", "shared.IHomeTransitionListener",
            "shared.IFocusTransitionListener", "desktopmode.IDesktopTaskListener",
            "startingsurface.IStartingWindow", "startingsurface.IStartingWindowListener",
            "common.pip.IPip", "common.pip.IPipAnimationListener", "draganddrop.IDragAndDrop",
            "onehanded.IOneHanded", "bubbles.IBubbles", "bubbles.IBubblesListener",
    };

    private HgShellInterfaces() {}

    /** The calls this phone's Shell takes; resolved once per process. */
    public static synchronized HgShellCalls.Result get() {
        if (sResult == null) {
            sResult = resolveFrom(shellJar());
            Log.i(TAG, summary(sResult));
        }
        return sResult;
    }

    /** Resolves on a helper thread, so the first split or gesture does not pay for it. */
    public static void prewarm() {
        UI_HELPER_EXECUTOR.execute(HgShellInterfaces::get);
    }

    /** The phone's Shell jar, or null if none can be read. */
    @Nullable
    static String shellJar() {
        if (new File(SHELL_JAR).canRead()) return SHELL_JAR;
        File[] jars = new File(FRAMEWORK_DIR).listFiles(
                (dir, name) -> name.contains("Shell") && name.endsWith(".jar"));
        if (jars == null) return null;
        for (File jar : jars) {
            if (jar.canRead()) return jar.getPath();
        }
        return null;
    }

    static HgShellCalls.Result resolveFrom(@Nullable String jarPath) {
        try {
            if (jarPath == null) throw new IllegalStateException("no Shell jar found");
            if (!new File(jarPath).canRead()) {
                throw new IllegalStateException(jarPath + " not readable");
            }
            ClassLoader loader = new PathClassLoader(jarPath,
                    ClassLoader.getSystemClassLoader().getParent());
            HgShellCalls.Result result = HgShellCalls.resolve(new LoaderSource(loader));
            synchronized (HgShellInterfaces.class) {
                sShellLoader = loader;
            }
            return result;
        } catch (Throwable t) {
            return HgShellCalls.unavailable("HyperOS split code unreadable: " + t);
        }
    }

    private static String summary(HgShellCalls.Result r) {
        StringBuilder sb = new StringBuilder();
        for (HgShellCalls.Feature f : HgShellCalls.Feature.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.name()).append(r.usable(f) ? " ok" : " off (" + r.reason(f) + ")");
        }
        return sb.toString();
    }

    /** The JVM descriptor of a parameter list, e.g. "(ILandroid/os/Bundle;)". */
    static String jvmParams(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> t : types) sb.append(descriptor(t));
        return sb.append(')').toString();
    }

    private static String descriptor(Class<?> t) {
        if (t.isArray()) return "[" + descriptor(t.getComponentType());
        if (t == int.class) return "I";
        if (t == boolean.class) return "Z";
        if (t == long.class) return "J";
        if (t == float.class) return "F";
        if (t == double.class) return "D";
        if (t == byte.class) return "B";
        if (t == char.class) return "C";
        if (t == short.class) return "S";
        if (t == void.class) return "V";
        return "L" + t.getName().replace('.', '/') + ";";
    }

    /** Reflection over the phone's Shell classes. A missing class or member is "not there". */
    private static final class LoaderSource implements HgShellCalls.Source {
        private final ClassLoader mLoader;

        LoaderSource(ClassLoader loader) {
            mLoader = loader;
        }

        @Nullable
        @Override
        public Integer code(String iface, String method) {
            try {
                Field f = Class.forName(iface + "$Stub", false, mLoader)
                        .getDeclaredField("TRANSACTION_" + method);
                f.setAccessible(true);
                return f.getInt(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }

        @Override
        public List<String> params(String iface, String method) {
            try {
                List<String> found = new ArrayList<>();
                for (Method m : Class.forName(iface, false, mLoader).getDeclaredMethods()) {
                    if (m.getName().equals(method)) found.add(jvmParams(m.getParameterTypes()));
                }
                return found;
            } catch (ReflectiveOperationException | LinkageError e) {
                return Collections.emptyList();
            }
        }
    }

    /** The status card's name for a feature that fell back. */
    static String featureLabel(HgShellCalls.Feature feature) {
        switch (feature) {
            case SOSC_SPLIT: return "HyperOS split screen";
            case SPLIT_STATE: return "split card on swipe up";
            default: return "recents cancel handling";
        }
    }

    /** fallbackLabels for this phone. */
    public static List<String> unusableFeatureLabels() {
        return fallbackLabels(get());
    }

    /**
     * What fell back, for the status card: one line when the Shell code could not be read at all,
     * else each feature that fell back with why ("<label> (<Interface>.<method>: <reason>)").
     */
    static List<String> fallbackLabels(HgShellCalls.Result r) {
        List<String> labels = new ArrayList<>();
        if (!r.shellRead()) {
            labels.add("HyperOS Shell code unreadable");
            return labels;
        }
        for (HgShellCalls.Feature f : HgShellCalls.Feature.values()) {
            if (!r.usable(f)) labels.add(featureLabel(f) + " (" + r.reason(f) + ")");
        }
        return labels;
    }

    /**
     * Our compiled-in Shell AIDL codes that differ from the phone's: "<Iface>.<method>: ours N,
     * phone M" or "...: missing on the phone". Empty when they all match, when nothing could be
     * compared, or when the Shell code could not be read (aospSummary and unusableFeatureLabels
     * tell those apart).
     */
    public static List<String> aospMismatches() {
        return aospCheck().mismatches;
    }

    private static final class AospCheck {
        int compared;
        final List<String> mismatches = new ArrayList<>();
    }

    private static AospCheck aospCheck() {
        get();
        ClassLoader phone;
        synchronized (HgShellInterfaces.class) {
            phone = sShellLoader;
        }
        AospCheck check = new AospCheck();
        if (phone == null) return check;
        ClassLoader ours = HgShellInterfaces.class.getClassLoader();
        for (String name : AOSP_INTERFACES) {
            String stub = "com.android.wm.shell." + name + "$Stub";
            Map<String, Integer> mine = transactionCodes(stub, ours);
            Map<String, Integer> theirs = transactionCodes(stub, phone);
            if (mine == null || theirs == null) continue; // one side lacks it: nothing to compare
            check.compared += mine.size();
            check.mismatches.addAll(
                    compareCodes(name.substring(name.lastIndexOf('.') + 1), mine, theirs));
        }
        return check;
    }

    /**
     * The compiled-in check in one line: "not checked" when none of our codes were found (R8 must
     * keep the generated TRANSACTION_ fields: proguard.flags), never a false "all match".
     */
    static String aospSummary(int compared, List<String> mismatches) {
        if (compared == 0) return "not checked (no compiled-in codes found)";
        if (mismatches.isEmpty()) return "all " + compared + " codes match";
        return compared + " codes compared; " + String.join("; ", mismatches);
    }

    /** Ours-only names and differing codes, in our name order; the phone's extras are ignored. */
    static List<String> compareCodes(String iface, Map<String, Integer> ours,
            Map<String, Integer> phone) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(ours).entrySet()) {
            Integer theirs = phone.get(e.getKey());
            if (theirs == null) {
                out.add(iface + "." + e.getKey() + ": missing on the phone");
            } else if (!theirs.equals(e.getValue())) {
                out.add(iface + "." + e.getKey() + ": ours " + e.getValue() + ", phone " + theirs);
            }
        }
        return out;
    }

    /** method -> code from a Stub's TRANSACTION_ constants, or null if the class is not there. */
    @Nullable
    private static Map<String, Integer> transactionCodes(String stub, ClassLoader loader) {
        try {
            Map<String, Integer> codes = new TreeMap<>();
            for (Field f : Class.forName(stub, false, loader).getDeclaredFields()) {
                if (!f.getName().startsWith("TRANSACTION_") || f.getType() != int.class) continue;
                f.setAccessible(true);
                codes.put(f.getName().substring("TRANSACTION_".length()), f.getInt(null));
            }
            return codes;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** The full check for Save logs: every call, every mismatch, what cannot be checked. */
    public static String report() {
        StringBuilder sb = new StringBuilder(get().describe());
        AospCheck check = aospCheck();
        sb.append("\ncompiled-in Shell AIDL: ")
                .append(aospSummary(check.compared, check.mismatches));
        sb.append("\nILauncherProxy, ISystemUiProxy: not checkable on the phone (names removed)");
        return sb.toString();
    }
}
