package dev.ranjan.tvrecents

import android.content.Intent
import android.content.pm.PackageManager

/** Resolves the activity that opens an app from the TV or standard launcher. */
object AppLauncher {

    /** Tries LEANBACK_LAUNCHER first and returns an explicit NEW_TASK intent. */
    fun frontDoorIntent(pm: PackageManager, pkg: String): Intent? {
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg)
            val activity = pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo
                ?: continue
            return intent.setClassName(activity.packageName, activity.name)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }
}
