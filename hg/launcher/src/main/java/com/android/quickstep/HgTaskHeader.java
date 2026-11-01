// HyperGestures: the app's icon and name above its recents card, as in HyperOS's recents, in place
// of AOSP's app chip menu (owner, 2026-10-07). The chip (IconAppChipView) keeps its icon and title
// but loses its background, shadow and dropdown arrow, sits just above the card, and opens no menu:
// holding the card brings up the round action buttons instead (HgTaskActionsView). A tap on the
// header opens the app's App info (owner, 2026-10-09); the header sits above the card, outside the
// card's touch area, so RecentsView routes the tap here (containerAt / openAppInfo). The title is
// white with a soft shadow, readable over the blurred background. Off:
// Settings.Global hypergestures_recents_header=0 (AOSP's chip and menu), read as cards inflate;
// each card keeps that snapshot (IconAppChipView.isHgHeader) for its taps and menu.
package com.android.quickstep;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.popup.SystemShortcut;
import com.android.quickstep.views.IconAppChipView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;

import java.util.List;

public final class HgTaskHeader {
    private HgTaskHeader() {}

    /** Gap between the header and the card's top edge, in dp. */
    static final float GAP_DP = 8;
    /** The app icon, as HyperOS's recents header (AOSP's chip: 24dp). */
    static final float ICON_DP = 30;
    /** Between the icon and the name. */
    static final float TITLE_GAP_DP = 12;
    /** The name's size, in sp (AOSP's chip: 14sp medium). */
    static final float TITLE_SP = 16;
    /** The header's width; longer names end in an ellipsis. Less in a split (the chip's maxWidth). */
    static final float MAX_WIDTH_DP = 260;
    /** The icon's inset from the card's start edge. */
    static final float EDGE_INSET_DP = 4;

    /**
     * The header replaces the chip's menu, so it needs the hold buttons for the card's actions:
     * with hypergestures_card_actions=0 the AOSP chip and its menu come back.
     */
    public static boolean isOn(Context context) {
        return HgRecentsSettings.isOn(context, HgRecentsSettings.HEADER)
                && HgRecentsSettings.isOn(context, HgRecentsSettings.CARD_ACTIONS);
    }

    /**
     * The chip without its background, shadow, arrow or clip; a white, shadowed name in HyperOS's
     * size and weight, ellipsized when it is longer than the header.
     */
    public static void style(View chip, @Nullable View arrow, @Nullable TextView title) {
        chip.setBackground(null);
        chip.setForeground(null);
        chip.setElevation(0f);
        chip.setClipToOutline(false);
        chip.setClickable(false);
        chip.setLongClickable(false);
        if (arrow != null) arrow.setVisibility(View.GONE);
        if (title != null) {
            float density = chip.getResources().getDisplayMetrics().density;
            title.setTextColor(Color.WHITE);
            title.setShadowLayer(4 * density, 0f, 1 * density, 0x55000000);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP);
            title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            title.setLetterSpacing(0f);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setHorizontalFadingEdgeEnabled(false);
            title.setHorizontallyScrolling(false);
        }
    }

    public static int iconSizePx(Resources res) {
        return Math.round(ICON_DP * res.getDisplayMetrics().density);
    }

    public static int titleGapPx(Resources res) {
        return Math.round(TITLE_GAP_DP * res.getDisplayMetrics().density);
    }

    /**
     * The header's height above the card, px: the chip's visible row (AOSP's collapsed chip
     * height, the icon centred in it) and GAP_DP to the card (lift). The vertical layout leaves
     * this room above each row (HgVerticalLayout), as the horizontal card has above it.
     */
    public static float heightPx(Resources res) {
        return res.getDimensionPixelSize(R.dimen.task_thumbnail_icon_menu_collapsed_height)
                + GAP_DP * res.getDisplayMetrics().density;
    }

    public static int maxWidthPx(Resources res) {
        return Math.round(MAX_WIDTH_DP * res.getDisplayMetrics().density);
    }

    /**
     * The header's width on a {@code cardWidth} wide card it must stay on (the vertical layout's
     * two columns, HgVerticalLayout): from its inset start edge to the card's end at most.
     */
    public static int maxWidthOnCard(Resources res, int cardWidth) {
        int inset = Math.round(EDGE_INSET_DP * res.getDisplayMetrics().density);
        return Math.max(0, Math.min(maxWidthPx(res), cardWidth - inset));
    }

    /**
     * How far to move the header towards the card's start edge so its icon, {@code iconStart} px
     * in from the card's start, sits EDGE_INSET_DP in (negative: towards the start, LTR).
     */
    public static float shift(Resources res, int iconStart) {
        return -(iconStart - EDGE_INSET_DP * res.getDisplayMetrics().density);
    }

    /**
     * How far to lift the chip so its visible row ({@code collapsedBottom} px tall from the chip's
     * top, which sits {@code chipTopInCard} px below the card's top) ends GAP_DP above the card.
     * Portrait displays only: in landscape there is no room above the card (status bar).
     */
    public static float lift(View chip, int collapsedBottom, int chipTopInCard) {
        if (chip.getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            return 0f;
        }
        return -(collapsedBottom + chipTopInCard
                + GAP_DP * chip.getResources().getDisplayMetrics().density);
    }

    /** The header's touch room above a card, in dp. */
    static final float TOUCH_ROOM_DP = 64;

    /**
     * Header room above the cards, in px at scale 1: kept out of RecentsView's tap-to-go-home.
     * {@code headerOn}: the cards' own snapshot (TaskView.hgHasHeader), not the live setting.
     */
    public static int touchRoom(Resources res, boolean headerOn) {
        return headerOn ? Math.round(TOUCH_ROOM_DP * res.getDisplayMetrics().density) : 0;
    }

    /**
     * The card whose header is under ({@code contentX}, {@code y}) in {@code rv}'s content
     * coordinates (view x plus scroll), or null. Only lifted headers count; a header still on its
     * card (landscape, a split card's second app) gets its touches through the card. Each chip's
     * own header snapshot decides (IconAppChipView.isHgHeader), not the setting, which is not
     * read again on every touch.
     */
    @Nullable
    public static TaskContainer containerAt(RecentsView<?, ?> rv, float contentX, float y) {
        float[] corner = null;
        for (TaskView taskView : rv.getTaskViews()) {
            if (taskView.getVisibility() != View.VISIBLE || taskView.getAlpha() == 0f) continue;
            for (TaskContainer container : taskView.getTaskContainers()) {
                IconAppChipView chip = container.getIconView();
                if (!takesTap(chip.isHgHeader(), chip.getVisibility() == View.VISIBLE,
                        chip.getTranslationY())) {
                    continue;
                }
                if (corner == null) corner = new float[2];
                corner[0] = 0f;
                corner[1] = 0f;
                Utilities.getDescendantCoordRelativeToAncestor(chip, rv, corner, false);
                float left = corner[0];
                float top = corner[1];
                corner[0] = chip.getWidth();
                corner[1] = chip.getHeight();
                Utilities.getDescendantCoordRelativeToAncestor(chip, rv, corner, false);
                if (contentX >= Math.min(left, corner[0]) && contentX <= Math.max(left, corner[0])
                        && y >= Math.min(top, corner[1]) && y <= Math.max(top, corner[1])
                        && withinHeaderClip(rv, taskView, contentX, corner)) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * Whether a card's chip takes a tap as its header: a header (the chip's snapshot), shown, and
     * lifted above the card (a header still on its card gets its touches through the card).
     */
    static boolean takesTap(boolean isHeader, boolean visible, float translationY) {
        return isHeader && visible && translationY < 0f;
    }

    /**
     * Whether {@code contentX} is inside the x range the card's header draws in: the stack layout
     * clips an older card's header at the newer card on top of it (TaskView.hgSetHeaderClip), so
     * a tap there is not on this header. Cards are checked newest first, as they are drawn.
     */
    private static boolean withinHeaderClip(RecentsView<?, ?> rv, TaskView taskView,
            float contentX, float[] tmp) {
        float start = taskView.getHgHeaderClipStart();
        float end = taskView.getHgHeaderClipEnd();
        if (start <= -TaskView.HG_NO_CLIP && end >= TaskView.HG_NO_CLIP) return true;
        boolean inside = true;
        if (start > -TaskView.HG_NO_CLIP) {
            tmp[0] = start;
            tmp[1] = 0f;
            Utilities.getDescendantCoordRelativeToAncestor(taskView, rv, tmp, false);
            inside = contentX >= tmp[0];
        }
        if (end < TaskView.HG_NO_CLIP) {
            tmp[0] = end;
            tmp[1] = 0f;
            Utilities.getDescendantCoordRelativeToAncestor(taskView, rv, tmp, false);
            inside &= contentX <= tmp[0];
        }
        return inside;
    }

    /** Opens App info for {@code container}'s app (AOSP's card menu item). */
    public static void openAppInfo(TaskContainer container) {
        TaskView taskView = container.getTaskView();
        if (HgAppInfo.open(taskView.getContext(), container)) return;
        try {
            RecentsViewContainer recents =
                    RecentsViewContainer.containerFromContext(taskView.getContext());
            List<SystemShortcut> shortcuts =
                    TaskShortcutFactory.APP_INFO.getShortcuts(recents, container);
            if (shortcuts != null && !shortcuts.isEmpty()) {
                shortcuts.get(0).onClick(container.getIconView());
            }
        } catch (RuntimeException e) {
            android.util.Log.w("HgTaskHeader", "App info failed", e);
        }
    }
}
