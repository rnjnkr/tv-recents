package dev.ranjan.tvrecents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises OK presses with a scheduler whose callbacks can be delivered out of order. */
class OkPressTrackerTest {
    private val scheduler = FakeScheduler()
    private val actions = FakeActions()
    private val tracker = OkPressTracker(scheduler::schedule, actions)

    @Test
    fun down_schedulesAtAbsoluteDeadline() {
        assertTrue(down(downTime = 1000, eventTime = 1200))
        assertEquals(1500L, scheduler.tasks.single().atUptimeMs)
    }

    @Test
    fun shortPress_actsOnlyOnRelease() {
        assertTrue(down())
        assertTrue(down(eventTime = 250, repeatCount = 1))
        assertTrue(actions.calls.isEmpty())
        assertTrue(up(eventTime = 499))
        assertTrue(scheduler.tasks.single().cancelled)
        scheduler.tasks.single().block()
        assertEquals(listOf("short"), actions.calls)
    }

    @Test
    fun hold_timerFiresWithoutRepeats_releaseDoesNothing() {
        down()
        val timer = scheduler.tasks.single()
        timer.block()
        assertEquals(listOf("long"), actions.calls)
        timer.block()
        assertTrue(up(eventTime = 800))
        assertEquals(listOf("long"), actions.calls)
    }

    @Test
    fun hold_upAtOrAfterThreshold_beforeTimer_actsOnce() {
        down()
        assertTrue(up(eventTime = 500))
        down(downTime = 1000)
        assertTrue(up(downTime = 1000, eventTime = 1550))
        scheduler.tasks.forEach {
            assertTrue(it.cancelled)
            it.block()
        }
        assertEquals(listOf("long", "long"), actions.calls)
    }

    @Test
    fun repeatAtThreshold_triggersHoldOnce() {
        down()
        assertTrue(down(eventTime = 500, repeatCount = 1))
        assertEquals(listOf("long"), actions.calls)
        assertTrue(down(eventTime = 550, repeatCount = 2))
        scheduler.tasks.single().block()
        assertTrue(up(eventTime = 600))
        assertEquals(listOf("long"), actions.calls)
    }

    @Test
    fun canceledUp_cancelsHoldEvenAtThreshold() {
        down()
        assertTrue(up(eventTime = 500, canceled = true))
        val timer = scheduler.tasks.single()
        assertTrue(timer.cancelled)
        timer.block()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun orphanRepeatAndUp_areConsumedWithoutAnAction() {
        assertTrue(down(eventTime = 550, repeatCount = 1))
        assertTrue(up(eventTime = 600))
        assertTrue(scheduler.tasks.isEmpty())
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun reset_cancelsPendingAndConsumesLateRelease() {
        down()
        val timer = scheduler.tasks.single()
        tracker.reset()
        assertTrue(timer.cancelled)
        timer.block()
        assertTrue(up(eventTime = 600))
        assertTrue(actions.calls.isEmpty())

        down(downTime = 700)
        up(downTime = 700, eventTime = 800)
        assertEquals(listOf("short"), actions.calls)
    }

    @Test
    fun freshDown_replacesPressAndIgnoresItsStaleTimerAndUp() {
        down()
        val oldTimer = scheduler.tasks.single()
        assertTrue(down(downTime = 300))
        assertTrue(oldTimer.cancelled)
        oldTimer.block()
        assertTrue(up(eventTime = 550))
        assertTrue(actions.calls.isEmpty())
        scheduler.tasks[1].block()
        assertEquals(listOf("long"), actions.calls)
    }

    private fun down(
        downTime: Long = 0,
        eventTime: Long = downTime,
        repeatCount: Int = 0,
    ) = tracker.onKey(0, repeatCount, downTime, eventTime, canceled = false)

    private fun up(downTime: Long = 0, eventTime: Long, canceled: Boolean = false) =
        tracker.onKey(1, 0, downTime, eventTime, canceled)

    private class FakeScheduler {
        data class Task(val atUptimeMs: Long, val block: () -> Unit, var cancelled: Boolean = false)

        val tasks = mutableListOf<Task>()

        fun schedule(atUptimeMs: Long, block: () -> Unit): () -> Unit {
            val task = Task(atUptimeMs, block)
            tasks.add(task)
            return { task.cancelled = true }
        }
    }

    private class FakeActions : OkActions {
        val calls = mutableListOf<String>()

        override fun onShortPress() {
            calls.add("short")
        }

        override fun onLongPress() {
            calls.add("long")
        }
    }
}
