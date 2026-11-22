package dev.ranjan.tvrecents

/** Actions produced by a Home press. */
interface HomeActions {
    fun goHome(): Boolean
    fun showSwitcher()
    fun dismissSwitcher()
    fun isSwitcherShowing(): Boolean
}

/** Runs block at an absolute SystemClock.uptimeMillis() time and returns a cancel function. */
typealias Scheduler = (atUptimeMs: Long, block: () -> Unit) -> () -> Unit

/** Owns a Home press from initial down through release and emits at most one action. */
class HomeKeyHandler(private val scheduler: Scheduler, private val actions: HomeActions) {

    private class Press(val downTime: Long, val suppressed: Boolean) {
        var held = false
        var cancelTimer: (() -> Unit)? = null
    }

    private var press: Press? = null
    private var lastHoldUpTime: Long? = null

    /** Returns true if consumed. Action values match KeyEvent.ACTION_DOWN and ACTION_UP. */
    fun onHomeKey(
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
                    clearPress()
                    val suppressed = lastHoldUpTime?.let { downTime - it in 0L until PHANTOM_TAP_MS } == true
                    val started = Press(downTime, suppressed)
                    press = started
                    if (!suppressed) {
                        started.cancelTimer = scheduler(downTime + HOLD_MS) {
                            if (press === started) hold(started)
                        }
                    }
                } else if (current != null && eventTime - downTime >= HOLD_MS) {
                    hold(current)
                }
                true
            }
            ACTION_UP -> {
                if (current == null) return false
                clearPress()
                when {
                    current.suppressed -> Unit
                    current.held -> lastHoldUpTime = eventTime
                    canceled -> Unit
                    eventTime - downTime >= HOLD_MS -> {
                        lastHoldUpTime = eventTime
                        actions.showSwitcher()
                    }
                    else -> {
                        runCatching { if (actions.isSwitcherShowing()) actions.dismissSwitcher() }
                        actions.goHome()
                    }
                }
                true
            }
            else -> false
        }
    }

    fun reset() {
        clearPress()
        lastHoldUpTime = null
    }

    private fun clearPress() {
        val previous = press
        press = null
        previous?.cancelTimer?.invoke()
    }

    private fun hold(current: Press) {
        if (current.held || current.suppressed) return
        current.held = true
        current.cancelTimer?.invoke()
        current.cancelTimer = null
        actions.showSwitcher()
    }

    companion object {
        const val HOLD_MS = 500L
        private const val PHANTOM_TAP_MS = 150L
        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
    }
}
