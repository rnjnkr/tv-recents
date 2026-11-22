package dev.ranjan.tvrecents

/** How a window affects the tracked foreground and MRU list. */
enum class PackageKind { APP, HOME, IGNORE }

/** Package lookups needed to recognize foreground apps. */
interface PackageFacts {
    fun isHome(pkg: String): Boolean
    fun isIme(pkg: String): Boolean
    fun isLaunchable(pkg: String): Boolean
}

/** Classifies window changes so transient surfaces leave recents alone. */
class PackageRules(
    private val ownPackage: String,
    private val facts: PackageFacts,
    private val ignored: List<Pair<String, String>> = DEFAULT_IGNORED,
) {
    fun classify(pkg: String?, cls: String?): PackageKind {
        if (pkg == null || cls == null) return PackageKind.IGNORE
        if (pkg == ownPackage || pkg == SYSTEM_UI || facts.isIme(pkg)) return PackageKind.IGNORE
        if (ignored.any { (name, prefix) -> pkg == name && cls.startsWith(prefix) }) return PackageKind.IGNORE
        if ("Dialog" in cls || "PopupWindow" in cls) return PackageKind.IGNORE
        // Checked before launchability: a launcher with no front door must still count as Home.
        if (facts.isHome(pkg)) return PackageKind.HOME
        return if (facts.isLaunchable(pkg)) PackageKind.APP else PackageKind.IGNORE
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        val DEFAULT_IGNORED: List<Pair<String, String>> = listOf(
            SYSTEM_UI to "",
            "com.android.tv.settings" to "android.widget.FrameLayout",
        )
    }
}

/** Stores packages in most-recent-first order. */
interface MruStore {
    fun load(): List<String>
    fun save(packages: List<String>)
}

/** Encodes the ordered package list as one preference value. */
object MruCodec {
    fun encode(p: List<String>): String = p.joinToString("\n")

    fun decode(s: String?): List<String> = s.orEmpty().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
}

/** Maintains the persisted MRU list and its current foreground app. */
class RecentsTracker(
    private val store: MruStore,
    private val rules: PackageRules,
    private val facts: PackageFacts,
    private val keep: Int = 16,
) {
    private var packages = store.load().distinct().take(keep)

    var currentForeground: String? = null
        private set

    fun onWindowChanged(pkg: String?, cls: String?) {
        when (rules.classify(pkg, cls)) {
            PackageKind.IGNORE -> Unit
            PackageKind.HOME -> currentForeground = null
            PackageKind.APP -> {
                val app = requireNotNull(pkg)
                currentForeground = app
                update((listOf(app) + packages.filterNot { it == app }).take(keep))
            }
        }
    }

    fun visible(limit: Int = 8): List<String> = packages.asSequence()
        .filter(facts::isLaunchable)
        .take(limit)
        .toList()

    fun remove(pkg: String) {
        update(packages.filterNot { it == pkg })
    }

    /** Selects the previous app when the first displayed card is already in front. */
    fun initialFocusIndex(shown: List<String>): Int = when {
        shown.isEmpty() -> -1
        shown.size > 1 && shown[0] == currentForeground -> 1
        else -> 0
    }

    private fun update(next: List<String>) {
        if (next == packages) return
        packages = next
        store.save(next)
    }
}
