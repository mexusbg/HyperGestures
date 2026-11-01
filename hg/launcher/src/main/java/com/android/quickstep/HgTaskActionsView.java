// HyperGestures: hold a recents card for HyperOS's round action buttons (design ruling 4): Lock or
// Unlock, Split screen, Floating window and App info, top to bottom, each where the card offers it
// (the same factories as the card menu, HgTaskShortcuts / TaskShortcutFactory.APP_INFO).
//
// The buttons fan out in an arc that bulges away from the card, on the side where no other card is
// in their way, else the finger's side (buttonsOnLeft), and the card slides toward the other side
// just far enough for them to clear it (TaskView's hgActionsTranslationX). The buttons sit right
// next to the slid card, and any other card that would show under them (the stack's older cards,
// the horizontal row's neighbour) slides out of their way too, as the grid layout's own column
// empties (owner, 0.4.0.5). It is a TYPE_TASK_MENU floating view over the whole drag layer: the
// swipe controllers stay out while it is open, a tap outside the buttons or back closes it, and the tap
// does not reach the cards. Off: Settings.Global hypergestures_card_actions=0.
package com.android.quickstep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.FloatProperty;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.R;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.util.Executors;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.views.GroupedTaskView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.RecentsViewContainer;
import com.android.quickstep.views.TaskContainer;
import com.android.quickstep.views.TaskView;

import java.util.ArrayList;
import java.util.List;

public class HgTaskActionsView extends AbstractFloatingView {
    private static final String TAG = "HgTaskActions";

    static final float BUTTON_DP = 56;
    /** Centre to centre: 28 dp between the 56 dp buttons (owner, 0.4.0.1: more room). */
    static final float SPACING_DP = 84;
    static final float ARC_DP = 22;
    static final float EDGE_MARGIN_DP = 20;
    /** Between the buttons and the slid card's edge (owner, 0.4.0.5: closer to the card). */
    static final float CARD_GAP_DP = 12;
    /** Between the button column and a neighbour card slid out of its way. */
    static final float NEIGHBOUR_PAD_DP = 12;
    static final float MIN_SHIFT_DP = 36;
    /** The held card's translationZ while the buttons are open: over its neighbours. */
    private static final float RAISED_Z = 1f;
    private static final float GLYPH_PADDING_DP = 16;
    private static final long OPEN_MS = 340;
    private static final long CLOSE_MS = 220;
    private static final long STAGGER_MS = 28;
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /**
     * The card's slide aside, on TaskView.hgActionsTranslationX; a live tile follows it from its
     * own offset at open (mLiveTileBase, NaN when there is none to move).
     */
    private final FloatProperty<TaskView> CARD_SHIFT =
            new FloatProperty<TaskView>("hgActionsTranslationX") {
                @Override
                public void setValue(TaskView view, float value) {
                    view.setHgActionsTranslationX(value);
                    if (!Float.isNaN(mLiveTileBase)) {
                        HgLiveTile.setPrimary(view, mLiveTileBase + value);
                    }
                }

                @Override
                public Float get(TaskView view) {
                    return view.getHgActionsTranslationX();
                }
            };
    private float mLiveTileBase = Float.NaN;

    /** Another card slid out of the buttons' way: its slide and its live tile's base, if any. */
    private static final class Neighbour {
        final TaskView view;
        final float shift;
        final float liveTileBase;

        Neighbour(TaskView view, float shift, float liveTileBase) {
            this.view = view;
            this.shift = shift;
            this.liveTileBase = liveTileBase;
        }

        void set(float value) {
            view.setHgActionsTranslationX(value);
            if (!Float.isNaN(liveTileBase)) HgLiveTile.setPrimary(view, liveTileBase + value);
        }
    }

    private final List<Neighbour> mNeighbours = new ArrayList<>();

    /** One button: its glyph, label and what a tap does. */
    private static final class Action {
        final int iconRes;
        final String label;
        final Runnable run;

        Action(int iconRes, String label, Runnable run) {
            this.iconRes = iconRes;
            this.label = label;
            this.run = run;
        }
    }

    private final RecentsViewContainer mContainer;
    private final FrameLayout mButtonLayer;
    private final List<View> mButtons = new ArrayList<>();
    private final float mDensity;
    private TaskView mTaskView;
    private float mShift;
    /**
     * The held card's own translationZ before it was raised over its neighbours (the slide aside
     * can cross the vertical layout's other column), NaN when not raised.
     */
    private float mCardZ = Float.NaN;
    private boolean mButtonsOnLeft;
    @Nullable private Animator mAnimator;
    /**
     * Inside onDetachedFromWindow: the drag layer is detaching its children, so the close
     * animation's end (run by the cancel there) must not remove this view from it re-entrantly.
     */
    private boolean mDetaching;

    private HgTaskActionsView(Context context) {
        super(context, null);
        mContainer = RecentsViewContainer.containerFromContext(context);
        mDensity = getResources().getDisplayMetrics().density;
        mButtonLayer = new FrameLayout(context);
        addView(mButtonLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Long-press on a card: opens the buttons. @param downX where the finger went down, in the
     * card's own coordinates (picks the side). @return whether they opened.
     */
    public static boolean showFor(TaskView taskView, float downX) {
        Context context = taskView.getContext();
        // A card whose chips were inflated as the header (no menu) keeps its buttons, its only
        // menu, even if the buttons were turned off since (TaskView.hgHasHeader).
        if (!taskView.getHgHasHeader()
                && !HgRecentsSettings.isOn(context, HgRecentsSettings.CARD_ACTIONS)) {
            return false;
        }
        RecentsView<?, ?> recentsView = taskView.getRecentsView();
        if (recentsView == null || recentsView.isSplitSelectionActive()) return false;
        RecentsViewContainer container = RecentsViewContainer.containerFromContext(context);
        if (AbstractFloatingView.getTopOpenViewWithType(container, TYPE_TASK_MENU) != null) {
            return false;
        }
        // In the stack layout (HgStackLayout) a held card behind the front one is mostly hidden:
        // as MiuiHome, bring it to the front first and open its buttons once the scroll settles
        // (added after any callback already waiting for the scroll's end, which still runs).
        int page = recentsView.indexOfChild(taskView);
        if (recentsView.isHgStack() && page >= 0 && page != recentsView.getNextPage()) {
            recentsView.snapToPage(page);
            recentsView.addOnPageTransitionEndCallback(() -> {
                if (taskView.getRecentsView() == recentsView
                        && recentsView.indexOfChild(taskView) == page
                        && recentsView.getNextPage() == page
                        && !taskView.isBeingDismissed()) {
                    showFor(taskView, downX);
                }
            });
            return true;
        }
        HgTaskActionsView view = new HgTaskActionsView(context);
        List<Action> actions = view.collectActions(taskView);
        if (actions.isEmpty()) return false;
        return view.open(taskView, actions, downX);
    }

    private List<Action> collectActions(TaskView taskView) {
        List<Action> out = new ArrayList<>();
        List<TaskContainer> containers = taskView.getTaskContainers();
        if (containers.isEmpty()) return out;
        TaskContainer first = containers.get(0);
        boolean grouped = taskView instanceof GroupedTaskView;
        Context context = getContext();

        if (HgTaskLocking.canLock(taskView)) {
            boolean locked = HgTaskLocking.isLocked(context, taskView);
            out.add(new Action(locked ? R.drawable.hg_ic_task_unlock : R.drawable.ic_lock,
                    context.getString(locked ? R.string.hg_task_menu_unlock : R.string.hg_task_menu_lock),
                    () -> {
                        boolean nowLocked = HgTaskLocking.toggle(context, taskView);
                        taskView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                        Log.i(TAG, (nowLocked ? "locked" : "unlocked") + " from the hold buttons");
                        close(true);
                    }));
        }
        addShortcut(out, HgTaskShortcuts.SPLIT_SCREEN, first, grouped);
        addShortcut(out, HgTaskShortcuts.FLOATING_WINDOW, first, grouped);
        addShortcut(out, TaskShortcutFactory.APP_INFO, first, grouped,
                () -> HgAppInfo.open(getContext(), first));
        return out;
    }

    private void addShortcut(List<Action> out, TaskShortcutFactory factory, TaskContainer first,
            boolean grouped) {
        addShortcut(out, factory, first, grouped, null);
    }

    /** {@code preferred}, when set, runs instead of the shortcut and returns false to fall back. */
    private void addShortcut(List<Action> out, TaskShortcutFactory factory, TaskContainer first,
            boolean grouped, @Nullable java.util.function.BooleanSupplier preferred) {
        if (grouped && !factory.showForGroupedTask()) return;
        List<SystemShortcut> shortcuts;
        try {
            shortcuts = factory.getShortcuts(mContainer, first);
        } catch (RuntimeException e) {
            Log.w(TAG, "a card action failed to load", e);
            return;
        }
        if (shortcuts == null || shortcuts.isEmpty()) return;
        SystemShortcut shortcut = shortcuts.get(0);
        out.add(new Action(shortcut.getIconResId(), getContext().getString(shortcut.getLabelResId()),
                () -> {
                    TaskView taskView = mTaskView;
                    // Instantly: the action animates the card or leaves recents itself.
                    close(false);
                    if (preferred != null && preferred.getAsBoolean()) return;
                    shortcut.onClick(taskView);
                }));
    }

    private boolean open(TaskView taskView, List<Action> actions, float downX) {
        BaseDragLayer<?> dragLayer = mContainer.getDragLayer();
        mTaskView = taskView;
        mLiveTileBase = HgLiveTile.primaryBase(taskView);
        mCardZ = taskView.getTranslationZ();
        taskView.setTranslationZ(Math.max(mCardZ, RAISED_Z));
        Rect card = new Rect();
        dragLayer.getDescendantRectRelativeToSelf(taskView, card);
        int layerWidth = dragLayer.getWidth();
        boolean fingerOnLeft = card.left
                + downX * (card.width() / (float) Math.max(1, taskView.getWidth()))
                < layerWidth / 2f;

        float button = BUTTON_DP * mDensity;
        float spacing = SPACING_DP * mDensity;
        float arc = ARC_DP * mDensity;
        float margin = EDGE_MARGIN_DP * mDensity;
        float gap = CARD_GAP_DP * mDensity;
        int n = actions.size();
        float centerY = clampColumnCenter(card.exactCenterY(), n, spacing, button,
                dragLayer.getInsets().top, dragLayer.getHeight() - dragLayer.getInsets().bottom);
        float half = (n - 1) / 2f * spacing + button / 2f;
        float maxBulge = 0f;
        for (int i = 0; i < n; i++) maxBulge = Math.max(maxBulge, arcFraction(i, n));

        // Each side laid out: its card slide and the other cards its column would cover.
        float[] shifts = new float[2];
        List<List<Neighbour>> neighbours = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            boolean left = side == 0;
            shifts[side] = shiftFor(left, card.left, card.right, layerWidth,
                    margin + button + arc + gap, MIN_SHIFT_DP * mDensity);
            float edge = (left ? card.left : card.right) + shifts[side];
            float inner = left ? edge - gap : edge + gap;
            float outer = left ? inner - button - arc * maxBulge : inner + button + arc * maxBulge;
            neighbours.add(neighboursFor(left, taskView, dragLayer, Math.min(inner, outer),
                    Math.max(inner, outer), centerY - half, centerY + half));
        }
        RecentsView<?, ?> recentsView = taskView.getRecentsView();
        mButtonsOnLeft = recentsView != null && recentsView.isHgStack()
                ? stackButtonsOnLeft(taskView == recentsView.getFirstTaskView(),
                        recentsView.isRtl())
                : buttonsOnLeft(fingerOnLeft, neighbours.get(0).isEmpty(),
                        neighbours.get(1).isEmpty());
        int chosen = mButtonsOnLeft ? 0 : 1;
        mShift = shifts[chosen];
        mNeighbours.addAll(neighbours.get(chosen));

        float cardEdge = (mButtonsOnLeft ? card.left : card.right) + mShift;
        for (int i = 0; i < n; i++) {
            Action action = actions.get(i);
            float cx = buttonCenterX(mButtonsOnLeft, cardEdge, gap, button, arc,
                    arcFraction(i, n));
            float cy = centerY + (i - (n - 1) / 2f) * spacing;
            View v = makeButton(action, Math.round(button));
            v.setTranslationX(cx - button / 2f);
            v.setTranslationY(cy - button / 2f);
            mButtons.add(v);
        }

        // Drag-layer coordinates: no inset margins (InsettableFrameLayout adds them otherwise).
        BaseDragLayer.LayoutParams lp = new BaseDragLayer.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.ignoreInsets = true;
        dragLayer.addView(this, lp);
        mIsOpen = true;
        animateOpen();
        Log.i(TAG, "opened for task " + taskView.getTaskIds()[0] + " with " + n + " buttons on the "
                + (mButtonsOnLeft ? "left" : "right"));
        return true;
    }

    /**
     * How far the card slides away from the buttons: enough for its near edge to clear
     * {@code clearance} from the buttons' screen edge, and at least {@code minShift}. Positive is
     * to the right (buttons on the left).
     */
    static float shiftFor(boolean buttonsOnLeft, float cardLeft, float cardRight, float layerWidth,
            float clearance, float minShift) {
        float needed = buttonsOnLeft ? clearance - cardLeft : cardRight - (layerWidth - clearance);
        float shift = Math.max(minShift, needed);
        return buttonsOnLeft ? shift : -shift;
    }

    /**
     * A button's centre x: next to the slid card's edge ({@code gap} from it), the arc's middle
     * ({@code bulge} 1) farthest from the card.
     */
    static float buttonCenterX(boolean buttonsOnLeft, float cardEdge, float gap, float button,
            float arc, float bulge) {
        float fromEdge = gap + button / 2f + arc * bulge;
        return buttonsOnLeft ? cardEdge - fromEdge : cardEdge + fromEdge;
    }

    /**
     * How far another card slides so the button column ({@code colLeft..colRight},
     * {@code colTop..colBottom}) does not show over it: only a card reaching under the column
     * from the buttons' side, and only past the column's outer edge ({@code pad} beyond it). 0
     * for a card clear of it (to the side, beyond it, or above or below it).
     */
    static float neighbourShift(boolean buttonsOnLeft, float otherLeft, float otherRight,
            float otherTop, float otherBottom, float colLeft, float colRight, float colTop,
            float colBottom, float pad) {
        if (otherBottom <= colTop || otherTop >= colBottom) return 0f;
        if (buttonsOnLeft) {
            if (otherLeft >= colRight) return 0f;
            return Math.min(0f, colLeft - pad - otherRight);
        }
        if (otherRight <= colLeft) return 0f;
        return Math.max(0f, colRight + pad - otherLeft);
    }

    /**
     * Which side the buttons open on (owner, 0.4.0.6): the side where no other card is under them
     * (the stack's and the row's first card: the right; the grid: the outer side); when both sides
     * are clear or both are not, the side of the finger.
     */
    static boolean buttonsOnLeft(boolean fingerOnLeft, boolean leftClear, boolean rightClear) {
        if (leftClear != rightClear) return leftClear;
        return fingerOnLeft;
    }

    /**
     * The stack's own rule (owner, 0.4.0.7): the first (newest) card's buttons on its newer side,
     * where no card is; every other card's on the older side. {@code olderToLeft}: RecentsView's
     * isRtl(), true for left-to-right languages (the newest card is the rightmost).
     */
    static boolean stackButtonsOnLeft(boolean firstCard, boolean olderToLeft) {
        return firstCard != olderToLeft;
    }

    /** The other cards under a button column on that side, with how far each slides away. */
    private List<Neighbour> neighboursFor(boolean buttonsOnLeft, TaskView held,
            BaseDragLayer<?> dragLayer, float colLeft, float colRight, float colTop,
            float colBottom) {
        List<Neighbour> out = new ArrayList<>();
        RecentsView<?, ?> recentsView = held.getRecentsView();
        if (recentsView == null) return out;
        float pad = NEIGHBOUR_PAD_DP * mDensity;
        Rect r = new Rect();
        for (TaskView other : recentsView.getTaskViews()) {
            if (other == held || other.getVisibility() != View.VISIBLE
                    || other.getAlpha() == 0f) {
                continue;
            }
            dragLayer.getDescendantRectRelativeToSelf(other, r);
            float shift = neighbourShift(buttonsOnLeft, r.left, r.right, r.top, r.bottom,
                    colLeft, colRight, colTop, colBottom, pad);
            if (shift != 0f) {
                out.add(new Neighbour(other,
                        other.getHgActionsTranslationX() + shift, HgLiveTile.primaryBase(other)));
            }
        }
        return out;
    }

    /** One animator moving every neighbour to its slide ({@code open}) or back to 0. */
    private ValueAnimator neighboursAnimator(boolean open, long duration) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(duration);
        a.setInterpolator(EMPHASIZED);
        a.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            for (Neighbour nb : mNeighbours) nb.set(nb.shift * (open ? t : 1f - t));
        });
        return a;
    }

    /** Every neighbour back where it was, at once. */
    private void resetNeighbours() {
        for (Neighbour nb : mNeighbours) nb.set(0f);
    }

    /** 1 in the middle of the column, 0 at its ends: how far a button bulges away from the card. */
    static float arcFraction(int index, int count) {
        if (count <= 1) return 1f;
        float half = (count - 1) / 2f;
        float p = (index - half) / half;
        return 1f - p * p;
    }

    /** The column's centre: on the card's centre, kept inside the layer's insets. */
    static float clampColumnCenter(float cardCenterY, int count, float spacing, float button,
            float top, float bottom) {
        float half = (count - 1) / 2f * spacing + button / 2f;
        float min = top + half;
        float max = bottom - half;
        if (min > max) return (top + bottom) / 2f;
        return Math.max(min, Math.min(max, cardCenterY));
    }

    private View makeButton(Action action, int size) {
        ImageView v = new ImageView(getContext());
        int padding = Math.round(GLYPH_PADDING_DP * mDensity);
        v.setImageResource(action.iconRes);
        v.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        HgBlurSupport.followGlassCircle(v); // frosted, or solid without blur
        v.setPadding(padding, padding, padding, padding);
        v.setContentDescription(action.label);
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(b -> {
            if (!mIsOpen) return;
            TaskView taskView = mTaskView;
            if (taskView == null || !taskView.isAttachedToWindow()
                    || taskView.getRecentsView() == null) {
                // The card went away under the buttons (dismissed, recents reloaded): nothing to act on.
                close(false);
                return;
            }
            action.run.run();
        });
        v.setAlpha(0f);
        mButtonLayer.addView(v, new FrameLayout.LayoutParams(size, size));
        return v;
    }

    private void animateOpen() {
        AnimatorSet set = new AnimatorSet();
        List<Animator> parts = new ArrayList<>();
        ObjectAnimator card = ObjectAnimator.ofFloat(mTaskView, CARD_SHIFT, mShift);
        card.setDuration(OPEN_MS);
        card.setInterpolator(EMPHASIZED);
        parts.add(card);
        if (!mNeighbours.isEmpty()) parts.add(neighboursAnimator(true, OPEN_MS));
        float fromCard = (mButtonsOnLeft ? 1 : -1) * 40 * mDensity;
        for (int i = 0; i < mButtons.size(); i++) {
            View v = mButtons.get(i);
            float x = v.getTranslationX();
            v.setTranslationX(x + fromCard);
            v.setScaleX(0.4f);
            v.setScaleY(0.4f);
            long delay = i * STAGGER_MS;
            ObjectAnimator alpha = ObjectAnimator.ofFloat(v, View.ALPHA, 1f);
            alpha.setDuration(OPEN_MS / 2);
            ObjectAnimator tx = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, x);
            tx.setInterpolator(EMPHASIZED);
            ObjectAnimator sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f);
            OvershootInterpolator overshoot = new OvershootInterpolator(1.6f);
            sx.setInterpolator(overshoot);
            sy.setInterpolator(overshoot);
            for (ObjectAnimator a : new ObjectAnimator[] {alpha, tx, sx, sy}) {
                a.setStartDelay(delay);
                if (a != alpha) a.setDuration(OPEN_MS);
                parts.add(a);
            }
        }
        set.playTogether(parts);
        startAnimator(set);
    }

    private void startAnimator(Animator animator) {
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = animator;
        animator.start();
    }

    @Override
    protected void handleClose(boolean animate) {
        if (!mIsOpen) return;
        mIsOpen = false;
        TaskView taskView = mTaskView;
        if (!animate || taskView == null) {
            if (mAnimator != null) mAnimator.cancel();
            if (taskView != null) CARD_SHIFT.setValue(taskView, 0f);
            resetNeighbours();
            restoreCardZ();
            removeFromDragLayer();
            return;
        }
        AnimatorSet set = new AnimatorSet();
        List<Animator> parts = new ArrayList<>();
        ObjectAnimator card = ObjectAnimator.ofFloat(taskView, CARD_SHIFT, 0f);
        card.setDuration(CLOSE_MS + 60);
        card.setInterpolator(EMPHASIZED);
        parts.add(card);
        if (!mNeighbours.isEmpty()) parts.add(neighboursAnimator(false, CLOSE_MS + 60));
        for (View v : mButtons) {
            ObjectAnimator alpha = ObjectAnimator.ofFloat(v, View.ALPHA, 0f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 0.6f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.6f);
            for (ObjectAnimator a : new ObjectAnimator[] {alpha, sx, sy}) {
                a.setDuration(CLOSE_MS);
                a.setInterpolator(new DecelerateInterpolator());
                parts.add(a);
            }
        }
        set.playTogether(parts);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    CARD_SHIFT.setValue(taskView, 0f);
                    resetNeighbours();
                }
                restoreCardZ();
                removeFromDragLayer();
            }
        });
        startAnimator(set);
    }

    /** Puts the held card back at its own depth, once its slide back is done. */
    private void restoreCardZ() {
        if (mTaskView != null && !Float.isNaN(mCardZ)) mTaskView.setTranslationZ(mCardZ);
        mCardZ = Float.NaN;
    }

    private void removeFromDragLayer() {
        if (mDetaching) {
            // ViewGroup.dispatchDetachedFromWindow is iterating over the drag layer's children:
            // removing one now throws or skips a view. Remove this one once that is done.
            Executors.MAIN_EXECUTOR.post(this::removeFromDragLayer);
            return;
        }
        if (getParent() != null) mContainer.getDragLayer().removeView(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Never leave the card shifted, whatever removed this view.
        mIsOpen = false;
        mDetaching = true;
        try {
            if (mAnimator != null) mAnimator.cancel();
        } finally {
            mDetaching = false;
        }
        if (mTaskView != null && mTaskView.getHgActionsTranslationX() != 0f) {
            CARD_SHIFT.setValue(mTaskView, 0f);
        }
        resetNeighbours();
        restoreCardZ();
    }

    @Override
    protected boolean isOfType(int type) {
        return (type & TYPE_TASK_MENU) != 0;
    }

    @Override
    public boolean onControllerInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        BaseDragLayer<?> dragLayer = mContainer.getDragLayer();
        for (View v : mButtons) {
            if (dragLayer.isEventOverView(v, ev)) return false; // the button takes the tap
        }
        close(true);
        return true; // the tap that closes does not reach the cards
    }
}
