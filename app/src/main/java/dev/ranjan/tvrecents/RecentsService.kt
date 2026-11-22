package dev.ranjan.tvrecents

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Owns the Home key and shows the switcher for recently used apps. */
class RecentsService : AccessibilityService(), HomeActions {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val homeKeyHandler = HomeKeyHandler(::schedule, this)
    private var overlay: SwitcherOverlay? = null
    private var tracker: RecentsTracker? = null
    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        clearSession()
        val facts = AndroidPackageFacts(this)
        tracker = RecentsTracker(PrefsMruStore(this), PackageRules(packageName, facts), facts)
        overlay = SwitcherOverlay(this, ::launchApp)
        registerDebugHomePress()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false

        Log.d(
            TAG,
            "Home action=${event.action} repeat=${event.repeatCount} downTime=${event.downTime} " +
                "eventTime=${event.eventTime} flags=${event.flags}",
        )
        return homeKeyHandler.onHomeKey(
            event.action, event.repeatCount, event.downTime, event.eventTime, event.isCanceled,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        val cls = event.className?.toString()
        Log.d(TAG, "Window state pkg=$pkg cls=$cls")
        tracker?.onWindowChanged(pkg, cls)
    }

    override fun goHome(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_HOME)
        if (!result) Log.w(TAG, "GLOBAL_ACTION_HOME failed")
        return result
    }

    override fun showSwitcher() {
        val overlay = overlay ?: return
        val tracker = tracker ?: return
        val pm = packageManager
        val entries = tracker.visible().mapNotNull { pkg ->
            try {
                val app = pm.getApplicationInfo(pkg, 0)
                AppEntry(
                    pkg,
                    pm.getApplicationLabel(app),
                    pm.getApplicationBanner(app),
                    pm.getApplicationIcon(app),
                )
            } catch (e: Exception) {
                Log.d(TAG, "App no longer available: $pkg", e)
                null
            }
        }
        overlay.show(entries, tracker.initialFocusIndex(entries.map { it.pkg }))
    }

    override fun dismissSwitcher() {
        overlay?.dismiss()
    }

    override fun isSwitcherShowing(): Boolean = overlay?.isShowing == true

    override fun onInterrupt() {
        clearSession()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterDebugHomePress()
        clearSession()
        overlay = null
        tracker = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterDebugHomePress()
        clearSession()
        overlay = null
        tracker = null
        super.onDestroy()
    }

    /**
     * Debug builds only: `adb shell am broadcast -a dev.ranjan.tvrecents.DEBUG_HOME --ei ms 700`
     * feeds a Home press of that length into the handler. Injected keys skip accessibility key
     * filters, so this is the only way to drive the hold path without the physical remote.
     * Needs the DUMP permission, which adb has and other apps don't.
     */
    private fun registerDebugHomePress() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val ms = intent.getIntExtra("ms", 700).toLong()
                val down = SystemClock.uptimeMillis()
                homeKeyHandler.onHomeKey(KeyEvent.ACTION_DOWN, 0, down, down, false)
                mainHandler.postAtTime({
                    homeKeyHandler.onHomeKey(KeyEvent.ACTION_UP, 0, down, down + ms, false)
                }, down + ms)
            }
        }
        registerReceiver(
            receiver, IntentFilter(DEBUG_HOME), "android.permission.DUMP", mainHandler,
            Context.RECEIVER_EXPORTED,
        )
        debugReceiver = receiver
    }

    private fun unregisterDebugHomePress() {
        debugReceiver?.let(::unregisterReceiver)
        debugReceiver = null
    }

    private fun schedule(atUptimeMs: Long, block: () -> Unit): () -> Unit {
        val runnable = Runnable(block)
        mainHandler.postAtTime(runnable, atUptimeMs)
        return { mainHandler.removeCallbacks(runnable) }
    }

    private fun clearSession() {
        dismissSwitcher()
        homeKeyHandler.reset()
    }

    private fun launchApp(pkg: String) {
        dismissSwitcher()
        try {
            val intent = AppLauncher.frontDoorIntent(packageManager, pkg)
            if (intent != null) {
                startActivity(intent)
                return
            }
            Log.w(TAG, "No launch activity for $pkg")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not launch $pkg", e)
        }
        tracker?.remove(pkg)
    }

    private companion object {
        const val TAG = "TvRecents"
        const val DEBUG_HOME = "dev.ranjan.tvrecents.DEBUG_HOME"
    }
}
