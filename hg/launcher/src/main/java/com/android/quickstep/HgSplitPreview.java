// HyperGestures: the cards of apps in, or just out of, HyperOS's split screen.
//
// Until such an app draws full screen again, HyperOS only has a half-screen snapshot of it
// (recorded as full-screen once it left split). Filled into a card it showed about 2x larger than
// every other card; centred, it left half the card empty: neither matches a full-screen app (owner,
// 2026-11-03). Its card shows the app's icon on its colour instead, AOSP's splash for a snapshot
// that does not fit (TaskView), until a full-screen snapshot exists.
//
// An app left alone in a split is pushed up to a strip at the top with home under it (HyperOS's
// minimized split). Its live tile is the same half-screen window; once recents is up, the card
// switches to a snapshot so it shows the icon card too (AbsSwipeUpHandler).
package com.android.quickstep;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import com.android.systemui.shared.recents.utilities.PreviewPositionHelper;
import com.android.systemui.shared.recents.utilities.Utilities;

public final class HgSplitPreview {
    private HgSplitPreview() {}

    /**
     * Whether a card shows the icon card instead of its snapshot: a single-task card whose
     * snapshot ({@code thumbWidth} x {@code thumbHeight}, any resolution) is far from the card's
     * shape, in the same orientation (else it is AOSP's rotation case); not a floating window.
     */
    public static boolean iconCard(int windowingMode, boolean singleCard, float thumbWidth,
            float thumbHeight, int cardWidth, int cardHeight) {
        if (!singleCard || windowingMode == WINDOWING_MODE_FREEFORM) return false;
        if (cardWidth <= 0 || cardHeight <= 0 || thumbWidth <= 0 || thumbHeight <= 0) return false;
        if ((thumbWidth > thumbHeight) != (cardWidth > cardHeight)) return false;
        return Utilities.isRelativePercentDifferenceGreaterThan(cardWidth / (float) cardHeight,
                thumbWidth / thumbHeight,
                PreviewPositionHelper.MAX_PCT_BEFORE_ASPECT_RATIOS_CONSIDERED_DIFFERENT);
    }

    /**
     * Whether a screenshot ({@code thumbWidth} x {@code thumbHeight}, any resolution) shows the app
     * full screen: the {@code displayWidth} x {@code displayHeight} screen's shape, either way up;
     * not taken in split or as a floating window. HgPreSplitThumbnails keeps those.
     */
    public static boolean fullScreenShape(int windowingMode, float thumbWidth, float thumbHeight,
            int displayWidth, int displayHeight) {
        if (windowingMode == WINDOWING_MODE_MULTI_WINDOW
                || windowingMode == WINDOWING_MODE_FREEFORM) {
            return false;
        }
        if (thumbWidth <= 0 || thumbHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return false;
        }
        float thumb = Math.min(thumbWidth, thumbHeight) / Math.max(thumbWidth, thumbHeight);
        float display = Math.min(displayWidth, displayHeight)
                / (float) Math.max(displayWidth, displayHeight);
        return !Utilities.isRelativePercentDifferenceGreaterThan(display, thumb,
                PreviewPositionHelper.MAX_PCT_BEFORE_ASPECT_RATIOS_CONSIDERED_DIFFERENT);
    }

    /**
     * Whether an icon card (see iconCard; same arguments) shows the app's last full-screen
     * screenshot ({@code preWidth} x {@code preHeight}; 0 if none) instead: the option is on
     * ({@code on}), the app is not locked by App lock ({@code appLocked}), and that screenshot
     * fits the card (the card's orientation and shape).
     */
    public static boolean showsPreSplit(boolean on, boolean appLocked, int windowingMode,
            boolean singleCard, float thumbWidth, float thumbHeight, float preWidth,
            float preHeight, int cardWidth, int cardHeight) {
        if (!on || appLocked || preWidth <= 0 || preHeight <= 0) return false;
        if (!iconCard(windowingMode, singleCard, thumbWidth, thumbHeight, cardWidth, cardHeight)) {
            return false;
        }
        if ((preWidth > preHeight) != (cardWidth > cardHeight)) return false;
        return !Utilities.isRelativePercentDifferenceGreaterThan(cardWidth / (float) cardHeight,
                preWidth / preHeight,
                PreviewPositionHelper.MAX_PCT_BEFORE_ASPECT_RATIOS_CONSIDERED_DIFFERENT);
    }

    /**
     * Whether the running app is in HyperOS's minimized split: in split, its window pushed partly
     * off the {@code displayWidth} x {@code displayHeight} screen.
     */
    public static boolean minimizedSplit(int windowingMode, int left, int top, int right,
            int bottom, int displayWidth, int displayHeight) {
        return windowingMode == WINDOWING_MODE_MULTI_WINDOW
                && (left < 0 || top < 0 || right > displayWidth || bottom > displayHeight);
    }
}
