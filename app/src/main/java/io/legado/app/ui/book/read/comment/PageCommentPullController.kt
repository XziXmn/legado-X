package io.legado.app.ui.book.read.comment

import kotlin.math.abs
import kotlin.math.min

/** Pure state machine used by [io.legado.app.ui.book.read.page.ReadView]. */
class PageCommentPullController(
    private val thresholdPx: Float,
    private val verticalDominance: Float = 1.15f,
) {

    enum class State {
        IDLE,
        TRACKING,
        PULLING,
        ARMED,
        SETTLING,
    }

    data class MoveResult(
        val claimed: Boolean,
        val claimedNow: Boolean,
        val offset: Float,
        val feedback: Boolean,
    )

    data class EndResult(
        val claimed: Boolean,
        val open: Boolean,
    )

    var state = State.IDLE
        private set

    private var startX = 0f
    private var startY = 0f
    private var feedbackSent = false

    val isClaimed: Boolean
        get() = state == State.PULLING || state == State.ARMED || state == State.SETTLING

    /** True while waiting to decide between page-comment pull and page-turn. */
    val isTracking: Boolean
        get() = state == State.TRACKING

    fun start(x: Float, y: Float, enabled: Boolean) {
        reset()
        if (!enabled) return
        startX = x
        startY = y
        state = State.TRACKING
    }

    fun move(
        x: Float,
        y: Float,
        touchSlop: Float,
        maxOffset: Float,
    ): MoveResult {
        if (state == State.IDLE || state == State.SETTLING) return NONE
        val deltaX = x - startX
        val deltaY = y - startY
        var claimedNow = false
        if (state == State.TRACKING) {
            if (abs(deltaX) <= touchSlop && abs(deltaY) <= touchSlop) return NONE
            if (deltaY <= 0f || deltaY <= abs(deltaX) * verticalDominance) {
                reset()
                return NONE
            }
            state = State.PULLING
            claimedNow = true
        }

        val offset = min(maxOffset.coerceAtLeast(0f), deltaY.coerceAtLeast(0f) / DAMPING)
        val armed = deltaY >= thresholdPx
        var feedback = false
        if (armed) {
            state = State.ARMED
            if (!feedbackSent) {
                feedbackSent = true
                feedback = true
            }
        } else {
            state = State.PULLING
        }
        return MoveResult(true, claimedNow, offset, feedback)
    }

    fun end(cancelled: Boolean): EndResult {
        val claimed = isClaimed
        val open = !cancelled && state == State.ARMED
        state = if (claimed) State.SETTLING else State.IDLE
        return EndResult(claimed, open)
    }

    fun finishSettling() {
        reset()
    }

    fun reset() {
        state = State.IDLE
        startX = 0f
        startY = 0f
        feedbackSent = false
    }

    companion object {
        private const val DAMPING = 3f
        private val NONE = MoveResult(false, false, 0f, false)
    }
}
