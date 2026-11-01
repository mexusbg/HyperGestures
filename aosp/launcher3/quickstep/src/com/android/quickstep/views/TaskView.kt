/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.IdRes
import android.app.ActivityOptions
import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.AttributeSet
import android.util.FloatProperty
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnCancel
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import com.android.app.animation.Interpolators
import com.android.app.tracing.traceSection
import com.android.internal.jank.Cuj.CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Flags.enableRefactorDigitalWellbeingToast
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.Utilities.getTrimmedStackTrace
import com.android.launcher3.concurrent.annotations.LightweightBackground
import com.android.launcher3.concurrent.annotations.LightweightBackgroundPriority
import com.android.launcher3.concurrent.annotations.Ui
import com.android.launcher3.dagger.ActivityContextComponent
import com.android.launcher3.logging.StatsLogManager.LauncherEvent
import com.android.launcher3.model.data.TaskViewItemInfo
import com.android.launcher3.testing.TestLogging
import com.android.launcher3.testing.shared.TestProtocol
import com.android.launcher3.util.Executors
import com.android.launcher3.util.KFloatProperty
import com.android.launcher3.util.MultiPropertyDelegate
import com.android.launcher3.util.MultiPropertyFactory
import com.android.launcher3.util.MultiValueAlpha
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.SplitConfigurationOptions
import com.android.launcher3.util.SplitConfigurationOptions.STAGE_POSITION_UNDEFINED
import com.android.launcher3.util.SplitConfigurationOptions.StagePosition
import com.android.launcher3.util.TraceHelper
import com.android.launcher3.util.TransformingTouchDelegate
import com.android.launcher3.util.ViewPool
import com.android.launcher3.util.rects.set
import com.android.quickstep.FullscreenDrawParams
import com.android.quickstep.HgLog
import com.android.quickstep.HgStackLayout // HyperGestures
import com.android.quickstep.HgVerticalLayout // HyperGestures
import com.android.quickstep.RemoteAnimationTargets
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle
import com.android.quickstep.TaskOverlayFactory
import com.android.quickstep.TaskViewUtils
import com.android.quickstep.orientation.RecentsPagedOrientationHandler
import com.android.quickstep.recents.domain.usecase.ThumbnailPosition
import com.android.quickstep.recents.ui.mapper.TaskUiStateMapper
import com.android.quickstep.recents.ui.viewmodel.TaskData
import com.android.quickstep.recents.ui.viewmodel.TaskTileUiState
import com.android.quickstep.recents.ui.viewmodel.TaskViewModel
import com.android.quickstep.task.TaskDismissButtonState
import com.android.quickstep.task.thumbnail.TaskContentView
import com.android.quickstep.task.thumbnail.TaskThumbnailView
import com.android.quickstep.util.ActiveGestureErrorDetector
import com.android.quickstep.util.ActiveGestureLog
import com.android.quickstep.util.BorderAnimator
import com.android.quickstep.util.BorderAnimator.Companion.DEFAULT_APPEARANCE_ANIMATION_DURATION_MS
import com.android.quickstep.util.BorderAnimator.Companion.DEFAULT_DISAPPEARANCE_ANIMATION_DURATION_MS
import com.android.quickstep.util.BorderAnimator.Companion.DEFAULT_INTERPOLATOR
import com.android.quickstep.util.BorderAnimator.Companion.createSimpleBorderAnimator
import com.android.quickstep.util.GroupTask
import com.android.quickstep.util.RecentsOrientedState
import com.android.quickstep.util.SingleTask
import com.android.quickstep.util.TaskCornerRadius
import com.android.quickstep.util.TaskRemovedDuringLaunchListener
import com.android.quickstep.util.isExternalDisplay
import com.android.quickstep.util.safeDisplayId
import com.android.quickstep.views.IconAppChipView.AppChipStatus
import com.android.quickstep.views.OverviewActionsView.DISABLED_NO_THUMBNAIL
import com.android.quickstep.views.OverviewActionsView.DISABLED_ROTATED
import com.android.quickstep.views.RecentsView.UNBOUND_TASK_VIEW_ID
import com.android.systemui.shared.recents.model.Task
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.InteractionJankMonitorWrapper
import com.android.wm.shell.shared.split.SplitScreenConstants
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A task in the Recents view. */
open class TaskView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
    focusBorderAnimator: BorderAnimator? = null,
    hoverBorderAnimator: BorderAnimator? = null,
    val type: TaskViewType = TaskViewType.SINGLE,
    protected val thumbnailFullscreenParams: FullscreenDrawParams = FullscreenDrawParams(context),
) : FrameLayout(context, attrs), ViewPool.Reusable {
    var groupTask: GroupTask? = null

    val taskIds: IntArray
        /** Returns a copy of integer array containing taskIds of all tasks in the TaskView. */
        get() = taskContainers.map { it.task.key.id }.toIntArray()

    val taskIdSet: Set<Int>
        /** Returns a copy of integer array containing taskIds of all tasks in the TaskView. */
        get() = taskContainers.map { it.task.key.id }.toSet()

    val snapshotViews: Array<View>
        get() = taskContainers.map { it.snapshotView }.toTypedArray()

    val taskContentViews: Array<TaskContentView>
        get() = taskContainers.map { it.taskContentView }.toTypedArray()

    val isGridTask: Boolean
        /** Returns whether the task is part of overview grid and not being focused. */
        get() = container.deviceProfile.deviceProperties.isLargeScreen && !isLargeTile

    val isRunningTask: Boolean
        get() = this === recentsView?.runningTaskView

    private val isSelectedTask: Boolean
        get() = this === recentsView?.selectedTaskView

    open val displayId: Int
        get() = taskContainers.firstOrNull()?.task.safeDisplayId

    val isExternalDisplay: Boolean
        get() = displayId.isExternalDisplay

    val isLargeTile: Boolean
        get() = type == TaskViewType.DESKTOP

    val recentsView: RecentsView<*, *>?
        get() = parent as? RecentsView<*, *>

    val pagedOrientationHandler: RecentsPagedOrientationHandler
        get() = orientedState.orientationHandler

    val firstTaskContainer: TaskContainer?
        get() = taskContainers.firstOrNull()

    val firstTask: Task?
        /** Returns the first task bound to this TaskView. */
        get() = firstTaskContainer?.task

    val isOnGridBottomRow: Boolean
        get() = recentsView?.isOnGridBottomRow(this) == true

    /**
     * A [TaskViewItemInfo] of this TaskView. The [firstTaskContainer] will be used to get some
     * specific information like user, title etc of the Task. However, these task specific
     * information will be skipped if the TaskView has no [taskContainers]. Note, please use
     * [TaskContainer.itemInfo] for [TaskViewItemInfo] on a specific [TaskContainer].
     */
    val itemInfo: TaskViewItemInfo
        get() = TaskViewItemInfo(this, firstTaskContainer)

    protected val container: RecentsViewContainer by lazy {
        RecentsViewContainer.containerFromContext(context)
    }
    protected val lastTouchDownPosition = PointF()

    // Derived view properties
    protected val persistentScale: Float
        /**
         * Returns multiplication of scale that is persistent (e.g. fullscreen and grid), and does
         * not change according to a temporary state.
         */
        get() = Utilities.mapRange(gridProgress, nonGridScale, 1f)

    protected val persistentTranslationX: Float
        /**
         * Returns addition of translationX that is persistent (e.g. fullscreen and grid), and does
         * not change according to a temporary state (e.g. task offset).
         */
        get() = (getNonGridTrans(nonGridTranslationX) + getGridTrans(this.gridTranslationX))

    val persistentTranslationY: Float
        /**
         * Returns addition of translationY that is persistent (e.g. fullscreen and grid), and does
         * not change according to a temporary state (e.g. task offset).
         */
        get() = boxTranslationY + getGridTrans(gridTranslationY)

    protected val primarySplitTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getPrimaryValue(
                SPLIT_SELECT_TRANSLATION_X,
                SPLIT_SELECT_TRANSLATION_Y,
            )

    protected val secondarySplitTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getSecondaryValue(
                SPLIT_SELECT_TRANSLATION_X,
                SPLIT_SELECT_TRANSLATION_Y,
            )

    val primaryDismissTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getPrimaryValue(DISMISS_TRANSLATION_X, DISMISS_TRANSLATION_Y)

    val secondaryDismissTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getSecondaryValue(DISMISS_TRANSLATION_X, DISMISS_TRANSLATION_Y)

    protected val primaryTaskOffsetTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getPrimaryValue(
                TASK_OFFSET_TRANSLATION_X,
                TASK_OFFSET_TRANSLATION_Y,
            )

    protected val secondaryTaskOffsetTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getSecondaryValue(
                TASK_OFFSET_TRANSLATION_X,
                TASK_OFFSET_TRANSLATION_Y,
            )

    protected val taskResistanceTranslationProperty: FloatProperty<TaskView>
        get() =
            pagedOrientationHandler.getSecondaryValue(
                TASK_RESISTANCE_TRANSLATION_X,
                TASK_RESISTANCE_TRANSLATION_Y,
            )

    private val tempCoordinates = FloatArray(2)
    private val borderWidthPx: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.task_hover_focus_border_width)
    }
    private val borderOffsetPx: Int by lazy {
        context.resources.getDimensionPixelSize(R.dimen.task_hover_focus_offset_size)
    }
    private val focusBorderAnimator: BorderAnimator =
        focusBorderAnimator
            ?: createSimpleBorderAnimator(
                TaskCornerRadius.get(context).toInt() + borderOffsetPx,
                borderWidthPx,
                {
                    getThumbnailBounds(it)
                    it.inset(-borderOffsetPx, -borderOffsetPx)
                },
                this,
                context
                    .obtainStyledAttributes(attrs, R.styleable.TaskView, defStyleAttr, defStyleRes)
                    .getColor(
                        R.styleable.TaskView_focusBorderColor,
                        BorderAnimator.DEFAULT_BORDER_COLOR,
                    ),
            )

    private val hoverBorderAnimator: BorderAnimator =
        hoverBorderAnimator
            ?: createSimpleBorderAnimator(
                TaskCornerRadius.get(context).toInt() + borderOffsetPx,
                borderWidthPx,
                {
                    getThumbnailBounds(it)
                    it.inset(-borderOffsetPx, -borderOffsetPx)
                },
                this,
                context
                    .obtainStyledAttributes(attrs, R.styleable.TaskView, defStyleAttr, defStyleRes)
                    .getColor(
                        R.styleable.TaskView_hoverBorderColor,
                        BorderAnimator.DEFAULT_BORDER_COLOR,
                    ),
            )

    private val rootViewDisplayId: Int
        get() = rootView.display?.displayId ?: Display.DEFAULT_DISPLAY

    /** Returns a list of all TaskContainers in the TaskView. */
    lateinit var taskContainers: List<TaskContainer>
        protected set

    lateinit var orientedState: RecentsOrientedState
    lateinit var taskOverlayFactory: TaskOverlayFactory

    var taskViewId = UNBOUND_TASK_VIEW_ID
    var isEndQuickSwitchCuj = false
    var isBeingDraggedForDismissal = false
    var isBeingDismissed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // HyperGestures: in the stack layout a card being dismissed no longer covers the
            // next card's header (RecentsView.hgUpdateStack): clip it again now.
            recentsView?.hgOnDismissChanged()
            // HyperGestures: in the vertical layout a leaving card keeps the centring it had as
            // it started to leave (a lone card leaves from the centre, one of two from its
            // slot, even once the other's removal leaves it alone).
            hgLeavingCentring = if (value) hgVertical?.centring(false) ?: 0f else 0f
        }

    private val systemGestureExclusionRectList = listOf(Rect()) // We only need 1 exclusion Rect

    var sysUiStatusNavFlags: Int = 0
        private set

    // Various animation progress variables.
    // progress: 0 = show icon and no insets; 1 = don't show icon and show full insets.
    protected var fullscreenProgress = 0f
        set(value) {
            if (value == field) return
            field = Utilities.boundToRange(value, 0f, 1f)
            onFullscreenProgressChanged(field)
        }

    // gridProgress 0 = carousel; 1 = 2 row grid.
    protected var gridProgress = 0f
        set(value) {
            field = value
            onGridProgressChanged()
        }

    /**
     * The modalness of this view is how it should be displayed when it is shown on its own in the
     * modal state of overview. 0 being in context with other tasks, 1 being shown on its own.
     */
    protected var modalness = 0f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            onModalnessUpdated(field)
        }

    var modalPivot: PointF? = null
        set(value) {
            field = value
            updatePivots()
        }

    var splitSplashAlpha = 0f
        set(value) {
            field = value
            applyThumbnailSplashAlpha()
        }

    protected var taskThumbnailSplashAlpha = 0f
        set(value) {
            field = value
            applyThumbnailSplashAlpha()
        }

    protected var nonGridScale = 1f
        set(value) {
            field = value
            applyScale()
        }

    private var dismissScale = 1f
        set(value) {
            field = value
            applyScale()
        }

    var modalScale = 1f
        set(value) {
            field = value
            applyScale()
        }

    var animateToIconScale = 1f
        set(value) {
            field = value
            applyScale()
        }

    private var dismissTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    private var dismissTranslationY = 0f
        set(value) {
            field = value
            applyTranslationY()
        }

    private var taskOffsetTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    private var taskOffsetTranslationY = 0f
        set(value) {
            field = value
            applyTranslationY()
        }

    private var taskResistanceTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    private var taskResistanceTranslationY = 0f
        set(value) {
            field = value
            applyTranslationY()
        }

    // The following translation variables should only be used in the same orientation as Launcher.
    private var boxTranslationY = 0f
        set(value) {
            field = value
            applyTranslationY()
        }

    // The following grid translations scales with mGridProgress.
    var gridTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    var gridTranslationY = 0f
        protected set(value) {
            field = value
            applyTranslationY()
        }

    // The following grid translation is used to animate closing the gap between grid and clear all.
    private var gridEndTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    // Applied as a complement to gridTranslation, for adjusting the carousel overview and quick
    // switch.
    var nonGridTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    // Used when in SplitScreenSelectState
    private var splitSelectTranslationY = 0f
        set(value) {
            field = value
            applyTranslationY()
        }

    private var splitSelectTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    // HyperGestures: the card's slide aside while HgTaskActionsView's hold buttons are open.
    var hgActionsTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    // HyperGestures: MIUI's stack layout (HgStackLayout), set by RecentsView.hgUpdateStack with
    // the card's natural on-screen offset from the front card's place; null in the horizontal
    // layout, where the stack values below stay 0 / 1 and change nothing.
    private var hgStack: HgStackLayout? = null
    private var hgStackOffset = 0f
    /** HyperGestures: the stack layout's x offset from the card's natural place. */
    var hgStackTranslationX = 0f
        private set
    /** HyperGestures: the stack layout's scale (1 for the front card). */
    var hgStackScale = 1f
        private set
    private var hgStackDim = 0f
    private var hgStackHeaderAlpha = 1f
    // HyperGestures: in the stack layout, whether a newer card lies on top of this one (it is not
    // the newest), and the x range of this view (its own px) its header may draw in: up to that
    // newer card's edge, as MIUI shows an older card's header only within its visible strip
    // (HgStackLayout.headerStrip). +-HG_NO_CLIP: unclipped.
    private var hgStackHasNewer = false
    /** HyperGestures: the header's drawable x range in this view's px (see hgStackHasNewer). */
    var hgHeaderClipStart = -HG_NO_CLIP
        private set
    var hgHeaderClipEnd = HG_NO_CLIP
        private set
    private val hgClipRect = Rect()

    // HyperGestures: MIUI's vertical layout (HgVerticalLayout), set by RecentsView.hgUpdateStack
    // with the card's natural offset in the strip (as hgStackOffset) and the grid's scroll; null
    // elsewhere. The card's x offset into its grid slot is hgStackTranslationX.
    private var hgVertical: HgVerticalLayout? = null
    private var hgVerticalScrollY = 0f
    // HyperGestures: the grid scroll the card is drawn at: the scroll, or the one frozen while
    // the swipe up runs (RecentsView.hgUpdateStack).
    private var hgVerticalShownScrollY = 0f
    /** HyperGestures: the vertical layout's y offset from the card's natural place. */
    var hgStackTranslationY = 0f
        private set
    /**
     * HyperGestures: the side (-1 left, 1 right) the vertical layout's dismiss translation moves
     * the card to: the dismiss swipe's own direction while one runs (TaskViewDismissTouchController
     * sets it), else the card's column, as MiuiHome's Clear all.
     */
    var hgDismissSide = -1
    /**
     * HyperGestures: a sideways dismiss swipe drags this card (TaskViewDismissTouchController, from
     * its drag start to its release): hgDismissSide stays the swipe's. AOSP's
     * isBeingDraggedForDismissal is set at every touch-down, so it cannot tell.
     */
    var hgDismissDragging = false

    // HyperGestures: the vertical layout's centring of this card while it is being dismissed
    // (isBeingDismissed); HgVerticalLayout.centring gives a leaving card none.
    private var hgLeavingCentring = 0f

    /** HyperGestures: whether the vertical layout places this card. */
    val isHgVertical: Boolean
        get() = hgVertical != null

    // HyperGestures: where the last touch went down on this card (its own x), so the hold buttons
    // open on the side of the finger.
    private var hgLastDownX = -1f

    /**
     * HyperGestures: whether this card shows the app header (HgTaskHeader), as its chips were
     * inflated (IconAppChipView.isHgHeader): the header's taps, the touch room above the cards and
     * the hold buttons follow it, not a setting toggled since.
     */
    val hgHasHeader: Boolean
        get() = ::taskContainers.isInitialized && taskContainers.any { it.iconView.isHgHeader }

    /** HyperGestures: the windowing mode of each of this card's tasks. */
    private fun hgWindowingModes(): IntArray =
        taskContainers.map { it.task.key.windowingMode }.toIntArray()

    /** HyperGestures: whether this card's app is in HyperOS's split screen (HgSystemSplitLaunch). */
    private fun hgInSystemSplit(): Boolean =
        com.android.quickstep.HgSystemSplitLaunch.inSystemSplit(hgWindowingModes())

    /**
     * HyperGestures: opening a card in HyperOS's split screen, or into the empty half of its
     * one-app split; recents' next stop must not move its task back, which would make the split
     * exit (HgSystemSplitLaunch).
     */
    private fun hgNoteSystemSplitLaunch() {
        val fillsOneAppSplit =
            com.android.quickstep.HgSystemSplitLaunch.fillsOneAppSplit(
                com.android.quickstep.HgSoScState.inOneAppSplit(),
                taskContainers.size,
                taskContainers.all { it.task.isDockable },
            )
        val recents = container as? com.android.quickstep.RecentsActivity
        if (hgInSystemSplit()) {
            Log.d(TAG, "${taskIds.contentToString()} - launch in HyperOS split screen")
            recents?.hgOnSplitPairLaunched()
        } else if (fillsOneAppSplit) {
            Log.d(TAG, "${taskIds.contentToString()} - launch in HyperOS's one-app split")
            recents?.hgOnOneAppSplitFill()
        }
    }

    var animateToIconTranslationX = 0f
        set(value) {
            field = value
            applyTranslationX()
        }

    var animateToIconTranslationY = 0f
        set(value) {
            field = value
            applyTranslationY()
        }

    private val taskViewAlpha = MultiValueAlpha(this, Alpha.entries.size)
    protected var stableAlpha by MultiPropertyDelegate(taskViewAlpha, Alpha.Stable)
    var attachAlpha by MultiPropertyDelegate(taskViewAlpha, Alpha.Attach)
    var splitAlpha by MultiPropertyDelegate(taskViewAlpha, Alpha.Split)
    private var modalAlpha by MultiPropertyDelegate(taskViewAlpha, Alpha.Modal)
    var animateToIconAlpha by MultiPropertyDelegate(taskViewAlpha, Alpha.AnimateToIcon)
    // HyperGestures: the vertical layout's fade of a card scrolled above the top edge.
    private var hgScrollAlpha by MultiPropertyDelegate(taskViewAlpha, Alpha.HgScroll)

    var shouldShowScreenshot = false
        get() = !isRunningTask || field
        set(value) {
            if (field == value) return
            field = value
        }

    /** Enable or disable showing border on hover and focus change */
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    var borderEnabled = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            // Set the animation correctly in case it misses the hover/focus event during state
            // transition
            hoverBorderAnimator.setBorderVisibility(visible = field && isHovered, animated = true)
            focusBorderAnimator.setBorderVisibility(visible = field && isFocused, animated = true)
        }

    /**
     * Used to cache the hover border state so we don't repeatedly call the border animator with
     * every hover event when the user hasn't crossed the threshold of the [thumbnailBounds].
     */
    private var hoverBorderVisible = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            Log.d(
                TAG,
                "${taskIds.contentToString()} - setting border animator visibility to: $field",
            )
            hoverBorderAnimator.setBorderVisibility(visible = field, animated = true)
        }

    // Used to cache thumbnail bounds to avoid recalculating on every hover move.
    private var thumbnailBounds = Rect()

    // Progress variable indicating if the TaskView is in a settled state:
    // 0 = The TaskView is in a transitioning state e.g. during gesture, in quickswitch carousel,
    // becoming focus task etc.
    // 1 = The TaskView is settled and no longer transitioning
    private var settledProgress = 1f
        set(value) {
            if (value == field) return
            field = value
            onSettledProgressUpdated(field)
        }

    private val settledProgressPropertyFactory =
        MultiPropertyFactory(
            this,
            SETTLED_PROGRESS,
            SettledProgress.entries.size,
            { x: Float, y: Float -> x * y },
            1f,
        )
    private var settledProgressFullscreen by
        MultiPropertyDelegate(settledProgressPropertyFactory, SettledProgress.Fullscreen)
    private var settledProgressGesture by
        MultiPropertyDelegate(settledProgressPropertyFactory, SettledProgress.Gesture)
    private var settledProgressDismiss by
        MultiPropertyDelegate(settledProgressPropertyFactory, SettledProgress.Dismiss)

    @Inject lateinit var viewModel: TaskViewModel
    @Inject
    @LightweightBackground(LightweightBackgroundPriority.UI)
    lateinit var lightweightBackgroundDispatcher: CoroutineDispatcher
    @Inject @Ui lateinit var mainDispatcher: CoroutineDispatcher
    @Inject lateinit var coroutineScope: CoroutineScope
    private val coroutineJobs = mutableListOf<Job>()
    private var taskDismissButton: FrameLayout? = null
    private var taskDismissButtonAnimator: ObjectAnimator? = null
    private var taskDismissButtonEnabled: Boolean = false

    /**
     * Returns a sequence of [Pair]s, where each pair contains a [IconAppChipView] and its
     * corresponding [TransformingTouchDelegate].
     */
    open fun getTaskIcons(): Sequence<Pair<IconAppChipView, TransformingTouchDelegate>> =
        taskContainers.asSequence().map { it.iconView to it.iconTouchDelegate }

    private var iconFadeInOnGestureCompleteAnimator: ObjectAnimator? = null
    private var isClickableAsLiveTile = true

    init {
        setOnClickListener { _ -> onClick() }
        // HyperGestures: hold a card for HyperOS's round action buttons (HgTaskActionsView).
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) hgLastDownX = ev.x
            false
        }
        setOnLongClickListener {
            val shown =
                com.android.quickstep.HgTaskActionsView.showFor(
                    this,
                    if (hgLastDownX >= 0f) hgLastDownX else width / 2f,
                )
            // The rest of this touch belongs to the buttons: no dismiss, lock swipe or scroll of
            // the card under them (as AOSP's icon long-press does for its menu).
            if (shown) parent?.requestDisallowInterceptTouchEvent(true)
            shown
        }

        setWillNotDraw(false)
    }

    override fun onConfigurationChanged(p0: Configuration?) {
        super.onConfigurationChanged(p0)
        thumbnailFullscreenParams.updateCornerRadius(context)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    public override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (borderEnabled) {
            focusBorderAnimator.setBorderVisibility(gainFocus, /* animated= */ true)
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (borderEnabled) {
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    getThumbnailBounds(thumbnailBounds)
                    hoverBorderVisible = event.isWithinThumbnailBounds()
                    showTaskDismissButton()
                }
                MotionEvent.ACTION_HOVER_MOVE ->
                    hoverBorderVisible = event.isWithinThumbnailBounds()
                MotionEvent.ACTION_HOVER_EXIT -> {
                    hoverBorderVisible = false
                    if (taskDismissButton?.isHovered == false) {
                        hideTaskDismissButton()
                    }
                }
                else -> {}
            }
        }
        return super.onHoverEvent(event)
    }

    open fun initialiseInjectables(component: ActivityContextComponent) {
        component.inject(this)
    }

    private fun showTaskDismissButton() {
        if (!taskDismissButtonEnabled) return

        val taskDismissButton = taskDismissButton ?: return

        if (taskDismissButtonAnimator?.isRunning == true) {
            taskDismissButtonAnimator?.cancel()
        }

        if (taskDismissButton.alpha == 1f) {
            return
        }

        taskDismissButtonAnimator =
            ObjectAnimator.ofFloat(taskDismissButton, View.ALPHA, 1f).apply {
                duration = DEFAULT_APPEARANCE_ANIMATION_DURATION_MS
                interpolator = DEFAULT_INTERPOLATOR
                doOnCancel { taskDismissButton.alpha = 0f }
                start()
            }
    }

    private fun hideTaskDismissButton() {
        if (!taskDismissButtonEnabled) return

        val taskDismissButton = taskDismissButton ?: return

        if (taskDismissButtonAnimator?.isRunning == true) {
            taskDismissButtonAnimator?.cancel()
        }

        if (taskDismissButton.alpha == 0f) {
            return
        }

        taskDismissButtonAnimator =
            ObjectAnimator.ofFloat(taskDismissButton, View.ALPHA, 0f).apply {
                duration = DEFAULT_DISAPPEARANCE_ANIMATION_DURATION_MS
                interpolator = DEFAULT_INTERPOLATOR
                doOnCancel { taskDismissButton.alpha = 0f }
                start()
            }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val recentsView = recentsView ?: return false
        val splitSelectStateController = recentsView.splitSelectController
        // Disable taps for split selection animation unless we have a task not being selected
        if (
            splitSelectStateController.isSplitSelectActive &&
                taskContainers.none { it.task.key.id != splitSelectStateController.initialTaskId }
        ) {
            return false
        }
        if (ev.action == MotionEvent.ACTION_DOWN) {
            with(lastTouchDownPosition) {
                x = ev.x
                y = ev.y
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun draw(canvas: Canvas) {
        // Draw border first so any child views outside of the thumbnail bounds are drawn above it.
        focusBorderAnimator.drawBorder(canvas)
        hoverBorderAnimator.drawBorder(canvas)
        super.draw(canvas)
    }

    override fun setLayoutDirection(layoutDirection: Int) {
        super.setLayoutDirection(layoutDirection)
        val deviceLayoutDirection = resources.configuration.layoutDirection
        getTaskIcons().forEach { (icon, _) -> icon.layoutDirection = deviceLayoutDirection }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        hgApplyHeaderClip() // HyperGestures: the headers may have moved within the card
        updatePivots()
        systemGestureExclusionRectList[0].apply {
            this.right = width
            this.bottom = height
        }
        getThumbnailBounds(thumbnailBounds)
    }

    private fun updatePivots() {
        val modalPivot = modalPivot
        if (modalPivot != null) {
            pivotX = modalPivot.x
            pivotY = modalPivot.y
        } else {
            if (container.deviceProfile.deviceProperties.isLargeScreen) {
                pivotX =
                    (if (layoutDirection == LAYOUT_DIRECTION_RTL) 0 else right - left).toFloat()
                pivotY = 0f
            } else {
                pivotX = (right - left) * 0.5f
                pivotY = height * 0.5f
            }
        }
    }

    override fun onRecycle() {
        isBeingDraggedForDismissal = false
        isBeingDismissed = false
        hgStack = null // HyperGestures: the next binding gets its stack values afresh
        hgVertical = null // HyperGestures: and its grid values
        hgDismissDragging = false // HyperGestures
        resetPersistentViewTransforms()

        groupTask = null
        viewModel.unbind()
        attachAlpha = 1f
        splitAlpha = 1f
        splitSplashAlpha = 0f
        modalAlpha = 1f
        modalScale = 1f
        modalPivot = null
        taskThumbnailSplashAlpha = 0f
        borderEnabled = false
        hoverBorderVisible = false
        taskViewId = UNBOUND_TASK_VIEW_ID
        // TODO(b/390583187): Clean the components UI State when TaskView is recycled.
        taskContainers.forEach { it.destroy() }

        getTaskIcons().forEach { (icon, _) -> icon.reset() }
        recycleTaskDismissButton()
    }

    private fun recycleTaskDismissButton() {
        if (taskDismissButtonAnimator?.isRunning == true) {
            taskDismissButtonAnimator?.cancel()
        }
        taskDismissButtonEnabled = false
        taskDismissButton?.setOnClickListener(null)
        taskDismissButton?.alpha = 0f
    }

    // TODO: Clip-out the icon region from the thumbnail, since they are overlapping.
    override fun hasOverlappingRendering() = false

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        with(info) {
            // Only make actions available if the app icon menu is visible to the user.
            // When modalness is >0, the user is in select mode and the icon menu is hidden.
            // When split selection is active, they should only be able to select the app and not
            // take any other action.
            val shouldPopulateAccessibilityMenu =
                modalness == 0f && recentsView?.isSplitSelectionActive == false
            if (shouldPopulateAccessibilityMenu) {
                taskContainers.forEach {
                    TraceHelper.allowIpcs("TV.a11yInfo") {
                        taskOverlayFactory.getEnabledShortcuts(this@TaskView, it).forEach { shortcut
                            ->
                            addAction(shortcut.createAccessibilityAction(context))
                        }
                    }
                }

                // Add DWB accessibility action at the end of the list
                taskContainers.forEach {
                    if (enableRefactorDigitalWellbeingToast()) {
                        it.taskContentView.getSupportedAccessibilityActions().forEach(::addAction)
                    } else {
                        it.digitalWellBeingToast?.getDWBAccessibilityAction()?.let(::addAction)
                    }
                }
            }

            recentsView?.let {
                collectionItemInfo =
                    AccessibilityNodeInfo.CollectionItemInfo(
                        0,
                        1,
                        // We only care about TaskView's for the `CollectionInfo` that Talkback uses
                        // to read out.
                        it.taskViews.reversed().indexOf(this@TaskView),
                        1,
                        false,
                    )
            }
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        // TODO(b/343708271): Add support for multiple tasks per action.
        taskContainers.forEach {
            if (enableRefactorDigitalWellbeingToast()) {
                if (it.taskContentView.handleAccessibilityAction(action)) {
                    return true
                }
            } else {
                if (it.digitalWellBeingToast?.handleAccessibilityAction(action) == true) {
                    return true
                }
            }

            taskOverlayFactory.getEnabledShortcuts(this, it).forEach { shortcut ->
                if (shortcut.hasHandlerForAction(action)) {
                    shortcut.onClick(this)
                    return true
                }
            }
        }

        return super.performAccessibilityAction(action, arguments)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        inflateViewStubs()
        taskDismissButton = findViewById(R.id.task_dismiss_button)
    }

    fun onIntersectScreenEdgeChanged(intersectsScreenEdge: Boolean) {
        systemGestureExclusionRects =
            if (intersectsScreenEdge) systemGestureExclusionRectList else emptyList()
    }

    protected open fun inflateViewStubs() {
        findViewById<ViewStub>(R.id.task_content_view)
            ?.apply {
                inflatedId = R.id.task_content_view
                layoutResource = R.layout.task_content_view
            }
            ?.inflate()

        if (!enableRefactorDigitalWellbeingToast()) {
            findViewById<ViewStub>(R.id.digital_wellbeing_toast)
                ?.apply { layoutResource = R.layout.digital_wellbeing_toast }
                ?.inflate()
        }
    }

    override fun onAttachedToWindow() =
        traceSection("TaskView.onAttachedToWindow") {
            super.onAttachedToWindow()
            // TaskView binds the ViewModel during onBind, and unbinds it in onRecycle. So it
            // should start listening here.
            // TV Lifecycle: onBind -> onAttachedToWindow -> onDetachFromWindow -> onRecycle
            coroutineJobs +=
                coroutineScope.launch(mainDispatcher) {
                    viewModel.state.collectLatest(::updateTaskViewState)
                }
        }

    private fun updateTaskViewState(state: TaskTileUiState) =
        traceSection("TaskView.updateTaskViewState") {
            sysUiStatusNavFlags = state.sysUiStatusNavFlags

            // Updating containers
            val mapOfTasks = state.tasks.associateBy { it.taskId }
            taskContainers.forEach { container ->
                val taskId = container.task.key.id
                val containerState = mapOfTasks[taskId]
                val shouldHaveHeader = (type == TaskViewType.DESKTOP)
                val shouldShowAppTimer =
                    (type == TaskViewType.SINGLE || type == TaskViewType.GROUPED)
                container.setState(
                    state = containerState,
                    hasHeader = shouldHaveHeader,
                    canShowAppTimer = shouldShowAppTimer,
                    clickCloseListener =
                        if (shouldHaveHeader) {
                            {
                                // Update the layout UI to remove this task from the layout grid,
                                // and remove the task from ActivityManager afterwards.
                                recentsView?.dismissTask(taskId, /* removeTask= */ true)
                            }
                        } else {
                            null
                        },
                )
                updateThumbnailValidity(container)
                val thumbnailPosition =
                    updateThumbnailMatrix(
                        container = container,
                        width = container.snapshotView.width,
                        height = container.snapshotView.height,
                    )
                container.setOverlayEnabled(state.taskOverlayEnabled, thumbnailPosition)
                if (state.isCentralTask) {
                    this.container.actionsView.let {
                        it.updateDisabledFlags(DISABLED_ROTATED, thumbnailPosition.isRotated)
                        it.updateDisabledFlags(
                            DISABLED_NO_THUMBNAIL,
                            state.tasks.any { taskData ->
                                (taskData as? TaskData.Data)?.thumbnailData?.thumbnail == null
                            },
                        )
                    }
                }

                setIconState(container, containerState)
                if (
                    containerState is TaskData &&
                        container.digitalWellBeingToast?.isDestroyed == false &&
                        container.task.titleDescription != null
                ) {
                    container.digitalWellBeingToast.initialize()
                }

                val dismissTaskViewOnClick: (View) -> Unit = {
                    recentsView?.dismissTaskView(container.taskView, /* removeTask= */ true)
                }
                setTaskDismissButtonState(
                    TaskUiStateMapper.toTaskDismissButtonState(
                        (type == TaskViewType.DESKTOP),
                        dismissTaskViewOnClick,
                    )
                )
            }
        }

    private fun setTaskDismissButtonState(state: TaskDismissButtonState) {
        when (state) {
            is TaskDismissButtonState.Enabled -> {
                taskDismissButtonEnabled = true
                taskDismissButton?.setOnClickListener(state.clickCloseListener)
            }
            is TaskDismissButtonState.Disabled -> {
                taskDismissButtonEnabled = false
                taskDismissButton?.setOnClickListener(null)
            }
        }
    }

    private fun updateThumbnailValidity(container: TaskContainer) {
        container.isThumbnailValid =
            viewModel.isThumbnailValid(
                thumbnail = container.thumbnailData,
                width = container.snapshotView.width,
                height = container.snapshotView.height,
                splitBounds = (this as? GroupedTaskView)?.splitBoundsConfig,
                stagePosition = container.stagePosition,
            )
        applyThumbnailSplashAlpha()
    }

    /**
     * Updates the thumbnail's transformation matrix and rotation state within a TaskContainer.
     *
     * This function is called to reposition the thumbnail in the following scenarios:
     * - When the TTV's size changes (onSizeChanged), and it's displaying a SnapshotSplash.
     * - When drawing a snapshot (drawSnapshot).
     *
     * @param container The TaskContainer holding the thumbnail to be updated.
     * @param width The desired width of the thumbnail's container.
     * @param height The desired height of the thumbnail's container.
     */
    private fun updateThumbnailMatrix(
        container: TaskContainer,
        width: Int,
        height: Int,
    ): ThumbnailPosition =
        traceSection("TaskView.updateThumbnailMatrix") {
            // TODO: b/428764855: Refactor `TaskViewModel` to include Split data.
            val splitPosition =
                when (container.stagePosition) {
                    SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT ->
                        SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT
                    SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT ->
                        SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT
                    else -> SplitScreenConstants.SPLIT_POSITION_UNDEFINED
                }
            val thumbnailPosition =
                viewModel.getThumbnailPosition(
                    container.thumbnailData,
                    width,
                    height,
                    isLayoutRtl,
                    (this as? GroupedTaskView)?.splitBoundsConfig,
                    splitPosition,
                    context.resources.displayMetrics.densityDpi,
                )
            container.updateThumbnailMatrix(thumbnailPosition.matrix)
            return thumbnailPosition
        }

    override fun onDetachedFromWindow() =
        traceSection("TaskView.onDetachedFromWindow") {
            super.onDetachedFromWindow()
            cancelJobs()
        }

    fun cancelJobs() {
        // The jobs are being cancelled in the background thread. So we make a copy of the
        // list to prevent cleaning a new job that might be added to this list during
        // onAttach or another moment in the lifecycle.
        val coroutineJobsToCancel = coroutineJobs.toList()
        coroutineJobs.clear()
        if (coroutineJobsToCancel.isEmpty()) return

        // TODO(b/391842220): This should ideally be handled in the completion block of the
        //  jobs above to be cancelled.
        taskContainers.forEach {
            it.setState(
                state = null,
                hasHeader = false,
                canShowAppTimer = false,
                clickCloseListener = null,
            )
            // Do not set icon to null if we are actively in split selection. The task
            // appears to have been offloaded as we remove it during split, but we still
            // need the icon to show over the split task.
            if (recentsView?.isSplitSelectionActive == false) {
                setIconState(it, null)
            }
        }

        coroutineScope.launch(lightweightBackgroundDispatcher) {
            traceSection("TaskView.onDetachedFromWindow.cancellingJobs") {
                coroutineJobsToCancel.forEach { it.cancel("TaskView detaching from window") }
            }
        }
    }

    /** Updates this task view to the given {@param task}. */
    open fun bind(
        singleTask: SingleTask,
        orientedState: RecentsOrientedState,
        taskOverlayFactory: TaskOverlayFactory,
    ) {
        this.groupTask = singleTask
        taskContainers =
            listOf(
                createTaskContainer(
                    singleTask.task,
                    R.id.task_content_view,
                    R.id.snapshot,
                    R.id.icon,
                    R.id.digital_wellbeing_toast,
                    STAGE_POSITION_UNDEFINED,
                    taskOverlayFactory,
                )
            )
        onBind(orientedState, taskOverlayFactory)
    }

    protected open fun onBind(
        orientedState: RecentsOrientedState,
        taskOverlayFactory: TaskOverlayFactory,
    ) =
        traceSection("TaskView.onBind") {
            this.orientedState = orientedState // Needed for dependencies
            this.taskOverlayFactory = taskOverlayFactory

            traceSection("TaskView.onBind.bindViewModel") {
                if (HgLog.VERBOSE) {
                    Log.d(TAG, "onBind $context ${orientedState.containerInterface}")
                }
                viewModel.bind(type, *taskIds)
            }

            hgApplyStackLook() // HyperGestures
            taskContainers.forEach { container ->
                container.bind()
                container.taskContentView.apply {
                    cornerRadius = thumbnailFullscreenParams.currentCornerRadius
                    taskCornerRadius = thumbnailFullscreenParams.taskCornerRadius
                    doOnSizeChange { width, height ->
                        updateThumbnailValidity(container)
                        val thumbnailPosition = updateThumbnailMatrix(container, width, height)
                        container.refreshOverlay(thumbnailPosition)
                    }
                }
            }
            setOrientationState(orientedState)
        }

    private fun applyThumbnailSplashAlpha() {
        val alpha = getSplashAlphaProgress()
        taskContainers.forEach { it.updateThumbnailSplashProgress(alpha) }
    }

    private fun getSplashAlphaProgress(): Float =
        when {
            splitSplashAlpha > 0f -> splitSplashAlpha
            shouldShowSplash() -> taskThumbnailSplashAlpha
            else -> 0f
        }

    internal fun shouldShowSplash(): Boolean = taskContainers.any { !it.isThumbnailValid }

    protected fun createTaskContainer(
        task: Task,
        @IdRes taskContentViewId: Int,
        @IdRes thumbnailViewId: Int,
        @IdRes iconViewId: Int,
        @IdRes digitalWellbeingBannerId: Int,
        @StagePosition stagePosition: Int,
        taskOverlayFactory: TaskOverlayFactory,
    ): TaskContainer =
        traceSection("TaskView.createTaskContainer") {
            val iconView = findViewById<IconAppChipView>(iconViewId)
            val taskContentView = findViewById<TaskContentView>(taskContentViewId)
            val snapshotView = taskContentView.findViewById<TaskThumbnailView>(thumbnailViewId)!!

            val digitalWellBeingToast: DigitalWellBeingToast? =
                if (enableRefactorDigitalWellbeingToast()) {
                    null
                } else {
                    findViewById(digitalWellbeingBannerId)!!
                }
            return TaskContainer(
                this,
                task,
                taskContentView,
                snapshotView,
                iconView,
                TransformingTouchDelegate(iconView),
                stagePosition,
                digitalWellBeingToast,
                taskOverlayFactory,
            )
        }

    fun containsMultipleTasks() = taskContainers.size > 1

    /**
     * Returns the TaskContainer corresponding to a given taskId, or null if the TaskView does not
     * contain a Task with that ID.
     */
    fun getTaskContainerById(taskId: Int) = taskContainers.firstOrNull { it.task.key.id == taskId }

    /** Check if given `taskId` is tracked in this view */
    fun containsTaskId(taskId: Int) = getTaskContainerById(taskId) != null

    open fun setOrientationState(orientationState: RecentsOrientedState) =
        traceSection("TaskView.setOrientationState") {
            this.orientedState = orientationState
            getTaskIcons().forEach { (icon, _) -> icon.setIconOrientation(orientationState) }
            setThumbnailOrientation(orientationState)
        }

    protected open fun setThumbnailOrientation(orientationState: RecentsOrientedState) {
        taskContainers.forEach {
            it.overlay.updateOrientationState(orientationState)
            it.digitalWellBeingToast?.initialize()
            it.onTaskViewDisplayConfigChanged()
        }
    }

    /**
     * Updates TaskView scaling and translation required to support variable width if enabled, while
     * ensuring TaskView fits into screen in fullscreen.
     */
    open fun updateTaskSize(lastComputedTaskSize: Rect, lastComputedGridTaskSize: Rect) {
        val taskWidth = lastComputedTaskSize.width()
        val taskHeight = lastComputedTaskSize.height()
        val nonGridScale: Float
        val boxTranslationY: Float
        val expectedWidth: Int
        val expectedHeight: Int
        if (container.deviceProfile.deviceProperties.isLargeScreen) {
            val boxWidth: Int
            val boxHeight: Int

            // Focused task and Desktop tasks should use focusTaskRatio that is associated
            // with the original orientation of the focused task.
            if (isLargeTile) {
                boxWidth = taskWidth
                boxHeight = taskHeight
            } else {
                // Otherwise task is in grid, and should use lastComputedGridTaskSize.
                boxWidth = lastComputedGridTaskSize.width()
                boxHeight = lastComputedGridTaskSize.height()
            }

            // Bound width/height to the box size.
            expectedWidth = boxWidth
            expectedHeight = boxHeight

            // Scale to to fit task Rect.
            nonGridScale = taskWidth / boxWidth.toFloat()

            // Align to top of task Rect.
            boxTranslationY = (expectedHeight - taskHeight) / 2.0f
        } else {
            nonGridScale = 1f
            boxTranslationY = 0f
            expectedWidth = taskWidth
            expectedHeight = taskHeight
        }
        this.nonGridScale = nonGridScale
        this.boxTranslationY = boxTranslationY
        updateLayoutParams<ViewGroup.LayoutParams> {
            width = expectedWidth
            height = expectedHeight
        }
        updateThumbnailSize()
    }

    protected open fun updateThumbnailSize() {
        taskContainers.forEach { it.digitalWellBeingToast?.setupLayout() }
    }

    /** Returns the thumbnail's bounds, optionally relative to the screen. */
    @JvmOverloads
    open fun getThumbnailBounds(bounds: Rect, relativeToDragLayer: Boolean = false) {
        bounds.setEmpty()
        taskContainers.forEach {
            val thumbnailBounds = Rect()
            if (relativeToDragLayer) {
                container.dragLayer.getDescendantRectRelativeToSelf(
                    it.taskContentView,
                    thumbnailBounds,
                )
            } else {
                thumbnailBounds.set(it.taskContentView)
            }
            bounds.union(thumbnailBounds)
        }
    }

    protected open fun setIconState(container: TaskContainer, state: TaskData?) =
        traceSection("TaskView.setIconState") {
            if (state is TaskData.Data) {
                setIcon(container.iconView, state.icon)
                container.iconView.setText(state.title)
            } else {
                setIcon(container.iconView, null)
                container.iconView.setText(null)
            }
        }

    protected fun setIcon(iconView: IconAppChipView, icon: Drawable?) {
        with(iconView) {
            if (icon != null && isHgHeader) {
                // HyperGestures: a header, not a menu button (HgTaskHeader); holding the card
                // opens the round action buttons. The chip's own snapshot, not the setting.
                setDrawable(icon)
                setOnClickListener(null)
                setOnLongClickListener(null)
                isClickable = false
                isLongClickable = false
            } else if (icon != null) {
                setDrawable(icon)
                setOnClickListener {
                    if (!confirmSecondSplitSelectApp()) {
                        showTaskMenu(this)
                    }
                }
                setOnLongClickListener {
                    requestDisallowInterceptTouchEvent(true)
                    showTaskMenu(this)
                }
            } else {
                setDrawable(null)
                setOnClickListener(null)
                setOnLongClickListener(null)
            }
        }
    }

    private fun onClick() {
        if (confirmSecondSplitSelectApp()) {
            Log.d(
                TAG,
                "${taskIds.contentToString()} - onClick - ignoring click: " +
                    "this task click selected the second app in split selection",
            )
            return
        }
        if (recentsView?.stateManager?.state?.isTaskViewInteractive != true) {
            Log.d(
                TAG,
                "${taskIds.contentToString()} - onClick - ignoring click: the state manager is " +
                    "not in an interactive state (state=${recentsView?.stateManager?.state})",
            )
            return
        }
        launchWithAnimation()
        container.statsLogManager
            .logger()
            .withItemInfo(itemInfo)
            .log(LauncherEvent.LAUNCHER_TASK_LAUNCH_TAP)
    }

    /** Launch of the current task (both live and inactive tasks) with an animation. */
    fun launchWithAnimation(): RunnableList? {
        Log.d(
            TAG,
            "${taskIds.contentToString()} - launchWithAnimation - initiating launch, " +
                "partial trace: ${getTrimmedStackTrace("TaskView.launchWithAnimation")}",
        )
        hgNoteSystemSplitLaunch() // HyperGestures
        // HyperGestures: a floating window's card restores it floating (HgFloatingCard); the
        // launch animation below would never end for it and recents would stop taking taps.
        val hgRecents = recentsView
        if (
            hgRecents != null && com.android.quickstep.HgFloatingCard.isFloating(hgWindowingModes())
        ) {
            com.android.quickstep.HgFloatingCard.launch(hgRecents, taskContainers[0].task)
            return RunnableList().also { it.executeAllAndDestroy() }
        }
        return if (isRunningTask && recentsView?.remoteTargetHandles != null) {
                launchAsLiveTile(recentsView?.remoteTargetHandles!!)
            } else {
                launchAsStaticTile()
            }
            ?.also {
                InteractionJankMonitorWrapper.begin(
                    /* v = */ this,
                    CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS,
                )
                it.add {
                    InteractionJankMonitorWrapper.end(CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS)
                    Log.d(
                        TAG,
                        "${taskIds.contentToString()} - launchWithAnimation - launchCompleted",
                    )
                }
            }
    }

    private fun launchAsLiveTile(remoteTargetHandles: Array<RemoteTargetHandle>): RunnableList? {
        val recentsView = recentsView ?: return null
        if (!isClickableAsLiveTile) {
            Log.e(
                TAG,
                "launchAsLiveTile - TaskView is not clickable as a live tile; returning to home: ${taskIds.contentToString()}",
            )
            return null
        }
        isClickableAsLiveTile = false
        val targets =
            if (remoteTargetHandles.isNotEmpty()) {
                if (remoteTargetHandles.size == 1) {
                    remoteTargetHandles[0].transformParams.targetSet
                } else {
                    val apps =
                        remoteTargetHandles.flatMap {
                            it.transformParams.targetSet.apps.asIterable()
                        }
                    val wallpapers =
                        remoteTargetHandles.flatMap {
                            it.transformParams.targetSet.wallpapers.asIterable()
                        }
                    RemoteAnimationTargets(
                        apps.toTypedArray(),
                        wallpapers.toTypedArray(),
                        remoteTargetHandles[0].transformParams.targetSet.nonApps,
                        remoteTargetHandles[0].transformParams.targetSet.targetMode,
                    )
                }
            } else {
                null
            }
        if (targets == null) {
            // If the recents animation is cancelled somehow between the parent if block and
            // here, try to launch the task as a non live tile task.
            val runnableList = launchAsStaticTile()
            if (runnableList == null) {
                Log.e(
                    TAG,
                    "launchAsLiveTile - Recents animation cancelled and cannot launch task as non-live tile; returning to home: ${taskIds.contentToString()}",
                )
            }
            isClickableAsLiveTile = true
            return runnableList
        }
        TestLogging.recordEvent(
            TestProtocol.SEQUENCE_MAIN,
            "composeRecentsLaunchAnimator",
            taskIds.contentToString(),
        )
        val runnableList = RunnableList()
        with(AnimatorSet()) {
            TaskViewUtils.composeRecentsLaunchAnimator(
                this,
                this@TaskView,
                targets.apps,
                targets.wallpapers,
                targets.nonApps,
                true, /* launcherClosing */
                recentsView.stateManager,
                recentsView,
                recentsView.depthController,
                /* transitionInfo= */ null,
                /* appearedTaskId= */ INVALID_TASK_ID,
            )
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: Animator) {
                        if (taskContainers.any { it.task.key.displayId != rootViewDisplayId }) {
                            launchAsStaticTile()
                        }
                        isClickableAsLiveTile = true
                        runEndCallback()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        runEndCallback()
                    }

                    private fun runEndCallback() {
                        runnableList.executeAllAndDestroy()
                    }
                }
            )
            start()
        }
        Log.d(TAG, "launchAsLiveTile - composeRecentsLaunchAnimator: ${taskIds.contentToString()}")
        recentsView.onTaskLaunchedInLiveTileMode()
        return runnableList
    }

    /**
     * Starts the task associated with this view and animates the startup.
     *
     * @return CompletionStage to indicate the animation completion or null if the launch failed.
     */
    open fun launchAsStaticTile(): RunnableList? {
        val firstTaskContainer = firstTaskContainer ?: return null
        TestLogging.recordEvent(
            TestProtocol.SEQUENCE_MAIN,
            "startActivityFromRecentsAsync",
            taskIds.contentToString(),
        )
        val opts =
            container.getActivityLaunchOptions(this, null).apply {
                options.launchDisplayId = displayId
                // On external displays the default windowing mode is not fullscreen. To make
                // sure fullscreen apps remain fullscreen we set the windowing mode explicitly.
                // HyperGestures: not for a task in HyperOS's split screen or a floating window,
                // which keep their mode (HgSystemSplitLaunch).
                options.launchWindowingMode =
                    com.android.quickstep.HgSystemSplitLaunch.launchWindowingMode(
                        com.android.quickstep.HgSystemSplitLaunch.keepsWindowingMode(
                            hgWindowingModes()
                        )
                    )
            }
        if (
            ActivityManagerWrapper.getInstance()
                .startActivityFromRecents(firstTaskContainer.task.key, opts.options)
        ) {
            Log.d(
                TAG,
                "launchAsStaticTile - startActivityFromRecents: ${taskIds.contentToString()}",
            )
            ActiveGestureLog.INSTANCE.trackEvent(
                ActiveGestureErrorDetector.GestureEvent.EXPECTING_TASK_APPEARED
            )
            val recentsView = recentsView ?: return null
            if (
                recentsView.runningTaskViewId != -1 &&
                    recentsView.mRecentsAnimationController != null
            ) {
                recentsView.onTaskLaunchedInLiveTileMode()

                // Return a fresh callback in the live tile case, so that it's not accidentally
                // triggered by QuickstepTransitionManager.AppLaunchAnimationRunner.
                return RunnableList().also { recentsView.addSideTaskLaunchCallback(it) }
            }
            // If the recents transition is running (ie. in live tile mode), then the start
            // of a new task will merge into the existing transition and it currently will
            // not be run independently, so we need to rely on the onTaskAppeared() call
            // for the new task to trigger the side launch callback to flush this runnable
            // list (which is usually flushed when the app launch animation finishes)
            recentsView.addSideTaskLaunchCallback(opts.onEndCallback)
            return opts.onEndCallback
        } else {
            notifyTaskLaunchFailed("launchAsStaticTile")
            return null
        }
    }

    /** Starts the task associated with this view without any animation */
    @JvmOverloads
    open fun launchWithoutAnimation(
        isQuickSwitch: Boolean = false,
        callback: (launched: Boolean) -> Unit,
    ) {
        val callbackWithLogging = { launchSuccess: Boolean ->
            Log.d(TAG, "launchWithoutAnimation - callback: launchSuccess: $launchSuccess")
            callback(launchSuccess)
        }
        val firstTaskContainer = firstTaskContainer ?: return
        hgNoteSystemSplitLaunch() // HyperGestures
        TestLogging.recordEvent(
            TestProtocol.SEQUENCE_MAIN,
            "startActivityFromRecentsAsync",
            taskIds.contentToString(),
        )
        val failureListener = TaskRemovedDuringLaunchListener(context.applicationContext)
        if (isQuickSwitch) {
            // We only listen for failures to launch in quickswitch because the during this
            // gesture launcher is in the background state, vs other launches which are in
            // the actual overview state
            failureListener.register(container, firstTaskContainer.task.key.id) {
                notifyTaskLaunchFailed("launchWithoutAnimation")
                recentsView?.let {
                    // Disable animations for now, as it is an edge case and the app usually
                    // covers launcher and also any state transition animation also gets
                    // clobbered by QuickstepTransitionManager.createWallpaperOpenAnimations
                    // when launcher shows again
                    it.startHome(false /* animated */, null /* onHomeAnimationComplete */)
                    // LauncherTaskbarUIController depends on the launcher state when
                    // checking whether to handle resume, but that can come in before
                    // startHome() changes the state, so force-refresh here to ensure the
                    // taskbar is updated
                    it.mContainerInterface.getTaskbarInteractor()?.refreshResumedState()
                }
            }
        }
        // Indicate success once the system has indicated that the transition has started
        val opts =
            ActivityOptions.makeCustomTaskAnimation(
                    context,
                    0,
                    0,
                    Executors.MAIN_EXECUTOR.handler,
                    { callbackWithLogging(true) },
                ) {
                    Log.d(TAG, "launchWithoutAnimation: launch animation finished")
                    failureListener.onTransitionFinished()
                }
                .apply {
                    launchDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
                    if (isQuickSwitch) {
                        setFreezeRecentTasksReordering()
                    }
                    // TODO(b/331754864): Update this to use TV.shouldShowSplash
                    disableStartingWindow = firstTaskContainer.shouldShowSplashView
                }
        Executors.UI_HELPER_EXECUTOR.execute {
            Log.d(
                TAG,
                "launchWithoutAnimation(isQuickSwitch: $isQuickSwitch) - " +
                    "startActivityFromRecents: ${taskIds.contentToString()}",
            )
            if (
                !ActivityManagerWrapper.getInstance()
                    .startActivityFromRecents(firstTaskContainer.task.key, opts)
            ) {
                Log.d(TAG, "launchWithoutAnimation - task launch failed")
                // If the call to start activity failed, then post the result immediately,
                // otherwise, wait for the animation start callback from the activity options
                // above
                Executors.MAIN_EXECUTOR.post {
                    notifyTaskLaunchFailed("launchTask")
                    callbackWithLogging(false)
                }
            }
        }
    }

    private fun notifyTaskLaunchFailed(launchMethod: String) {
        val sb =
            StringBuilder("$launchMethod - Failed to launch task: ${taskIds.contentToString()}\n")
        taskContainers.forEach {
            sb.append("(task=${it.task.key.baseIntent} userId=${it.task.key.userId})\n")
        }
        Log.w(TAG, sb.toString())
        Toast.makeText(context, R.string.activity_not_available, Toast.LENGTH_SHORT).show()
        // HyperGestures: no launch, so the next stop moves recents back as usual.
        (container as? com.android.quickstep.RecentsActivity)?.hgClearSplitPairLaunched()
    }

    /**
     * Returns `true` if user is already in split select mode and this tap was to choose the second
     * app. `false` otherwise
     */
    protected open fun confirmSecondSplitSelectApp(): Boolean {
        val index = getLastSelectedChildTaskIndex()
        if (index >= taskContainers.size) {
            return false
        }
        val container = taskContainers[index]
        val recentsView = recentsView ?: return false
        return recentsView.confirmSplitSelect(
            this,
            container.task,
            container.iconView.getDrawable(),
            container.snapshotView,
            container.thumbnail,
            /* intent */ null,
            /* user */ null,
            container.itemInfo,
        )
    }

    /**
     * Returns the task index of the last selected child task (0 or 1). If we contain multiple tasks
     * and this TaskView is used as part of split selection, the selected child task index will be
     * that of the remaining task.
     */
    protected open fun getLastSelectedChildTaskIndex() = 0

    private fun showTaskMenu(iconView: IconAppChipView): Boolean {
        val recentsView = recentsView ?: return false
        if (!recentsView.canLaunchFullscreenTask()) {
            // Don't show menu when selecting second split screen app
            return true
        }
        if (
            !container.deviceProfile.deviceProperties.isLargeScreen && !recentsView.isClearAllHidden
        ) {
            recentsView.snapToPage(recentsView.indexOfChild(this))
            return false
        }
        val menuContainer = getContainerForIconView(iconView)

        container.statsLogManager
            .logger()
            .withItemInfo(menuContainer?.itemInfo ?: itemInfo)
            .log(LauncherEvent.LAUNCHER_TASK_ICON_TAP_OR_LONGPRESS)
        return showTaskMenuWithContainer(menuContainer)
    }

    private fun closeTaskMenu(): Boolean {
        val floatingView: AbstractFloatingView? =
            AbstractFloatingView.getTopOpenViewWithType(
                container,
                AbstractFloatingView.TYPE_TASK_MENU,
            )
        if (floatingView?.isOpen == true) {
            floatingView.close(true)
            return true
        } else {
            return false
        }
    }

    private fun showTaskMenuWithContainer(menuContainer: TaskContainer?): Boolean {
        val recentsView = recentsView ?: return false
        // Disable hover on all TaskView's whilst menu is showing.
        recentsView.setTaskBorderEnabled(false)
        val iconView = menuContainer?.iconView ?: getTaskIcons().elementAt(0).first

        return if (iconView.status == AppChipStatus.Expanded) {
            closeTaskMenu()
        } else {
            val onShowAction = { recentsView.setTaskBorderEnabled(true) }
            val taskTarget =
                if (menuContainer != null) {
                    TaskMenuView.TaskTarget.FromTaskContainer(menuContainer)
                } else {
                    TaskMenuView.TaskTarget.FromTaskView(this)
                }
            TaskMenuView.showForTask(taskTarget, onShowAction)
        }
    }

    /**
     * Whether the taskview should take the touch event from parent. Events passed to children that
     * might require special handling.
     */
    open fun offerTouchToChildren(event: MotionEvent): Boolean {
        getTaskIcons().forEach { (icon, iconTouchDelegate) ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                computeAndSetIconTouchDelegate(icon, tempCoordinates, iconTouchDelegate)
                if (iconTouchDelegate.onTouchEvent(event)) {
                    return true
                }
            }
        }
        return false
    }

    private fun computeAndSetIconTouchDelegate(
        view: IconAppChipView,
        tempCenterCoordinates: FloatArray,
        transformingTouchDelegate: TransformingTouchDelegate,
    ) {
        val viewHalfWidth = view.width / 2f
        val viewHalfHeight = view.height / 2f
        Utilities.getDescendantCoordRelativeToAncestor(
            view,
            container.dragLayer,
            tempCenterCoordinates.apply {
                this[0] = viewHalfWidth
                this[1] = viewHalfHeight
            },
            false,
        )
        transformingTouchDelegate.setBounds(
            (tempCenterCoordinates[0] - viewHalfWidth).toInt(),
            (tempCenterCoordinates[1] - viewHalfHeight).toInt(),
            (tempCenterCoordinates[0] + viewHalfWidth).toInt(),
            (tempCenterCoordinates[1] + viewHalfHeight).toInt(),
        )
    }

    /**
     * Called to animate a smooth transition when going directly from an app into Overview (and vice
     * versa). Icons fade in, and DWB banners slide in with a "shift up" animation.
     */
    private fun onSettledProgressUpdated(settledProgress: Float) {
        getTaskIcons().forEach { (icon, _) -> icon.settledProgressAlpha = settledProgress }
        taskContainers.forEach {
            if (enableRefactorDigitalWellbeingToast()) {
                it.taskContentView.onParentAnimationProgress(settledProgress)
            } else {
                it.digitalWellBeingToast?.bannerOffsetPercentage = 1f - settledProgress
            }
        }
    }

    fun startIconFadeInOnGestureComplete() {
        iconFadeInOnGestureCompleteAnimator?.cancel()
        iconFadeInOnGestureCompleteAnimator =
            ObjectAnimator.ofFloat(this, SETTLED_PROGRESS_GESTURE, 1f).apply {
                duration = FADE_IN_ICON_DURATION
                interpolator = Interpolators.LINEAR
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            iconFadeInOnGestureCompleteAnimator = null
                        }
                    }
                )
                start()
            }
    }

    fun setIconVisibleForGesture(isVisible: Boolean) {
        iconFadeInOnGestureCompleteAnimator?.cancel()
        settledProgressGesture = if (isVisible) 1f else 0f
    }

    /** Set a color tint on the snapshot and supporting views. */
    open fun setColorTint(amount: Float, tintColor: Int) {
        // RecentsView's COLOR_TINT animates between 0 and 0.5f, we want to hide the app chip menu.
        val colorTintAlpha = Utilities.mapToRange(amount, 0f, 0.5f, 1f, 0f, Interpolators.LINEAR)
        getTaskIcons().forEach { (icon, _) -> icon.colorTintAlpha = colorTintAlpha }
        taskContainers.forEach {
            it.updateTintAmount(amount)
            it.digitalWellBeingToast?.setColorTint(tintColor, amount)
        }
    }

    /**
     * Sets visibility for the thumbnail and associated elements (DWB banners and action chips).
     * IconView is unaffected.
     *
     * @param taskId is only used when setting visibility to a non-[View.VISIBLE] value
     */
    open fun setThumbnailVisibility(isVisible: Boolean, taskId: Int) {
        taskContainers
            .filter { isVisible || it.task.key.id == taskId }
            .forEach { taskContainer ->
                sequenceOf(
                        taskContainer.taskContentView,
                        taskContainer.digitalWellBeingToast,
                        taskContainer.overlay.suggestView,
                    )
                    .filterNotNull()
                    .forEach {
                        // Prevent setting to INVISIBLE when already GONE to avoid onLayout pass.
                        // Use isInvisible to set visible/invisible. Using isVisible sets GONE if
                        // false.
                        if (it.isInvisible != !isVisible && !it.isGone) {
                            it.isInvisible = !isVisible
                        }
                    }
            }
    }

    protected fun getScrollAdjustment(gridEnabled: Boolean) =
        if (gridEnabled) gridTranslationX else nonGridTranslationX

    fun getOffsetAdjustment(gridEnabled: Boolean) = getScrollAdjustment(gridEnabled)

    fun getSizeAdjustment(fullscreenEnabled: Boolean) = if (fullscreenEnabled) nonGridScale else 1f

    private fun applyScale() {
        val scale =
            persistentScale *
                dismissScale *
                animateToIconScale *
                Utilities.mapRange(modalness, 1f, modalScale) *
                hgStackScale // HyperGestures: 1 outside the stack layout
        scaleX = scale
        scaleY = scale
        updateFullscreenParams()
    }

    private fun applyTranslationX() {
        hgUpdateStack() // HyperGestures
        translationX =
            dismissTranslationX +
                taskOffsetTranslationX +
                taskResistanceTranslationX +
                splitSelectTranslationX +
                gridEndTranslationX +
                persistentTranslationX +
                animateToIconTranslationX +
                hgActionsTranslationX +
                hgStackTranslationX // HyperGestures: 0 outside the stack layout
    }

    /**
     * HyperGestures: RecentsView's stack layout pass (on scroll and layout): [stack], null in the
     * horizontal layout, the card's natural screen offset from the front card's place, and
     * whether a newer card lies on top of it ([hasNewer]: its header is clipped at its edge).
     */
    fun hgSetStack(stack: HgStackLayout?, offset: Float, hasNewer: Boolean) {
        if (stack === hgStack && offset == hgStackOffset && hasNewer == hgStackHasNewer &&
            hgVertical == null) {
            return
        }
        hgStack = stack
        hgStackOffset = offset
        hgStackHasNewer = hasNewer
        hgSetVerticalLayout(null)
        applyTranslationX()
    }

    /**
     * HyperGestures: RecentsView's vertical layout pass (on scroll and layout): [layout], the
     * card's natural screen offset from the task size's place in the strip, the grid's scroll and
     * the one it is drawn at ([shownScrollY], frozen during the swipe up); [force] when the
     * layout's task count changed.
     */
    fun hgSetVertical(
        layout: HgVerticalLayout,
        offset: Float,
        scrollY: Float,
        shownScrollY: Float,
        force: Boolean,
    ) {
        if (!force && layout === hgVertical && offset == hgStackOffset &&
            scrollY == hgVerticalScrollY && shownScrollY == hgVerticalShownScrollY &&
            hgStack == null) {
            return
        }
        hgStack = null
        hgStackOffset = offset
        hgVerticalScrollY = scrollY
        hgVerticalShownScrollY = shownScrollY
        hgSetVerticalLayout(layout)
        applyTranslationX()
    }

    private fun hgSetVerticalLayout(layout: HgVerticalLayout?) {
        if (layout === hgVertical) return
        val wasVertical = hgVertical != null
        hgVertical = layout
        // The dismiss translation moves the card sideways in the vertical layout, down elsewhere.
        if (wasVertical != (layout != null)) hgApplyTranslationY()
    }

    // HyperGestures: the card's screen x offset in the strip, the stack and vertical layouts'
    // depth: its natural offset plus the dismiss, reflow and gesture translations.
    private val hgStripOffset: Float
        get() =
            hgStackOffset +
                dismissTranslationX +
                taskResistanceTranslationX +
                splitSelectTranslationX +
                persistentTranslationX

    // HyperGestures: the vertical layout's centring of this card (a lone card's, see
    // HgVerticalLayout.centring); a leaving card keeps the one it started to leave with.
    private fun hgCentring(vertical: HgVerticalLayout): Float =
        if (isBeingDismissed) hgLeavingCentring else vertical.centring(false)

    /**
     * HyperGestures: the vertical layout's way (px, [out] x and y) from where this reflowing card
     * is drawn to where its reflow leaves it, the previous slot: the reflow's page move
     * [stripTranslation] (RecentsView's mTaskViewsDismissPrimaryTranslations) taken along the
     * slot path (HgVerticalLayout.reflowRemaining), for the dismiss swipe's hit test
     * (TaskViewDismissTouchController). False outside the vertical layout.
     */
    fun hgReflowRemaining(stripTranslation: Float, out: FloatArray): Boolean {
        val vertical = hgVertical ?: return false
        val depth = vertical.depth(hgStripOffset - dismissTranslationX)
        vertical.reflowRemaining(
            vertical.slot(depth, hgVerticalScrollY),
            vertical.reflow(dismissTranslationX),
            vertical.reflow(stripTranslation),
            hgCentring(vertical),
            out,
        )
        return true
    }

    /**
     * HyperGestures: the card's place, size, dim and header alpha in the stack layout, from its
     * depth. The depth counts the translations the dismiss, reflow and gesture animations give
     * the card, so those move it through the deck; the hold buttons' slide aside and the
     * launch-to-icon move stay plain moves.
     */
    private fun hgUpdateStack() {
        val stack = hgStack
        val vertical = hgVertical
        val offset = hgStripOffset
        if (vertical != null) {
            // The vertical layout's grid slot, from the card's place in the strip; the dismiss
            // translation (down elsewhere) moves the card sideways, to hgDismissSide. The page
            // offsets (taskOffsetTranslationX: launch, gesture attach) stay plain sideways moves,
            // as the live tile's simulator applies them. The reflow's page move after a dismiss
            // (dismissTranslationX) is the way to the previous slot, taken on the straight line
            // between the two slots (HgVerticalLayout.slotX), so a spring's overshoot does not
            // turn the card round a row's end.
            val reflow = vertical.reflow(dismissTranslationX)
            val depth = vertical.depth(offset - dismissTranslationX)
            val scrollY = hgVerticalScrollY
            val slot = vertical.slot(depth, scrollY) - reflow // where it is drawn
            if (dismissTranslationY == 0f && !hgDismissDragging) {
                hgDismissSide = vertical.dismissSide(slot)
            }
            // Scrolled above the top edge (as drawn): the header fades out, the card a little.
            hgSetStackValues(
                vertical.translationX(
                    depth,
                    scrollY,
                    reflow,
                    hgCentring(vertical),
                ) - hgDismissSide * dismissTranslationY,
                vertical.translationY(depth, scrollY, hgVerticalShownScrollY, reflow),
                1f,
                0f,
                vertical.headerAlpha(slot, hgVerticalShownScrollY),
                vertical.cardAlpha(slot, hgVerticalShownScrollY),
            )
            hgSetHeaderClip(-HG_NO_CLIP, HG_NO_CLIP)
            hgSetA11yHidden(false)
            return
        }
        // HyperGestures: TalkBack reads only the stack's cards that can be seen.
        hgSetA11yHidden(stack != null &&
            !HgStackLayout.shownToAccessibility(stack.depth(offset + taskOffsetTranslationX)))
        if (stack == null) {
            if (hgStackTranslationX != 0f || hgStackTranslationY != 0f || hgStackScale != 1f ||
                hgStackDim != 0f || hgStackHeaderAlpha != 1f || hgScrollAlpha != 1f) {
                hgSetStackValues(0f, 0f, 1f, 0f, 1f, 1f)
            }
            hgSetHeaderClip(-HG_NO_CLIP, HG_NO_CLIP)
            return
        }
        val depth = stack.depth(offset + taskOffsetTranslationX)
        // Every card keeps its header (MIUI), clipped at the newer card's edge (below).
        hgSetStackValues(
            stack.translationX(depth),
            0f,
            HgStackLayout.scale(depth),
            HgStackLayout.dim(depth),
            1f,
            1f,
        )
        if (!hgStackHasNewer) {
            hgSetHeaderClip(-HG_NO_CLIP, HG_NO_CLIP)
        } else if (stack.isOlderToLeft) {
            hgSetHeaderClip(-HG_NO_CLIP, stack.headerStrip(depth))
        } else {
            hgSetHeaderClip(width - stack.headerStrip(depth), HG_NO_CLIP)
        }
    }

    /**
     * HyperGestures: the x range ([start], [end], this view's px) the header draws in; applied
     * to the headers only when it changed (hgApplyHeaderClip).
     */
    private fun hgSetHeaderClip(start: Float, end: Float) {
        if (start == hgHeaderClipStart && end == hgHeaderClipEnd) return
        hgHeaderClipStart = start
        hgHeaderClipEnd = end
        hgApplyHeaderClip()
    }

    /**
     * HyperGestures: in the stack layout an older card's header shows only within its visible
     * strip, never where the newer card on top of it is (hgSetHeaderClip). The header is drawn
     * with its card, so the newer card covers the rest of it anyway; the clip keeps its name from
     * showing above the newer card, in that card's header row. Set as each header's clip bounds,
     * in its own px: a render property, so a scroll frame re-records nothing. Also when a header
     * moves within the card (layout, its lift and shift: IconAppChipView.hgApplyLift).
     */
    fun hgApplyHeaderClip() {
        if (!::taskContainers.isInitialized) return
        val unclipped = hgHeaderClipStart <= -HG_NO_CLIP && hgHeaderClipEnd >= HG_NO_CLIP
        for (i in taskContainers.indices) {
            val chip = taskContainers[i].iconView
            if (unclipped) {
                if (chip.getClipBounds(hgClipRect)) chip.clipBounds = null // no copy
                continue
            }
            // This view's x to the chip's own: undo its move and its scale about its pivot.
            val origin = chip.left + chip.translationX + chip.pivotX
            val scale = if (chip.scaleX != 0f) chip.scaleX else 1f
            val start =
                if (hgHeaderClipStart <= -HG_NO_CLIP) -HG_NO_CLIP_PX
                else Math.floor((chip.pivotX + (hgHeaderClipStart - origin) / scale).toDouble())
                    .toInt()
            val end =
                if (hgHeaderClipEnd >= HG_NO_CLIP) HG_NO_CLIP_PX
                else Math.ceil((chip.pivotX + (hgHeaderClipEnd - origin) / scale).toDouble())
                    .toInt()
            hgClipRect.set(start, -HG_NO_CLIP_PX, Math.max(start, end), HG_NO_CLIP_PX)
            chip.clipBounds = hgClipRect // copied by the view
        }
    }

    /** HyperGestures: hides the card from accessibility (stack cards nobody can see). */
    private fun hgSetA11yHidden(hidden: Boolean) {
        if (hidden == hgA11yHidden) return
        hgA11yHidden = hidden
        importantForAccessibility =
            if (hidden) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private var hgA11yHidden = false

    private fun hgSetStackValues(
        tx: Float,
        ty: Float,
        scale: Float,
        dim: Float,
        headerAlpha: Float,
        alpha: Float,
    ) {
        val moved = tx != hgStackTranslationX || ty != hgStackTranslationY ||
            scale != hgStackScale || alpha != hgScrollAlpha
        hgStackTranslationX = tx
        if (ty != hgStackTranslationY) {
            hgStackTranslationY = ty
            hgApplyTranslationY()
        }
        if (scale != hgStackScale) {
            hgStackScale = scale
            applyScale()
        }
        if (alpha != hgScrollAlpha) hgScrollAlpha = alpha
        // Indexed loops: this runs on every scroll frame, without allocating. Unbound: kept for
        // onBind (hgApplyStackLook), which gives the views these values.
        if (dim != hgStackDim || headerAlpha != hgStackHeaderAlpha) {
            hgStackDim = dim
            hgStackHeaderAlpha = headerAlpha
            if (::taskContainers.isInitialized) hgApplyStackLook()
        }
        if (moved) hgMirrorStackToLiveTile()
    }

    /**
     * HyperGestures: the stack dim and header alpha on this card's thumbnails and headers; also
     * at every binding, so a card laid out before it was bound (or bound anew) is not left with
     * its views' old values while the cached ones say nothing changed.
     */
    private fun hgApplyStackLook() {
        for (i in taskContainers.indices) {
            taskContainers[i].snapshotView.hgStackDim = hgStackDim
            taskContainers[i].iconView.hgStackAlpha = hgStackHeaderAlpha
        }
    }

    /**
     * HyperGestures: the stack layout at rest (RecentsView.hgSettleStack: no drag, scroll, gesture
     * or dismiss running). The reflow's and the resistance's x translations are depth terms and
     * belong to animations that have ended: back to 0. The hold buttons' slide aside stays only
     * while they are open ([menuOpen]). The composed translation and scale are applied again, so
     * an animator that wrote the view's translationX directly (AOSP's launch parallax) cannot
     * leave the card shifted. Returns the card's terms before, for the settle log line, if
     * [describe] (verbose logging, the front card only), else null.
     */
    fun hgSettleStack(menuOpen: Boolean, describe: Boolean): String? {
        val terms =
            if (!describe) null
            else
                "offset=$hgStackOffset dismissX=$dismissTranslationX " +
                    "taskOffsetX=$taskOffsetTranslationX " +
                    "resistanceX=$taskResistanceTranslationX " +
                    "actionsX=$hgActionsTranslationX stackX=$hgStackTranslationX " +
                    "translationX=$translationX"
        if (!isBeingDismissed && !hgDismissDragging && dismissTranslationX != 0f) {
            dismissTranslationX = 0f
        }
        if (taskResistanceTranslationX != 0f) taskResistanceTranslationX = 0f
        if (!menuOpen && hgActionsTranslationX != 0f) hgActionsTranslationX = 0f
        applyTranslationX()
        applyScale()
        return terms
    }

    /**
     * HyperGestures: the running app's live tile is its own surface below recents (HgLiveTile):
     * give its simulator the card's stack scale and offset too: the running card is the newest,
     * in front or on the newer side, sliding out as an older card comes to the front. Only the
     * values: RecentsView.dispatchDraw redraws the live tile in every frame it draws, so a change
     * asks for that frame (invalidate; a card's own property change alone does not redraw
     * RecentsView) instead of a surface transaction per setter, several a frame while it moves.
     */
    fun hgMirrorStackToLiveTile() {
        val recentsView = recentsView ?: return
        if (!recentsView.enableDrawingLiveTile || !isRunningTask) return
        var changed = false
        recentsView.runActionOnRemoteHandles { handle ->
            if (hgCopyStackTo(handle.taskViewSimulator, 1f)) changed = true
        }
        if (changed) recentsView.invalidate()
    }

    /**
     * HyperGestures: gives [simulator] this card's stack scale and stack or grid offsets, times
     * [fraction]: 1 for the live tile; the swipe up's own simulator (AbsSwipeUpHandler) gets its
     * progress toward recents, so the window leaves full screen in place and lands on the card.
     * Returns whether anything changed.
     */
    fun hgCopyStackTo(
        simulator: com.android.quickstep.util.TaskViewSimulator,
        fraction: Float,
    ): Boolean {
        val vertical = hgVertical != null
        val scale = 1f + (hgStackScale - 1f) * fraction
        val tx = hgStackTranslationX * fraction
        val ty = hgStackTranslationY * fraction
        val alpha = 1f + (hgScrollAlpha - 1f) * fraction
        if (simulator.hgStackScale.value == scale &&
            simulator.hgStackTranslationX.value == tx &&
            simulator.hgStackTranslationY.value == ty &&
            simulator.hgAlpha.value == alpha &&
            simulator.hgVertical == vertical) {
            return false
        }
        simulator.hgStackScale.value = scale
        simulator.hgStackTranslationX.value = tx
        simulator.hgStackTranslationY.value = ty
        simulator.hgAlpha.value = alpha
        simulator.hgVertical = vertical
        return true
    }

    /**
     * HyperGestures: [rect], in [ancestor]'s coordinates (the drag layer), moved to where it
     * would be without the card's stack scale and offset; unchanged outside the stack layout.
     * The launch animation's pivot is computed for the natural place it ends from.
     */
    fun hgUnmapStack(rect: Rect, ancestor: View) {
        val scale = hgStackScale
        if (scale == 1f && hgStackTranslationX == 0f && hgStackTranslationY == 0f) return
        val pivot = floatArrayOf(pivotX, pivotY)
        Utilities.getDescendantCoordRelativeToAncestor(this, ancestor, pivot, false)
        val naturalPivotX = pivot[0] - hgStackTranslationX
        val naturalPivotY = pivot[1] - hgStackTranslationY
        rect.set(
            Math.round(naturalPivotX + (rect.left - pivot[0]) / scale),
            Math.round(naturalPivotY + (rect.top - pivot[1]) / scale),
            Math.round(naturalPivotX + (rect.right - pivot[0]) / scale),
            Math.round(naturalPivotY + (rect.bottom - pivot[1]) / scale),
        )
    }

    private fun applyTranslationY() {
        // HyperGestures: in the vertical layout the dismiss translation moves the card sideways:
        // recompute its grid offsets (which apply y as well).
        if (hgVertical != null) applyTranslationX()
        hgApplyTranslationY()
    }

    private fun hgApplyTranslationY() {
        translationY =
            (if (hgVertical != null) 0f else dismissTranslationY) + // HyperGestures: sideways
                taskOffsetTranslationY +
                taskResistanceTranslationY +
                splitSelectTranslationY +
                persistentTranslationY +
                animateToIconTranslationY +
                hgStackTranslationY // HyperGestures: 0 outside the vertical layout
    }

    private fun onGridProgressChanged() {
        applyTranslationX()
        applyTranslationY()
        applyScale()
    }

    protected open fun onFullscreenProgressChanged(fullscreenProgress: Float) {
        taskContainers.forEach { it.overlay.setFullscreenProgress(fullscreenProgress) }
        updateSettledProgressFullscreen(fullscreenProgress)
        updateFullscreenParams()
    }

    protected fun updateSettledProgressFullscreen(fullscreenProgress: Float) {
        settledProgressFullscreen =
            SETTLED_PROGRESS_FAST_OUT_INTERPOLATOR.getInterpolation(1 - fullscreenProgress)
    }

    protected open fun updateFullscreenParams() {
        updateFullscreenParams(thumbnailFullscreenParams)
        taskContainers.forEach {
            it.taskContentView.cornerRadius = thumbnailFullscreenParams.currentCornerRadius
            it.overlay.setFullscreenParams(thumbnailFullscreenParams)
        }
    }

    protected fun updateFullscreenParams(fullscreenParams: FullscreenDrawParams) {
        recentsView?.let { fullscreenParams.setProgress(fullscreenProgress, it.scaleX, scaleX) }
    }

    private fun onModalnessUpdated(modalness: Float) {
        getTaskIcons().forEach { (icon, _) -> icon.modalAlpha = 1f - modalness }
        taskContainers.forEach {
            if (enableRefactorDigitalWellbeingToast()) {
                it.taskContentView.onParentAnimationProgress(1f - modalness)
            } else {
                it.digitalWellBeingToast?.bannerOffsetPercentage = modalness
            }
        }
        modalAlpha = if (isSelectedTask) 1f else (1f - modalness)
        applyScale()
    }

    fun resetPersistentViewTransforms() {
        nonGridTranslationX = 0f
        gridTranslationX = 0f
        gridTranslationY = 0f
        boxTranslationY = 0f
        taskContainers.forEach {
            it.snapshotView.translationX = 0f
            it.snapshotView.translationY = 0f
        }
        resetViewTransforms()
    }

    fun resetViewTransforms() {
        // Dismiss translation shouldn't reset if actively being dragged
        if (!isBeingDraggedForDismissal) {
            secondaryDismissTranslationProperty.setValue(this, 0f)
        }
        primaryDismissTranslationProperty.setValue(this, 0f)

        // fullscreenTranslation and accumulatedTranslation should not be reset, as
        // resetViewTransforms is called during QuickSwitch scrolling.
        taskOffsetTranslationX = 0f
        taskResistanceTranslationX = 0f
        splitSelectTranslationX = 0f
        gridEndTranslationX = 0f
        taskOffsetTranslationY = 0f
        taskResistanceTranslationY = 0f
        if (recentsView?.isSplitSelectionActive != true) {
            splitSelectTranslationY = 0f
        }
        dismissScale = 1f
        translationZ = 0f
        setIconVisibleForGesture(true)
        settledProgressDismiss = 1f
        setColorTint(0f, 0)
        animateToIconAlpha = 1f
        animateToIconScale = 1f
        animateToIconTranslationX = 0f
        animateToIconTranslationY = 0f
    }

    private fun getGridTrans(endTranslation: Float) =
        Utilities.mapRange(gridProgress, 0f, endTranslation)

    private fun getNonGridTrans(endTranslation: Float) =
        endTranslation - getGridTrans(endTranslation)

    private fun MotionEvent.isWithinThumbnailBounds(): Boolean {
        return thumbnailBounds.contains(x.toInt(), y.toInt())
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        (if (isLayoutRtl) taskContainers.reversed() else taskContainers).forEach {
            it.addChildForAccessibility(outChildren)
        }
    }

    protected open fun getContainerForIconView(appChip: IconAppChipView) =
        taskContainers.firstOrNull { it.iconView === appChip }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        getTaskIcons().forEach { (icon, _) -> icon.contentAlpha = alpha }
    }

    companion object {
        private const val TAG = "TaskView"
        /** HyperGestures: hgHeaderClipStart / End when the header is not clipped. */
        const val HG_NO_CLIP = 1e6f
        /** HyperGestures: the header's clip bounds' open side, px. */
        private const val HG_NO_CLIP_PX = 1_000_000

        private enum class Alpha {
            Stable,
            Attach,
            Split,
            Modal,
            AnimateToIcon,
            HgScroll, // HyperGestures: hgScrollAlpha
        }

        private enum class SettledProgress {
            Fullscreen,
            Gesture,
            Dismiss,
        }

        const val FADE_IN_ICON_DURATION: Long = 120
        private const val DIM_ANIM_DURATION: Long = 700
        private const val SETTLE_TRANSITION_THRESHOLD =
            FADE_IN_ICON_DURATION.toFloat() / DIM_ANIM_DURATION
        val SETTLED_PROGRESS_FAST_OUT_INTERPOLATOR =
            Interpolators.clampToProgress(
                Interpolators.FAST_OUT_SLOW_IN,
                1f - SETTLE_TRANSITION_THRESHOLD,
                1f,
            )!!

        private val SETTLED_PROGRESS: FloatProperty<TaskView> =
            KFloatProperty(TaskView::settledProgress)

        private val SETTLED_PROGRESS_GESTURE: FloatProperty<TaskView> =
            KFloatProperty(TaskView::settledProgressGesture)

        private val SPLIT_SELECT_TRANSLATION_X: FloatProperty<TaskView> =
            KFloatProperty(TaskView::splitSelectTranslationX)

        private val SPLIT_SELECT_TRANSLATION_Y: FloatProperty<TaskView> =
            KFloatProperty(TaskView::splitSelectTranslationY)

        private val DISMISS_TRANSLATION_X: FloatProperty<TaskView> =
            KFloatProperty(TaskView::dismissTranslationX)

        private val DISMISS_TRANSLATION_Y: FloatProperty<TaskView> =
            KFloatProperty(TaskView::dismissTranslationY)

        private val TASK_OFFSET_TRANSLATION_X: FloatProperty<TaskView> =
            KFloatProperty(TaskView::taskOffsetTranslationX)

        private val TASK_OFFSET_TRANSLATION_Y: FloatProperty<TaskView> =
            KFloatProperty(TaskView::taskOffsetTranslationY)

        private val TASK_RESISTANCE_TRANSLATION_X: FloatProperty<TaskView> =
            KFloatProperty(TaskView::taskResistanceTranslationX)

        private val TASK_RESISTANCE_TRANSLATION_Y: FloatProperty<TaskView> =
            KFloatProperty(TaskView::taskResistanceTranslationY)

        @JvmField
        val GRID_END_TRANSLATION_X: FloatProperty<TaskView> =
            KFloatProperty(TaskView::gridEndTranslationX)

        @JvmField
        val DISMISS_SCALE: FloatProperty<TaskView> = KFloatProperty(TaskView::dismissScale)

        @JvmField val SPLIT_ALPHA: FloatProperty<TaskView> = KFloatProperty(TaskView::splitAlpha)
    }
}
