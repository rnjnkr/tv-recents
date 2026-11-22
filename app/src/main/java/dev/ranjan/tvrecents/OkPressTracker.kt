package dev.ranjan.tvrecents

/** Actions produced by OK on an app card. */
interface OkActions {
    fun onShortPress()
    fun onLongPress()
}

/** Owns an OK press and consumes trailing events after a hold or reset. */
class OkPressTracker(private val scheduler: Scheduler, private val actions: OkActions) {

    private class Press(val downTime: Long) {
        var cancelTimer: (() -> Unit)? = null
    }

    private var press: Press? = null

    /** Returns true if consumed. Action values match KeyEvent.ACTION_DOWN and ACTION_UP. */
    fun onKey(
        action: Int,
        repeatCount: Int,
        downTime: Long,
        eventTime: Long,
        canceled: Boolean,
    ): Boolean {
        val current = press?.takeIf { it.downTime == downTime }
        return when (action) {
            ACTION_DOWN -> {
                if (repeatCount == 0) {
                    reset()
                    val started = Press(downTime)
                    press = started
                    started.cancelTimer = scheduler(downTime + HOLD_MS) {
                        if (press === started) hold()
                    }
                } else if (current != null && eventTime - downTime >= HOLD_MS) {
                    hold()
                }
                true
            }
            ACTION_UP -> {
                if (current != null) {
                    reset()
                    if (!canceled) {
                        if (eventTime - downTime >= HOLD_MS) actions.onLongPress()
                        else actions.onShortPress()
                    }
                }
                true
            }
            else -> false
        }
    }

    fun reset() {
        val previous = press
        press = null
        previous?.cancelTimer?.invoke()
    }

    private fun hold() {
        reset()
        actions.onLongPress()
    }

    companion object {
        const val HOLD_MS = 500L
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
    }
}
