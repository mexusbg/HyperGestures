// HyperGestures: Save logs (plan ruling R7). One zip, HyperGestures-logs-<yyyyMMdd-HHmmss>.zip,
// saved to Downloads through MediaStore (no storage permission), so a user can attach it to a
// GitHub issue or copy it to a PC without adb. It holds:
//   logcat.txt     logcat -d of the main, system, crash and events buffers (READ_LOGS, privileged),
//                  optionally filtered to recents and gestures (HgLogFilter);
//   dumpsys.txt    our TouchInteractionService (its ActiveGestureLog) and, unfiltered only, the
//                  activity manager's recents and activities, which list every app's tasks
//                  (DUMP, privileged);
//   module-log.txt the module's log copy (hg_mirror_sync writes it into our external files dir,
//                  which the settings page creates, as our own uid), and module-log.old.txt,
//                  the module's previous log;
//   info.txt       device, build, versions, our settings (the module's version, update and
//                  status values among them) and the system ones we read, HOME, how each
//                  command ended and any file that could not be read.
// Runs on a background thread the settings page starts; each command has a time limit.
package com.android.quickstep;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import com.android.quickstep.interaction.HgBackPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HgLogCollector {
    private HgLogCollector() {}

    private static final String TAG = "HgLogCollector";

    /** The module's log copy in our external files dir (hg_log's HG_LOG_MIRROR). */
    public static final String MODULE_LOG = "module-log.txt";
    /** The copy hg_rotate_log moved aside. */
    static final String MODULE_LOG_OLD = "module-log.old.txt";
    /** The line service.sh logs at every boot (hg_log_version). */
    static final String MODULE_VERSION_MARK = "service: module version ";
    /**
     * The running module's version, Settings.Global: service.sh writes it at every boot
     * (hg_publish_version), "0.3.9.38-alpha (50) boot=<boot id>". Settings, not files in our data
     * dir: root must never write into a dir we control (a link there could lead it anywhere).
     */
    static final String MODULE_VERSION_KEY = "hypergestures_module_version";
    /**
     * The module version installed but not booted yet, Settings.Global: customize.sh writes it
     * at install ("0.3.9.39-alpha (51)"), service.sh deletes it at the next boot.
     */
    static final String MODULE_UPDATE_KEY = "hypergestures_module_update";
    /** Where the module wrote the same facts up to 0.3.9.38's dev builds: deleted (ours). */
    static final String[] OLD_MODULE_FILES = {"module-version", "module-update", "module-status"};
    /** More than any module value holds: a longer one is not ours. */
    static final int MODULE_VALUE_MAX_CHARS = 4096;

    static final String[] LOGCAT =
            {"logcat", "-d", "-b", "main,system,crash,events", "-v", "threadtime"};
    /** Long enough for Android's log access prompt, if the system shows one. */
    static final long LOGCAT_TIMEOUT_S = 60;
    /** Our TouchInteractionService's dump (ActiveGestureLog): always. */
    static final String[] DUMPSYS_OURS = {"dumpsys", "activity", "service",
            "com.hypergestures/com.android.quickstep.TouchInteractionService"};
    /** Every app's tasks and activities: only when the logs are not filtered. */
    static final String[][] DUMPSYS_SYSTEM = {
            {"dumpsys", "activity", "recents"},
            {"dumpsys", "activity", "activities"},
    };
    /** How much of the module's log copy moduleVersionFromLog() reads: its end. */
    static final int VERSION_TAIL_BYTES = 64 * 1024;
    static final long DUMPSYS_TIMEOUT_S = 15;

    /** Our Settings.Global keys, listed in info.txt even when unset. */
    static final String[] GLOBAL_KEYS = {
            HgRecentsSettings.RECENTS_LAYOUT, HgRecentsSettings.BLUR,
            HgRecentsSettings.BLUR_FALLBACK, HgRecentsSettings.HEADER,
            HgRecentsSettings.ICON_SHAPE, HgRecentsSettings.CARD_ACTIONS,
            HgRecentsSettings.CARD_LOCK_SWIPE, HgRecentsSettings.CORNER_DRAG,
            HgRecentsSettings.SPLIT_GESTURE, HgRecentsSettings.FLOATING_SHRINK,
            HgRecentsSettings.CLEAR_KILLS, HgRecentsSettings.DISMISS_KILLS,
            HgRecentsSettings.BACK_STYLE,
            HgRecentsSettings.HOME_NOTICE, HgHomeBack.SETTING,
            HgRecentsSettings.HIDE_GESTURE_LINE, HgRecentsSettings.NOTIFICATION_ICONS,
            HgRecentsSettings.APP_INFO_FIX,
            HgBackPolicy.BACK_PATH_SETTING,
            // The module's facts for us (not switches): its version, an installed update and
            // this boot's status.
            MODULE_VERSION_KEY, MODULE_UPDATE_KEY, HgModuleStatus.KEY,
            // Ours for the module's health checks (HgHealthFacts) and their off switch.
            HgHealthFacts.PROVIDER_BOUND, HgHealthFacts.GESTURE_MONITOR,
            HgRecentsSettings.HEALTH_FACTS, HgRecentsSettings.SETTINGS_MIGRATION,
    };
    static final String[] SYSTEM_GLOBAL_KEYS = {HgRecentsLayout.MIUI_STYLE, "force_fsg_nav_bar"};
    static final String[] SECURE_KEYS = {"navigation_mode", HgBlurSupport.MIUI_ENABLE,
            "back_gesture_inset_scale_left", "back_gesture_inset_scale_right"};
    /** The haptic keys show which one HyperOS's toggle flips (interaction/HgBackHaptics). */
    static final String[] SYSTEM_KEYS = {HgRamIndicator.SHOW_MEM_INFO, "haptic_feedback_disable",
            "haptic_feedback_enabled", "haptic_feedback_level", "haptic_feedback_intensity"};

    /** The zip's file name for a save at {@code nowMs}. */
    static String zipName(long nowMs) {
        return "HyperGestures-logs-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(nowMs))
                + ".zip";
    }

    /** The module version a log line names (hg_log_version), or null. */
    static String moduleVersionOf(String line) {
        int i = line.indexOf(MODULE_VERSION_MARK);
        if (i < 0) return null;
        String version = line.substring(i + MODULE_VERSION_MARK.length()).trim();
        return version.isEmpty() ? null : version;
    }

    /** The latest module version named in {@code text} (log lines), or null. */
    static String moduleVersionIn(String text) {
        String version = null;
        for (String line : text.split("\n")) {
            String v = moduleVersionOf(line);
            if (v != null) version = v;
        }
        return version;
    }

    /** A module version as the module names it (hg_publish_version, its log line). */
    public static final class ModuleVersion {
        public final String name;
        /** Empty when none is named. */
        public final String code;

        ModuleVersion(String name, String code) {
            this.name = name;
            this.code = code;
        }

        /** "0.3.9.38-alpha (50)", as the log line names it; the name alone without a code. */
        public String display() {
            return code.isEmpty() ? name : name + " (" + code + ")";
        }

        /** The version display() gives, read back ("0.3.9.38-alpha (50)"); null for null. */
        static ModuleVersion fromDisplay(String display) {
            if (display == null || display.trim().isEmpty()) return null;
            String s = display.trim();
            int open = s.lastIndexOf(" (");
            if (open > 0 && s.endsWith(")")) {
                return new ModuleVersion(s.substring(0, open), s.substring(open + 2,
                        s.length() - 1).trim());
            }
            return new ModuleVersion(s, "");
        }

        /** Same version: the names match, and the codes too when both name one. */
        boolean sameAs(ModuleVersion other) {
            return other != null && name.equals(other.name)
                    && (code.isEmpty() || other.code.isEmpty() || code.equals(other.code));
        }
    }

    /** Where the running module's version came from (RunningModule.source). */
    static final int FROM_NONE = 0;
    /** This boot's hypergestures_module_version: the module runs this boot. */
    static final int FROM_SETTING = 1;
    /** No hypergestures_module_version (not written): the log copy's latest boot line. */
    static final int FROM_LOG = 2;
    /** Only an earlier boot names one: the module has not published this boot (not running). */
    static final int FROM_EARLIER_BOOT = 3;

    /** The running module's version and where it came from. */
    public static final class RunningModule {
        /** Null when nothing names one (FROM_NONE). */
        public final ModuleVersion version;
        public final int source;

        RunningModule(ModuleVersion version, int source) {
            this.version = version;
            this.source = version == null ? FROM_NONE : source;
        }

        /** Whether this boot runs it (as far as the app can tell). */
        public boolean running() {
            return source == FROM_SETTING || source == FROM_LOG;
        }
    }

    /**
     * The running module from hypergestures_module_version ({@code value}, null if unset), this
     * boot's id ({@code bootId}, null if unreadable) and the log copy's latest version line
     * ({@code logVersion}, null if none). The value counts only when its boot= is this boot's; an
     * earlier boot's means the module has not published this boot, so the version (the log's,
     * else the value's) is only an earlier boot's. With our boot id unreadable a value can't be
     * placed in either, so the module is unknown (FROM_NONE), as HgModuleStatus.forBoot treats
     * the status.
     */
    static RunningModule runningModule(String value, String bootId, String logVersion) {
        ModuleVersion set = parseModuleVersion(value);
        ModuleVersion log = ModuleVersion.fromDisplay(logVersion);
        if (set == null) return new RunningModule(log, FROM_LOG);
        if (bootId == null) return new RunningModule(null, FROM_NONE);
        if (isThisBoot(bootIdOf(value), bootId)) return new RunningModule(set, FROM_SETTING);
        return new RunningModule(log != null ? log : set, FROM_EARLIER_BOOT);
    }

    /** True when {@code valueBootId} is this boot's id; never when ours is unknown. */
    static boolean isThisBoot(String valueBootId, String bootId) {
        return bootId != null && bootId.equals(valueBootId);
    }

    /**
     * The key=value pairs of a module value (hypergestures_module_status: one line, pairs
     * separated by ';'; newlines separate them too), trimmed; the last of a key wins.
     */
    static Map<String, String> parseKeyValues(String text) {
        Map<String, String> values = new TreeMap<>();
        for (String pair : text.split("[;\n]")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            values.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return values;
    }

    /** The kernel's id of this boot, as hg_boot_id reads it. */
    static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    /** This boot's id, or null if it can't be read. */
    static String bootId() {
        try (RandomAccessFile f = new RandomAccessFile(BOOT_ID_PATH, "r")) {
            byte[] bytes = new byte[64];
            int n = f.read(bytes);
            String id = n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII).trim() : "";
            return id.isEmpty() ? null : id;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "boot id unreadable: " + e);
            return null;
        }
    }

    /** The marker before the boot id in hypergestures_module_version. */
    static final String BOOT_MARK = " boot=";

    /**
     * The version a module value names ("0.3.9.38-alpha (50)", with " boot=<id>" after it in
     * hypergestures_module_version), or null if it names none: unset, too long, or not that shape.
     */
    static ModuleVersion parseModuleVersion(String value) {
        if (value == null || value.length() > MODULE_VALUE_MAX_CHARS) return null;
        String s = value.trim();
        int boot = s.lastIndexOf(BOOT_MARK);
        if (boot >= 0) s = s.substring(0, boot).trim();
        int open = s.lastIndexOf(" (");
        if (open <= 0 || !s.endsWith(")")) return null;
        String name = s.substring(0, open).trim();
        String code = s.substring(open + 2, s.length() - 1).trim();
        if (name.isEmpty() || !code.matches("[0-9]*")) return null;
        return new ModuleVersion(name, code);
    }

    /** The boot id hypergestures_module_version names, or null if none. */
    static String bootIdOf(String value) {
        if (value == null) return null;
        int boot = value.lastIndexOf(BOOT_MARK);
        if (boot < 0) return null;
        String id = value.substring(boot + BOOT_MARK.length()).trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * The installed update to name next to the running version: {@code update} when it is not
     * {@code running} (null when unknown; ModuleVersion.sameAs), else null.
     */
    static ModuleVersion pendingUpdate(ModuleVersion running, ModuleVersion update) {
        if (update == null) return null;
        return update.sameAs(running) ? null : update;
    }

    /** One of the module's Settings.Global values, or null if unset or unreadable. */
    static String moduleValue(Context context, String key) {
        try {
            return Settings.Global.getString(context.getContentResolver(), key);
        } catch (RuntimeException e) {
            Log.w(TAG, key + " unreadable: " + e);
            return null;
        }
    }

    /**
     * Deletes the files earlier builds of the module wrote into our device-protected package dir
     * (OLD_MODULE_FILES): ours to delete, and nothing reads them any more. A few unlinks; a link
     * at a name is removed, never followed.
     */
    static void deleteOldModuleFiles(Context context) {
        try {
            File dir = context.createDeviceProtectedStorageContext().getDataDir();
            for (String name : OLD_MODULE_FILES) {
                File f = new File(dir, name);
                if (!f.isDirectory()) f.delete();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "old module files: " + e);
        }
    }

    /**
     * The running module: this boot's hypergestures_module_version, else its log copy's latest
     * boot line (runningModule). Reads files: not on the main thread.
     */
    public static RunningModule runningModule(Context context) {
        deleteOldModuleFiles(context);
        return runningModule(moduleValue(context, MODULE_VERSION_KEY), bootId(),
                moduleVersionFromLog(context));
    }

    /**
     * The module version installed for the next boot when it is not {@code running}, else null;
     * also null when the module is not running this boot (its values are an earlier boot's).
     */
    public static ModuleVersion moduleUpdate(Context context, RunningModule running) {
        if (!running.running() && running.source != FROM_NONE) return null;
        return pendingUpdate(running.version,
                parseModuleVersion(moduleValue(context, MODULE_UPDATE_KEY)));
    }

    /** info.txt's module line: the version and where it came from. */
    static String describe(RunningModule running) {
        if (running.version == null) return "unknown";
        String v = running.version.display();
        switch (running.source) {
            case FROM_LOG: return v + " (from the log: no version setting)";
            case FROM_EARLIER_BOOT: return "not running this boot (last: " + v
                    + ", an earlier boot)";
            default: return v;
        }
    }

    /**
     * The module version from its log copy's latest boot line (in its last VERSION_TAIL_BYTES),
     * or null if none is readable.
     */
    static String moduleVersionFromLog(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        File log = new File(dir, MODULE_LOG);
        if (!log.canRead()) return null;
        try (RandomAccessFile f = new RandomAccessFile(log, "r")) {
            long start = Math.max(0, f.length() - VERSION_TAIL_BYTES);
            byte[] tail = new byte[(int) (f.length() - start)];
            f.seek(start);
            f.readFully(tail);
            return moduleVersionIn(new String(tail, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "module log unreadable: " + e);
            return null;
        }
    }

    /**
     * Collects everything into a new zip in Downloads. Background thread only (it waits for
     * logcat and dumpsys). Returns the zip's file name.
     */
    public static String save(Context context, boolean filtered) throws IOException {
        File tmp = new File(context.getCacheDir(), "hg-logs");
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("no cache dir");
        File logcat = new File(tmp, "logcat.txt");
        List<String[]> dumpCommands = new ArrayList<>();
        dumpCommands.add(DUMPSYS_OURS);
        if (!filtered) dumpCommands.addAll(Arrays.asList(DUMPSYS_SYSTEM));
        List<File> dumps = new ArrayList<>();
        List<String> status = new ArrayList<>();
        try {
            status.add(run(LOGCAT, logcat, LOGCAT_TIMEOUT_S));
            for (int i = 0; i < dumpCommands.size(); i++) {
                File out = new File(tmp, "dumpsys-" + i + ".txt");
                dumps.add(out);
                status.add(run(dumpCommands.get(i), out, DUMPSYS_TIMEOUT_S));
            }
            Set<Integer> pids = ourPids(context);
            String name = zipName(System.currentTimeMillis());
            writeZip(context, name, filtered, pids, logcat, dumps, status);
            Log.i(TAG, "logs saved: " + name + (filtered ? " (filtered)" : ""));
            return name;
        } finally {
            logcat.delete();
            for (File f : dumps) f.delete();
        }
    }

    /** Runs {@code command} with its output (and errors) in {@code out}; returns its status. */
    private static String run(String[] command, File out, long timeoutS) {
        String what = String.join(" ", command);
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(out).start();
            p.getOutputStream().close();
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                return what + ": timed out after " + timeoutS + " s (output so far kept)";
            }
            return what + ": exit " + p.exitValue();
        } catch (IOException e) {
            return what + ": failed: " + e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return what + ": interrupted";
        } finally {
            if (p != null) p.destroyForcibly();
        }
    }

    /** The pids of our own processes (this one and any other of our uid). */
    private static Set<Integer> ourPids(Context context) {
        Set<Integer> pids = new HashSet<>();
        pids.add(Process.myPid());
        try {
            List<ActivityManager.RunningAppProcessInfo> procs =
                    context.getSystemService(ActivityManager.class).getRunningAppProcesses();
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo info : procs) {
                    if (info.uid == Process.myUid()) pids.add(info.pid);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "running processes unreadable: " + e);
        }
        return pids;
    }

    private static void writeZip(Context context, String name, boolean filtered,
            Set<Integer> pids, File logcat, List<File> dumps, List<String> status)
            throws IOException {
        ContentResolver cr = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("Downloads refused the file");
        boolean done = false;
        try {
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new IOException("Downloads gave no stream");
                ZipOutputStream zip = new ZipOutputStream(os);
                Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);

                zip.putNextEntry(new ZipEntry("logcat.txt"));
                writeLogcat(w, logcat, filtered ? new HgLogFilter(pids) : null);
                w.flush();
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry("dumpsys.txt"));
                for (int i = 0; i < dumps.size(); i++) {
                    w.write("==== " + status.get(i + 1) + " ====\n");
                    w.flush();
                    copy(dumps.get(i), zip);
                    w.write("\n");
                }
                w.flush();
                zip.closeEntry();

                List<String> problems = new ArrayList<>();
                File dir = context.getExternalFilesDir(null);
                for (String log : new String[] {MODULE_LOG, MODULE_LOG_OLD}) {
                    File f = dir == null ? null : new File(dir, log);
                    if (f == null || !f.isFile()) continue;
                    zip.putNextEntry(new ZipEntry(log));
                    try {
                        copy(f, zip);
                    } catch (IOException e) {
                        problems.add(log + " unreadable (the copy may be cut short): " + e);
                    }
                    zip.closeEntry();
                }

                zip.putNextEntry(new ZipEntry("info.txt"));
                w.write(info(context, filtered, pids, status, problems));
                w.flush();
                zip.closeEntry();
                zip.finish();
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, values, null, null);
            done = true;
        } finally {
            if (!done) {
                try {
                    cr.delete(uri, null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "cannot delete the partial zip: " + e);
                }
            }
        }
    }

    private static void writeLogcat(Writer w, File logcat, HgLogFilter filter)
            throws IOException {
        if (filter != null) w.write("# filtered: recents and gestures only (HgLogFilter)\n");
        if (!logcat.isFile()) return;
        if (filter != null) {
            // First pass: which pids' crash lines are kept (HgLogFilter.scan).
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new FileInputStream(logcat), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) filter.scan(line);
            }
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(logcat), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (filter == null || filter.keep(line)) {
                    w.write(line);
                    w.write('\n');
                }
            }
        }
    }

    private static void copy(File from, OutputStream to) throws IOException {
        if (!from.isFile()) return;
        try (InputStream in = new FileInputStream(from)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) to.write(buf, 0, n);
        }
    }

    private static String info(Context context, boolean filtered, Set<Integer> pids,
            List<String> status, List<String> problems) {
        StringBuilder sb = new StringBuilder();
        sb.append("HyperGestures logs, saved ").append(new Date()).append('\n');
        sb.append("logcat: ").append(filtered ? "recents and gestures only" : "everything")
                .append(filtered ? "; dumpsys: ours only"
                        : "; dumpsys: ours, recents, activities").append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), Android ")
                .append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
                .append(")\n");
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            sb.append("app: ").append(pi.versionName).append(" (")
                    .append(pi.getLongVersionCode()).append(")\n");
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            sb.append("app: unknown (").append(e).append(")\n");
        }
        sb.append("module: ").append(describe(runningModule(context))).append('\n');
        // This boot's id, for a report where About and the module disagree: the module's values
        // (version, update, status) are listed as they are under Settings.Global below, each
        // with the boot id it was written for.
        sb.append("boot id: ").append(String.valueOf(bootId())).append('\n');
        sb.append("HOME: ").append(home(context)).append('\n');
        sb.append("our pids: ").append(pids).append('\n');

        ContentResolver cr = context.getContentResolver();
        sb.append("\nSettings.Global:\n");
        TreeMap<String, String> global = new TreeMap<>();
        for (String key : GLOBAL_KEYS) global.put(key, read(cr, Table.GLOBAL, key));
        global.putAll(hgGlobals(cr)); // any other hypergestures_* key that is set
        for (String key : SYSTEM_GLOBAL_KEYS) global.put(key, read(cr, Table.GLOBAL, key));
        appendAll(sb, global);
        sb.append("Settings.Secure:\n");
        TreeMap<String, String> secure = new TreeMap<>();
        for (String key : SECURE_KEYS) secure.put(key, read(cr, Table.SECURE, key));
        appendAll(sb, secure);
        sb.append("Settings.System:\n");
        TreeMap<String, String> system = new TreeMap<>();
        for (String key : SYSTEM_KEYS) system.put(key, read(cr, Table.SYSTEM, key));
        appendAll(sb, system);

        sb.append("\ncommands:\n");
        for (String s : status) sb.append("  ").append(s).append('\n');
        for (String s : problems) sb.append(s).append('\n');
        return sb.toString();
    }

    private static void appendAll(StringBuilder sb, TreeMap<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ")
                    .append(e.getValue() == null ? "(unset)" : e.getValue()).append('\n');
        }
    }

    private enum Table { GLOBAL, SECURE, SYSTEM }

    private static String read(ContentResolver cr, Table table, String key) {
        try {
            switch (table) {
                case GLOBAL: return Settings.Global.getString(cr, key);
                case SECURE: return Settings.Secure.getString(cr, key);
                default: return Settings.System.getString(cr, key);
            }
        } catch (RuntimeException e) {
            return "unreadable: " + e;
        }
    }

    /** Every Settings.Global hypergestures_* key that is set. */
    private static TreeMap<String, String> hgGlobals(ContentResolver cr) {
        TreeMap<String, String> found = new TreeMap<>();
        String[] projection = {Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE};
        try (Cursor c = cr.query(Settings.Global.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(0);
                if (name != null && name.startsWith("hypergestures_")) {
                    found.put(name, c.getString(1));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot list Settings.Global: " + e);
        }
        return found;
    }

    /** The default HOME app's package (HgHome), or why there is none. */
    private static String home(Context context) {
        String home = HgHome.packageName(context);
        return home != null ? home : "none (no default home app, or the setup wizard)";
    }
}
