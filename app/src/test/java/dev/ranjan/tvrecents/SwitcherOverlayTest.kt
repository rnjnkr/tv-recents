package dev.ranjan.tvrecents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitcherOverlayTest {
    @Test
    fun close_hiddenForForegroundApp() {
        assertFalse(canRemoveCard("a", "a"))
    }

    @Test
    fun close_shownForBackgroundAppOrUnknownForeground() {
        assertTrue(canRemoveCard("a", "b"))
        assertTrue(canRemoveCard("a", null))
    }

    @Test
    fun removal_focusesNextCard() {
        assertEquals(0, focusIndexAfterRemoval(0, 2))
        assertEquals(1, focusIndexAfterRemoval(1, 2))
    }

    @Test
    fun removal_focusesPreviousCardWhenLast() {
        assertEquals(1, focusIndexAfterRemoval(2, 2))
    }

    @Test
    fun removal_focusesRootWhenEmpty() {
        assertEquals(-1, focusIndexAfterRemoval(0, 0))
    }
}
