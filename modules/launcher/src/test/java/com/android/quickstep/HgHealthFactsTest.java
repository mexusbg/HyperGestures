package com.android.quickstep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** The app's health facts and the module's hg_app_fact agree on names, format and monitor. */
public class HgHealthFactsTest {
    // Gradle runs unit tests in the module directory (modules/launcher).
    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replace("\r", "");
    }

    private static String functionsSh() throws IOException {
        return read("../../module/template/common/functions.sh");
    }

    /** The module reads the keys we write. */
    @Test public void moduleReadsOurKeys() throws IOException {
        String sh = functionsSh();
        assertTrue(sh.contains("\nHG_FACT_BOUND=" + HgHealthFacts.PROVIDER_BOUND + "\n"));
        assertTrue(sh.contains("\nHG_FACT_MONITOR=" + HgHealthFacts.GESTURE_MONITOR + "\n"));
    }

    /**
     * The monitor TouchInteractionHandler registers is the one hg_monitor_in_dump looks for in
     * `dumpsys input` (the dump fallback and cross-check of the gesture-monitor value).
     */
    @Test public void monitorNameMatchesTheDumpCheck() throws IOException {
        String tih = read(
                "../../aosp/launcher3/quickstep/src/com/android/quickstep/"
                        + "TouchInteractionHandler.java");
        assertTrue(tih.contains("new InputMonitorCompat(\"" + HgHealthFacts.MONITOR_NAME
                + "\", displayId)"));
        assertTrue(functionsSh().contains(
                "index($0, \"[Gesture Monitor] " + HgHealthFacts.MONITOR_NAME + "\")"));
    }

    /** "<bootId> <pid> <uptimeMs>": three fields, as hg_app_fact splits them. */
    @Test public void valueFormat() {
        assertEquals("1b2c-77 7450 81234", HgHealthFacts.value("1b2c-77", 7450, 81234));
        assertEquals(3, HgHealthFacts.value("1b2c-77", 7450, 81234).split(" ").length);
    }
}
