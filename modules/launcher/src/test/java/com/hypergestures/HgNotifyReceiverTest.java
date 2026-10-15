package com.hypergestures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** The module's hg_notify and HgNotifyReceiver agree on the component, action and result code. */
public class HgNotifyReceiverTest {
    // Gradle runs unit tests in the module directory (modules/launcher).
    private static String functionsSh() throws IOException {
        Path p = Paths.get("../../module/template/common/functions.sh");
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void moduleTargetsThisReceiver() throws IOException {
        String sh = functionsSh();
        assertTrue(sh.contains("\nHG_NOTIFY_RECEIVER=$HG_PKG/" + HgNotifyReceiver.class.getName() + " "));
        assertTrue(sh.contains("\nHG_NOTIFY_ACTION=" + HgNotifyReceiver.ACTION + "\n"));
        assertTrue(sh.contains("/^Broadcast completed: result=" + HgNotifyReceiver.RESULT_POSTED + "(,|$)/"));
        // The tag is hg_notify's second argument; its default is the receiver's default tag.
        assertTrue(sh.contains("--es tag \"${2:-" + HgNotifyReceiver.DEFAULT_TAG + "}\" "));
        assertTrue(sh.contains("_hg_ntag=\"${2:-" + HgNotifyReceiver.DEFAULT_TAG + "}\""));
    }

    @Test public void emptyExtrasFallBackToDefaults() {
        assertEquals("HyperGestures", HgNotifyReceiver.orDefault(null, HgNotifyReceiver.DEFAULT_TITLE));
        assertEquals("HyperGestures", HgNotifyReceiver.orDefault("", HgNotifyReceiver.DEFAULT_TITLE));
        assertEquals("x", HgNotifyReceiver.orDefault("x", HgNotifyReceiver.DEFAULT_TITLE));
    }
}
