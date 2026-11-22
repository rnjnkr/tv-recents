package dev.ranjan.tvrecents

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.InputMethodManager

/** Resolves launcher, keyboard and front-door activity information from Android. */
class AndroidPackageFacts(context: Context) : PackageFacts {
    private val pm = context.packageManager
    private val inputMethods = context.getSystemService(InputMethodManager::class.java)
    private var homePackages = emptySet<String>()

    override fun isHome(pkg: String): Boolean {
        if (pkg in homePackages) return true
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        homePackages = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
        return pkg in homePackages
    }

    override fun isIme(pkg: String): Boolean =
        inputMethods?.enabledInputMethodList?.any { it.packageName == pkg } == true

    override fun isLaunchable(pkg: String): Boolean = try {
        AppLauncher.frontDoorIntent(pm, pkg) != null
    } catch (e: RuntimeException) {
        Log.w("TvRecents", "Could not resolve launch activity for $pkg", e)
        false
    }
}
