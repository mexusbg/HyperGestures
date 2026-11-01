// HyperGestures: the moving miniatures on the settings page's layout picker (HgLayoutPreviewView,
// owner 2026-10-31: "Option A", tiles side by side, plain translucent white cards). One cycle of
// each layout as pure maths, so it is testable: where each card is at a moment t of the cycle
// (0 to 1), in fractions of the preview's width (x, w) and height (y, h), back to front.
// - Vertical: two columns, the newest top-left (as HgVerticalLayout), scrolled up a little and
//   back.
// - Horizontal: one row, paged twice towards older cards and back.
// - Stack: a deck (as HgStackLayout): the front card, older ones smaller behind on the left, the
//   next one sliding in from the right; each card goes through every place once a cycle.
// The values are the page's: "0" vertical, "1" horizontal, "2" stack (HgRecentsSettings).
// Drawn every frame by three views, so frame() fills cards the caller keeps (newCards) instead of
// allocating new ones.
package com.android.quickstep;

import java.util.Arrays;
import java.util.List;

final class HgLayoutPreview {
    static final int VERTICAL = 0;
    static final int HORIZONTAL = 1;
    static final int STACK = 2;

    /** One cycle of every preview. */
    static final long CYCLE_MS = 6000;

    /** One card of a frame; reused from frame to frame (frame(int, float, Card[])). */
    static final class Card {
        float x;
        float y;
        float w;
        float h;
        float alpha;
        /** Draw order (higher on top); a frame is already sorted by it. */
        float z;

        void set(float x, float y, float w, float h, float alpha, float z) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.alpha = alpha;
            this.z = z;
        }
    }

    private static final int VERTICAL_CARDS = 8;
    private static final float V_LEFT = 0.08f;
    private static final float V_COLUMN = 0.46f;
    private static final float V_TOP = 0.09f;
    private static final float V_ROW = 0.36f;
    private static final float V_W = 0.38f;
    private static final float V_H = 0.28f;
    private static final float[] V_TIMES = {0f, 0.15f, 0.45f, 0.65f, 0.95f, 1f};
    private static final float[] V_SCROLL = {0f, 0f, 0.38f, 0.38f, 0f, 0f};

    private static final int HORIZONTAL_CARDS = 4;
    private static final float H_LEFT = 0.19f;
    private static final float H_PAGE = 0.68f;
    private static final float H_TOP = 0.2f;
    private static final float H_W = 0.62f;
    private static final float H_H = 0.56f;
    private static final float[] H_TIMES = {0f, 0.2f, 0.33f, 0.53f, 0.66f, 0.86f, 1f};
    private static final float[] H_PAGES = {0f, 0f, 1f, 1f, 2f, 2f, 0f};

    private static final int STACK_CARDS = 4;
    private static final float S_TOP = 0.2f;
    private static final float S_W = 0.64f;
    private static final float S_H = 0.58f;
    private static final float[] S_TIMES = {0f, 0.2f, 0.25f, 0.45f, 0.5f, 0.7f, 0.75f, 0.95f, 1f};
    private static final float[] S_LEFT =
            {1.1f, 0.8f, 0.8f, 0.22f, 0.22f, 0.08f, 0.08f, 0.02f, 0.02f};
    private static final float[] S_SCALE = {1f, 1f, 1f, 1f, 1f, 0.9f, 0.9f, 0.8f, 0.8f};
    private static final float[] S_ALPHA = {1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f};

    /** The most cards a frame has (vertical's). */
    static final int MAX_CARDS = VERTICAL_CARDS;

    private HgLayoutPreview() {}

    /** The preview for a setting value ("0", "1", "2"); -1 for anything else. */
    static int mode(String value) {
        if ("0".equals(value)) return VERTICAL;
        if ("1".equals(value)) return HORIZONTAL;
        if ("2".equals(value)) return STACK;
        return -1;
    }

    /** The setting value for a preview. */
    static String value(int mode) {
        return Integer.toString(mode);
    }

    /** Cards for frame(int, float, Card[]): MAX_CARDS of them, kept by the caller. */
    static Card[] newCards() {
        Card[] cards = new Card[MAX_CARDS];
        for (int i = 0; i < cards.length; i++) cards[i] = new Card();
        return cards;
    }

    /** The cards of {@code mode} at {@code t} (0 to 1) of the cycle, back to front (tests). */
    static List<Card> frame(int mode, float t) {
        Card[] cards = newCards();
        return Arrays.asList(cards).subList(0, frame(mode, t, cards));
    }

    /**
     * Fills {@code cards} (newCards) with the cards of {@code mode} at {@code t} (0 to 1) of the
     * cycle, back to front, and returns how many there are. Allocates nothing.
     */
    static int frame(int mode, float t, Card[] cards) {
        switch (mode) {
            case VERTICAL: {
                float scroll = keyframes(t, V_TIMES, V_SCROLL);
                for (int k = 0; k < VERTICAL_CARDS; k++) {
                    cards[k].set(V_LEFT + (k % 2) * V_COLUMN,
                            V_TOP + (k / 2) * V_ROW - scroll, V_W, V_H, 1f, 0f);
                }
                return VERTICAL_CARDS;
            }
            case HORIZONTAL: {
                float page = keyframes(t, H_TIMES, H_PAGES);
                for (int k = 0; k < HORIZONTAL_CARDS; k++) {
                    cards[k].set(H_LEFT + (k - page) * H_PAGE, H_TOP, H_W, H_H, 1f, 0f);
                }
                return HORIZONTAL_CARDS;
            }
            default: {
                for (int k = 0; k < STACK_CARDS; k++) {
                    float p = (t + (float) k / STACK_CARDS) % 1f;
                    float scale = keyframes(p, S_TIMES, S_SCALE);
                    float w = S_W * scale;
                    float h = S_H * scale;
                    // Scaled about the card's centre, as TaskView's pivot.
                    float x = keyframes(p, S_TIMES, S_LEFT) + (S_W - w) / 2f;
                    float y = S_TOP + (S_H - h) / 2f;
                    cards[k].set(x, y, w, h, keyframes(p, S_TIMES, S_ALPHA), 1f - p);
                }
                sortByZ(cards, STACK_CARDS);
                return STACK_CARDS;
            }
        }
    }

    /** Insertion sort of the first {@code n} cards by z, in place (a handful; no allocation). */
    private static void sortByZ(Card[] cards, int n) {
        for (int i = 1; i < n; i++) {
            Card c = cards[i];
            int j = i - 1;
            while (j >= 0 && cards[j].z > c.z) {
                cards[j + 1] = cards[j];
                j--;
            }
            cards[j + 1] = c;
        }
    }

    /** {@code values} at {@code t} over {@code times}, eased in and out between keyframes. */
    private static float keyframes(float t, float[] times, float[] values) {
        for (int i = 1; i < times.length; i++) {
            if (t <= times[i]) {
                float span = times[i] - times[i - 1];
                float u = span <= 0 ? 1f : (t - times[i - 1]) / span;
                return values[i - 1] + (values[i] - values[i - 1]) * ease(u);
            }
        }
        return values[values.length - 1];
    }

    /** Cubic ease in and out. */
    private static float ease(float u) {
        if (u < 0.5f) return 4f * u * u * u;
        float v = -2f * u + 2f;
        return 1f - v * v * v / 2f;
    }
}
