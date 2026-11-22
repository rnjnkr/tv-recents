package dev.ranjan.tvrecents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks foreground tracking, persistence and focus using fake package facts and storage. */
class RecentsTrackerTest {
    private val store = FakeStore()
    private val unlaunchable = mutableSetOf<String>()
    private val facts = object : PackageFacts {
        override fun isHome(pkg: String) = pkg == "home"
        override fun isIme(pkg: String) = false
        override fun isLaunchable(pkg: String) = pkg !in unlaunchable
    }
    private val rules = PackageRules("recents", facts)
    private val tracker = RecentsTracker(store, rules, facts)

    @Test
    fun order_mostRecentFirst_deduped() {
        window("a", "b", "a")
        assertEquals(listOf("a", "b"), tracker.visible())
        assertEquals("a", tracker.currentForeground)
        assertEquals(3, store.saves)
        window("a")
        assertEquals(3, store.saves)
    }

    @Test
    fun cap_keeps16_shows8() {
        val packages = List(20) { "app$it" }
        window(*packages.toTypedArray())
        assertEquals(packages.takeLast(16).reversed(), store.load())
        assertEquals(packages.takeLast(8).reversed(), tracker.visible())
    }

    @Test
    fun ignore_doesNotChangeOrderOrForeground() {
        window("a", "b", "com.android.systemui", "recents")
        assertEquals(listOf("b", "a"), tracker.visible())
        assertEquals("b", tracker.currentForeground)
        assertEquals(2, store.saves)
    }

    @Test
    fun home_setsForegroundNull_keepsList() {
        window("a", "b", "home")
        assertNull(tracker.currentForeground)
        assertEquals(listOf("b", "a"), tracker.visible())
        assertEquals(2, store.saves)
        window("a")
        assertEquals("a", tracker.currentForeground)
    }

    @Test
    fun focus_inApp_isSecond() {
        window("a", "b")
        assertEquals(1, tracker.initialFocusIndex(tracker.visible()))
    }

    @Test
    fun focus_fromHome_isFirst() {
        window("a", "b", "home")
        assertEquals(0, tracker.initialFocusIndex(tracker.visible()))
    }

    @Test
    fun focus_single_isZero() {
        window("a")
        assertEquals(0, tracker.initialFocusIndex(tracker.visible()))
    }

    @Test
    fun focus_empty_isMinusOne() {
        assertEquals(-1, tracker.initialFocusIndex(tracker.visible()))
    }

    @Test
    fun focus_firstNotForeground_isZero() {
        window("a", "b", "c")
        unlaunchable.add("c")
        assertEquals(0, tracker.initialFocusIndex(tracker.visible()))
    }

    @Test
    fun focus_usesFinalShownList() {
        window("a", "b")
        assertEquals(0, tracker.initialFocusIndex(listOf("b")))
    }

    @Test
    fun visible_dropsUnlaunchable() {
        window("a", "b", "c")
        unlaunchable.add("b")
        assertEquals(listOf("c", "a"), tracker.visible())
        assertEquals(listOf("c", "b", "a"), store.load())
        assertEquals(3, store.saves)
        unlaunchable.clear()
        assertEquals(listOf("c", "b", "a"), tracker.visible())
    }

    @Test
    fun remove_persists() {
        window("a", "b", "c")
        tracker.remove("b")
        assertEquals(listOf("c", "a"), RecentsTracker(store, rules, facts).visible())
    }

    @Test
    fun persistence_roundTrip16_exactOrder() {
        val packages = List(16) { "app$it" }
        window(*packages.toTypedArray())
        assertEquals(packages.reversed(), store.load())
        val restored = RecentsTracker(store, rules, facts)
        assertEquals(packages.reversed(), restored.visible(limit = 16))
        assertNull(restored.currentForeground)
    }

    private fun window(vararg packages: String) {
        packages.forEach { tracker.onWindowChanged(it, "MainActivity") }
    }

    private class FakeStore : MruStore {
        private var encoded: String? = null
        var saves = 0
            private set

        override fun load() = MruCodec.decode(encoded)

        override fun save(packages: List<String>) {
            encoded = MruCodec.encode(packages)
            saves++
        }
    }
}
