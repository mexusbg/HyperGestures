package com.android.quickstep;

import static org.junit.Assert.assertSame;

import org.junit.Test;

public class HgSwipeDownControllerTest {
    /** The switch on (unset or not "0") locks; off launches, as AOSP. */
    @Test public void choosesByTheSwitch() {
        Object lock = new Object();
        Object launch = new Object();
        assertSame(lock, HgSwipeDownController.choose(true, lock, launch));
        assertSame(launch, HgSwipeDownController.choose(false, lock, launch));
        assertSame(lock, HgSwipeDownController.choose(
                HgRecentsSettings.isOn((String) null), lock, launch));
        assertSame(launch, HgSwipeDownController.choose(
                HgRecentsSettings.isOn("0"), lock, launch));
    }
}
