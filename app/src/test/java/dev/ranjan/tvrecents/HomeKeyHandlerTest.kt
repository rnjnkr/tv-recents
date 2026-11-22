package dev.ranjan.tvrecents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises Home presses with a scheduler whose callbacks can be delivered out of order. */
class HomeKeyHandlerTest {
    private val scheduler = FakeScheduler()
    private val actions = FakeActions()
    private val handler = HomeKeyHandler(scheduler::schedule, actions)

    @Test
    fun down_schedulesAtAbsoluteDeadline() {
        assertTrue(down(downTime = 1000, eventTime = 1200))
        assertEquals(1500L, scheduler.tasks.single().atUptimeMs)
    }

    @Test
    fun tap_goesHomeOnce() {
        assertTrue(down())
        assertTrue(up(eventTime = 120))
        assertEquals(listOf("goHome"), actions.calls)
    }

    @Test
    fun hold_timerFires_showsOnce() {
        assertTrue(down())
        scheduler.tasks.single().block()
        assertTrue(up(eventTime = 800))
        assertEquals(listOf("show"), actions.calls)
    }

    @Test
    fun hold_upAtThreshold_beforeTimer_shows() {
        down()
        val timer = scheduler.tasks.single()
        assertTrue(up(eventTime = 500))
        assertEquals(listOf("show"), actions.calls)
        assertTrue(timer.cancelled)
        timer.block()
        assertEquals(listOf("show"), actions.calls)
    }

    @Test
    fun hold_timerThenUp_singleAction() {
        down()
        scheduler.tasks.single().block()
        assertTrue(up(eventTime = 500))
        assertEquals(listOf("show"), actions.calls)
    }

    @Test
    fun phantomTapAfterHold_ignored() {
        assertTrue(down())
        scheduler.tasks.single().block()
        assertTrue(up(eventTime = 2760))

        assertTrue(down(downTime = 2800))
        assertTrue(up(downTime = 2800, eventTime = 3036))
        assertEquals(listOf("show"), actions.calls)
    }

    @Test
    fun realTapAfterHold_goesHome() {
        assertTrue(down())
        scheduler.tasks.single().block()
        assertTrue(up(eventTime = 2760))

        assertTrue(down(downTime = 3060))
        assertTrue(up(downTime = 3060, eventTime = 3180))
        assertEquals(listOf("show", "dismiss", "goHome"), actions.calls)
    }

    @Test
    fun repeatPastThreshold_triggersHold() {
        down()
        assertTrue(down(eventTime = 550, repeatCount = 1))
        assertEquals(listOf("show"), actions.calls)
        scheduler.tasks.single().block()
        assertTrue(up(eventTime = 600))
        assertEquals(listOf("show"), actions.calls)
    }

    @Test
    fun canceledUp_noAction() {
        down()
        assertTrue(up(eventTime = 500, canceled = true))
        scheduler.tasks.single().block()
        assertTrue(actions.calls.isEmpty())
        down(downTime = 600)
        up(downTime = 600, eventTime = 720)
        assertEquals(listOf("goHome"), actions.calls)
    }

    @Test
    fun missingUp_freshDown_restarts() {
        down()
        val oldTimer = scheduler.tasks.single()
        assertTrue(down(downTime = 1000))
        assertTrue(oldTimer.cancelled)
        assertTrue(up(downTime = 1000, eventTime = 1100))
        assertEquals(listOf("goHome"), actions.calls)
        oldTimer.block()
        assertEquals(listOf("goHome"), actions.calls)
    }

    @Test
    fun staleTimer_whileNewerPressHeld_ignored() {
        down()
        up(eventTime = 100)
        down(downTime = 300)
        scheduler.tasks[0].block()
        assertEquals(listOf("goHome"), actions.calls)
        scheduler.tasks[1].block()
        assertEquals(listOf("goHome", "show"), actions.calls)
    }

    @Test
    fun orphanUp_passesThrough() {
        assertFalse(up(eventTime = 100))
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun tapWhileShowing_dismissesThenGoesHome() {
        actions.showing = true
        down()
        up(eventTime = 120)
        assertEquals(listOf("dismiss", "goHome"), actions.calls)
    }

    @Test
    fun dismissThrows_tapStillGoesHome() {
        actions.showing = true
        actions.dismissThrows = true
        down()
        assertTrue(up(eventTime = 120))
        assertEquals(listOf("dismiss", "goHome"), actions.calls)
    }

    @Test
    fun goHomeFalse_nextTapStillWorks() {
        actions.homeResult = false
        down()
        assertTrue(up(eventTime = 120))
        actions.homeResult = true
        down(downTime = 400)
        assertTrue(up(downTime = 400, eventTime = 520))
        assertEquals(listOf("goHome", "goHome"), actions.calls)
    }

    @Test
    fun rapidTaps_eachGoesHome() {
        repeat(5) { index ->
            val time = index * 400L
            assertTrue(down(downTime = time))
            assertTrue(up(downTime = time, eventTime = time + 120))
        }
        assertEquals(List(5) { "goHome" }, actions.calls)
    }

    @Test
    fun reset_cancelsPending() {
        down()
        val timer = scheduler.tasks.single()
        handler.reset()
        assertTrue(timer.cancelled)
        timer.block()
        assertTrue(actions.calls.isEmpty())
        assertFalse(up(eventTime = 600))
    }

    private fun down(
        downTime: Long = 0,
        eventTime: Long = downTime,
        repeatCount: Int = 0,
    ) = handler.onHomeKey(0, repeatCount, downTime, eventTime, canceled = false)

    private fun up(downTime: Long = 0, eventTime: Long, canceled: Boolean = false) =
        handler.onHomeKey(1, 0, downTime, eventTime, canceled)

    private class FakeScheduler {
        data class Task(val atUptimeMs: Long, val block: () -> Unit, var cancelled: Boolean = false)

        val tasks = mutableListOf<Task>()

        fun schedule(atUptimeMs: Long, block: () -> Unit): () -> Unit {
            val task = Task(atUptimeMs, block)
            tasks.add(task)
            return { task.cancelled = true }
        }
    }

    private class FakeActions : HomeActions {
        val calls = mutableListOf<String>()
        var showing = false
        var homeResult = true
        var dismissThrows = false

        override fun goHome(): Boolean {
            calls.add("goHome")
            return homeResult
        }

        override fun showSwitcher() {
            calls.add("show")
            showing = true
        }

        override fun dismissSwitcher() {
            calls.add("dismiss")
            showing = false
            if (dismissThrows) throw IllegalStateException("window gone")
        }

        override fun isSwitcherShowing() = showing
    }
}
