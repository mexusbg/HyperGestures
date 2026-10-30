// HyperGestures: the settings page's cards (HgSettingsFragment's adapter). The rows of one section
// sit on one rounded card under its header, as in HyperOS's settings: each row draws its own part
// of the card as its background, rounded where the card begins and ends. A row joins the card of
// the row above it when both belong to the same section; a header (a PreferenceCategory) or a row
// of another group (the status card at the top) starts a new one.
package com.android.quickstep;

final class HgSettingsCards {
    private HgSettingsCards() {}

    /**
     * Whether a row continues the card of the item above it.
     *
     * @param aboveIsRow the item above is a row (not a header, and there is one)
     * @param aboveParent the group of the item above
     * @param parent this row's group
     */
    static boolean joins(boolean aboveIsRow, Object aboveParent, Object parent) {
        return aboveIsRow && aboveParent == parent;
    }

    /**
     * A row's corner radii, as GradientDrawable.setCornerRadii takes them (top left, top right,
     * bottom right, bottom left; x and y each): {@code outer} where the card begins or ends,
     * {@code inner} where it goes on to the next row.
     */
    static float[] radii(boolean joinedAbove, boolean joinedBelow, float outer, float inner) {
        float top = joinedAbove ? inner : outer;
        float bottom = joinedBelow ? inner : outer;
        return new float[] {top, top, top, top, bottom, bottom, bottom, bottom};
    }
}
