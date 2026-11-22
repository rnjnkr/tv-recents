package dev.ranjan.tvrecents

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks classification independently of Android package lookups. */
class PackageRulesTest {
    private val facts = object : PackageFacts {
        override fun isHome(pkg: String) = pkg == "com.google.android.apps.tv.launcherx"
        override fun isIme(pkg: String) = pkg == "keyboard"
        override fun isLaunchable(pkg: String) = pkg != "background"
    }
    private val rules = PackageRules("dev.ranjan.tvrecents", facts)

    @Test
    fun nullPkgOrCls_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify(null, "MainActivity"))
        assertEquals(PackageKind.IGNORE, rules.classify("app", null))
    }

    @Test
    fun own_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify("dev.ranjan.tvrecents", "android.widget.FrameLayout"))
    }

    @Test
    fun systemui_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify("com.android.systemui", "MainActivity"))
        assertEquals(
            PackageKind.IGNORE,
            rules.classify("com.android.systemui", "com.android.systemui.volume.VolumeDialogImpl\$CustomDialog"),
        )
    }

    @Test
    fun ime_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify("keyboard", "MainActivity"))
    }

    @Test
    fun notLaunchable_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify("background", "MainActivity"))
    }

    @Test
    fun prefixMatch_ignore() {
        val rules = PackageRules("recents", facts, listOf("com.sony.dtv.x" to "com.sony.dtv.x.Saver"))
        assertEquals(PackageKind.IGNORE, rules.classify("com.sony.dtv.x", "com.sony.dtv.x.SaverActivity"))
        assertEquals(PackageKind.APP, rules.classify("com.sony.dtv.x", "com.sony.dtv.x.MainActivity"))
        assertEquals(PackageKind.APP, rules.classify("other", "com.sony.dtv.x.SaverActivity"))
    }

    @Test
    fun quickSettings_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify("com.android.tv.settings", "android.widget.FrameLayout"))
        assertEquals(PackageKind.APP, rules.classify("com.android.tv.settings", "SettingsActivity"))
    }

    @Test
    fun dialogClass_ignore() {
        assertEquals(PackageKind.IGNORE, rules.classify("app", "android.app.Dialog"))
        assertEquals(PackageKind.IGNORE, rules.classify("app", "app.PopupWindow\$PopupDecorView"))
    }

    @Test
    fun home_home() {
        assertEquals(PackageKind.HOME, rules.classify("com.google.android.apps.tv.launcherx", "MainActivity"))
    }

    @Test
    fun launcherWithoutFrontDoor_stillHome() {
        val noFrontDoor = object : PackageFacts {
            override fun isHome(pkg: String) = pkg == "launcher"
            override fun isIme(pkg: String) = false
            override fun isLaunchable(pkg: String) = false
        }
        val rules = PackageRules("dev.ranjan.tvrecents", noFrontDoor)
        assertEquals(PackageKind.HOME, rules.classify("launcher", "HomeActivity"))
        assertEquals(PackageKind.IGNORE, rules.classify("background", "MainActivity"))
    }

    @Test
    fun normal_app() {
        assertEquals(PackageKind.APP, rules.classify("app", "MainActivity"))
    }
}
