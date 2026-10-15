package com.android.quickstep;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HgRamIndicatorTest {
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    private static final String LINE = "%1$s of %2$s available";
    private static final String MEGA = "%1$s MB";
    private static final String GIGA = "%1$s GB";

    /** HyperOS's HardwareInfo: 1 GiB steps up to 4 GiB, 2 GiB up to 8 GiB, then 4 GiB. */
    @Test public void totalRoundsUpToThePhysicalSize() {
        assertEquals(16 * GB, HgRamIndicator.physicalBytes((long) (14.9 * GB)));
        assertEquals(12 * GB, HgRamIndicator.physicalBytes((long) (11.2 * GB)));
        assertEquals(8 * GB, HgRamIndicator.physicalBytes((long) (7.4 * GB)));
        assertEquals(6 * GB, HgRamIndicator.physicalBytes((long) (5.6 * GB)));
        assertEquals(3 * GB, HgRamIndicator.physicalBytes((long) (2.7 * GB)));
        assertEquals(8 * GB, HgRamIndicator.physicalBytes(8 * GB));
    }

    @Test public void formatsLikeHyperOs() {
        assertEquals("5.3 GB of 16.0 GB available",
                HgRamIndicator.format(LINE, MEGA, GIGA, (long) (5.3 * GB), (long) (14.9 * GB)));
    }

    /** Free memory under 1 GB is whole MB. */
    @Test public void freeUnderOneGbIsMegabytes() {
        assertEquals("812 MB of 4.0 GB available",
                HgRamIndicator.format(LINE, MEGA, GIGA, 812 * MB + 300_000, (long) (3.6 * GB)));
        assertEquals("1023 MB", HgRamIndicator.formatMemory(1023 * 1024, false, MEGA, GIGA));
        assertEquals("1.0 GB", HgRamIndicator.formatMemory(1024 * 1024, false, MEGA, GIGA));
    }

    /** Free memory rounds to the nearest tenth; the total always up to a whole GB. */
    @Test public void roundingMatchesMiuiHome() {
        // 6103 MB is 5.96 GB: 59.6 tenths round to 60.
        assertEquals("6.0 GB", HgRamIndicator.formatMemory(6103 * 1024, false, MEGA, GIGA));
        assertEquals("5.9 GB", HgRamIndicator.formatMemory(6083 * 1024, false, MEGA, GIGA));
        assertEquals("15.0 GB", HgRamIndicator.formatMemory(14337 * 1024, true, MEGA, GIGA));
        assertEquals("16.0 GB", HgRamIndicator.formatMemory(16384 * 1024, true, MEGA, GIGA));
    }
}
