package dev.ranjan.tvrecents

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Display data for a launchable app. */
data class AppEntry(val pkg: String, val label: CharSequence, val banner: Drawable?, val icon: Drawable)

/** Owns the focusable accessibility window and handles D-pad app selection. */
class SwitcherOverlay(service: AccessibilityService, private val onPick: (String) -> Unit) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(service)
    private var root: SwitcherRoot? = null
    private var focusTarget: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable {
        Log.d("TvRecents", "Switcher auto-dismissed after idle")
        dismiss()
    }

    val isShowing: Boolean
        get() = root != null

    private companion object {
        const val IDLE_TIMEOUT_MS = 10_000L
    }

    /** Focuses the root when focusIndex is -1 or there are no entries. */
    fun show(entries: List<AppEntry>, focusIndex: Int) {
        val existing = root
        val content = existing ?: (inflater.inflate(R.layout.switcher, null) as SwitcherRoot).apply {
            onBack = ::dismiss
            onKey = ::restartIdleTimer
        }
        val cards = content.findViewById<LinearLayout>(R.id.switcher_cards)
        cards.removeAllViews()
        entries.forEach { cards.addView(createCard(cards, it)) }
        content.findViewById<View>(R.id.switcher_scroll).visibility =
            if (entries.isEmpty()) View.GONE else View.VISIBLE
        content.findViewById<View>(R.id.switcher_empty).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE

        if (existing == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                dimAmount = 0f
            }
            try {
                windowManager.addView(content, params)
            } catch (e: RuntimeException) {
                Log.w("TvRecents", "Could not show switcher", e)
                return
            }
            root = content
        }

        restartIdleTimer()
        val target = cards.getChildAt(focusIndex) ?: content
        focusTarget = target
        content.post {
            if (root === content && focusTarget === target) target.requestFocus()
        }
    }

    fun dismiss() {
        val content = root ?: return
        root = null
        focusTarget = null
        handler.removeCallbacks(autoDismiss)
        try {
            windowManager.removeView(content)
        } catch (e: RuntimeException) {
            Log.w("TvRecents", "Could not remove switcher", e)
        }
    }

    private fun restartIdleTimer() {
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, IDLE_TIMEOUT_MS)
    }

    private fun createCard(parent: LinearLayout, entry: AppEntry): View =
        inflater.inflate(R.layout.switcher_card, parent, false).apply {
            contentDescription = entry.label
            val banner = findViewById<ImageView>(R.id.card_banner)
            val fallback = findViewById<View>(R.id.card_fallback)
            if (entry.banner != null) {
                banner.setImageDrawable(entry.banner)
                banner.visibility = View.VISIBLE
                fallback.visibility = View.GONE
            } else {
                findViewById<ImageView>(R.id.card_icon).setImageDrawable(entry.icon)
                findViewById<TextView>(R.id.card_label).text = entry.label
            }

            setOnFocusChangeListener { card, focused ->
                val scale = if (focused) 1.1f else 1f
                card.scaleX = scale
                card.scaleY = scale
            }
            setOnKeyListener { card, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // Wrap around at either end; elsewhere let normal focus search move.
                        val last = parent.childCount - 1
                        val index = parent.indexOfChild(card)
                        val target = when {
                            keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index == 0 -> last
                            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && index == last -> 0
                            else -> return@setOnKeyListener false
                        }
                        if (event.action == KeyEvent.ACTION_DOWN && target != index) {
                            parent.getChildAt(target).requestFocus()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onPick(entry.pkg)
                        true
                    }
                    else -> false
                }
            }
        }
}

/** Consumes Back before it reaches any child and dismisses on release. */
internal class SwitcherRoot(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    var onBack: (() -> Unit)? = null
    var onKey: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onKey?.invoke()
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack?.invoke()
        return true
    }
}
