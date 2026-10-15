package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.quickstep.interaction.HgBackPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** Names the module's shell and the app both spell, pinned so one side cannot drift alone. */
public class HgModuleNamesTest {
    // Gradle runs unit tests in the module directory (modules/launcher).
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replace("\r", "");
    }

    private static String functionsSh() throws IOException {
        return read("../../module/template/common/functions.sh");
    }

    /** The module writes these Settings.Global keys; the app reads the same names. */
    @Test public void moduleWritesTheKeysTheAppReads() throws IOException {
        String sh = functionsSh();
        assertTrue(sh.contains("_hg_pvk=" + HgLogCollector.MODULE_UPDATE_KEY + ";"));
        assertTrue(sh.contains("_hg_pvk=" + HgLogCollector.MODULE_VERSION_KEY + ";"));
        assertTrue(sh.contains("hg_global_put " + HgModuleStatus.KEY + " "));
        assertTrue(sh.contains("hg_global_del " + HgLogCollector.MODULE_UPDATE_KEY + "\n"));
        // customize.sh installs through hg_publish_version's update mode.
        String cz = read("../../module/template/customize.sh");
        assertTrue(cz.contains("hg_publish_version \"$MODPATH\" update"));
        assertTrue(cz.contains(HgLogCollector.MODULE_UPDATE_KEY));
    }

    /** The version line the module logs and the boot marker the app parses. */
    @Test public void versionLogLine() throws IOException {
        assertTrue(functionsSh().contains(
                "hg_log \"" + HgLogCollector.MODULE_VERSION_MARK + "$_hg_vv ($_hg_vvc)\""));
        assertTrue(functionsSh().contains(" boot=$_hg_pvb\""));
    }

    /** The module's log copy and its rotated name are the files the app reads. */
    @Test public void logMirrorFiles() throws IOException {
        String sh = functionsSh();
        assertTrue(sh.contains("/files/" + HgLogCollector.MODULE_LOG + "}\""));
        // getExternalFilesDir(null) of our package for user 0, where su as our uid writes it.
        assertTrue(sh.contains(": \"${HG_LOG_MIRROR:=/sdcard/Android/data/"
                + "com.hypergestures/files/" + HgLogCollector.MODULE_LOG + "}\""));
        assertEquals("module-log.old.txt", HgLogCollector.MODULE_LOG_OLD);
        // hg_mirror_sync: "${HG_LOG_MIRROR%.txt}.old.txt" of module-log.txt.
        assertTrue(sh.contains("\"${HG_LOG_MIRROR%.txt}.old.txt\""));
        assertEquals(HgLogCollector.MODULE_LOG.replace(".txt", ".old.txt"),
                HgLogCollector.MODULE_LOG_OLD);
    }

    /** The crash watch ignores exactly the page's own process, declared in the manifest. */
    @Test public void crashIgnoredIsTheSettingsProcess() throws IOException {
        assertTrue(functionsSh().contains("\nHG_CRASH_IGNORED=\"$HG_PKG:settings\"\n"));
        String m = read("../../hg/launcher/src/main/AndroidManifest.xml");
        int a = m.indexOf("android:name=\"com.android.quickstep.HgSettingsActivity\"");
        assertTrue(a > 0);
        String tag = m.substring(a, m.indexOf(">", a));
        assertTrue(tag.contains("android:process=\":settings\""));
    }

    /**
     * The App info trampoline runs in that same ignored process, and its filter outranks AOSP
     * Settings' (android:priority="1" on InstalledAppDetails).
     */
    @Test public void appInfoTrampolineIsInTheIgnoredProcess() throws IOException {
        String m = read("../../hg/launcher/src/main/AndroidManifest.xml");
        int a = m.indexOf("android:name=\"com.android.quickstep.HgAppInfoTrampoline\"");
        assertTrue(a > 0);
        String tag = m.substring(a, m.indexOf(">", a));
        assertTrue(tag.contains("android:process=\":settings\""));
        assertTrue(tag.contains("android:theme=\"@android:style/Theme.NoDisplay\""));
        String body = m.substring(a, m.indexOf("</activity>", a));
        assertTrue(body.contains("<intent-filter android:priority=\"100\">"));
        assertTrue(body.contains(
                "<action android:name=\"android.settings.APPLICATION_DETAILS_SETTINGS\" />"));
        assertTrue(body.contains("<data android:scheme=\"package\" />"));
    }

    /** The module disables the overlay the app fabricates, by its name. */
    @Test public void hideLineOverlayName() throws IOException {
        assertTrue(functionsSh().contains(
                "\nHG_HIDE_LINE=$HG_PKG:" + HgHideGestureLine.OVERLAY_NAME + " "));
    }

    /** The module's HOME notice and the app's instant one say the same thing. */
    @Test public void homeNoticeText() throws IOException {
        String sh = functionsSh();
        assertTrue(sh.contains("_hg_to=MiuiHome\n"));
        assertTrue(sh.contains("hg_notify \"HyperGestures: reboot to switch gestures to $_hg_to\""));
        assertEquals("HyperGestures: reboot to switch gestures to MiuiHome", HgHomeSwitch.TEXT);
    }

    /** The key the owner sets over adb, listed once for the consumer and the log info. */
    @Test public void backKey() {
        assertEquals("hypergestures_back", HgBackPolicy.BACK_PATH_SETTING);
    }
}
