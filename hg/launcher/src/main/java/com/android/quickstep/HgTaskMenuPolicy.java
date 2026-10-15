// HyperGestures: which entries the recents card menu offers, and in which order, extracted so it
// is testable.
//
// The owner wants exactly HyperOS (MiuiHome) recents' menu: App info, Lock/Unlock, Split screen,
// Floating window, Close. AOSP's other entries (Pin = screen pinning, Install, Freeform/Desktop,
// external display, aspect ratio, wellbeing, Save app pair, Select, Screenshot) are not offered.
// The first four are per-task factories (TaskOverlayFactory's list, Modules.kt); Close is AOSP's
// REMOVE_TASK, which TaskViewShortFactory always appends after them.
package com.android.quickstep;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class HgTaskMenuPolicy {
    private HgTaskMenuPolicy() {}

    public enum Entry { APP_INFO, LOCK, SPLIT_SCREEN, FLOATING_WINDOW, CLOSE }

    /** The per-task entries, in menu order (TaskOverlayFactory's factory list). */
    public static final List<Entry> PER_TASK = Collections.unmodifiableList(Arrays.asList(
            Entry.APP_INFO, Entry.LOCK, Entry.SPLIT_SCREEN, Entry.FLOATING_WINDOW));

    /** The whole menu, in order: the per-task entries, then Close (TaskViewShortFactory). */
    public static final List<Entry> MENU = Collections.unmodifiableList(Arrays.asList(
            Entry.APP_INFO, Entry.LOCK, Entry.SPLIT_SCREEN, Entry.FLOATING_WINDOW, Entry.CLOSE));

    /**
     * Floating window is offered for a single-task card whose app HyperOS can open in a floating
     * window (MiuiMultiWindowUtils.supportFreeform() and packageSupportFreeform(pkg)).
     */
    public static boolean showFloatingWindow(boolean systemSupportsFloating,
            boolean appSupportsFloating, boolean groupedOrDesktopCard) {
        return systemSupportsFloating && appSupportsFloating && !groupedOrDesktopCard;
    }

    /**
     * Split screen is offered only when another card can be the second app: AOSP's split picks
     * the second app from recents, and with no candidate it does nothing (owner, 2026-10-07).
     *
     * @param otherSplittableCards single-app cards other than this one whose app can be split
     */
    public static boolean showSplitScreen(int otherSplittableCards) {
        return otherSplittableCards > 0;
    }
}
