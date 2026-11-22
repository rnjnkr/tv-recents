package dev.ranjan.tvrecents

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Display data for a launchable app. */
data class AppEntry(val pkg: String, val label: CharSequence, val banner: Drawable?, val icon: Drawable)

/** Owns the focusable accessibility window and handles D-pad app selection. */
class SwitcherOverlay(
    service: AccessibilityService,
    private val onPick: (String) -> Unit,
    private val onAppInfo: (String) -> Unit,
) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(service)
    private var root: SwitcherRoot? = null
    private var focusTarget: View? = null
    private var appInfoCard: SwitcherCard? = null
    private var chipDownTime: Long? = null
    private val handler = Handler(Looper.getMainLooper())
    private val okPressTracker = OkPressTracker(::schedule, object : OkActions {
        override fun onShortPress() {
            (root?.findFocus() as? SwitcherCard)?.let { onPick(it.pkg) }
        }

        override fun onLongPress() {
            val content = root ?: return
            val card = content.findFocus() as? SwitcherCard ?: return
            if (card.isAttachedToWindow) showAppInfo(content, card)
        }
    })
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
        resetPresses()
        closeAppInfo()
        val existing = root
        val content = existing ?: (inflater.inflate(R.layout.switcher, null) as SwitcherRoot).apply {
            onBack = back@{
                if (root !== this) return@back
                val card = appInfoCard
                if (card == null) dismiss() else closeAppInfo(card)
            }
            onKey = { if (root === this) restartIdleTimer() }
            onOk = { if (root === this) onOkKey(it) }
            onFocusChanged = focus@{ focused ->
                if (root !== this) return@focus
                resetPresses()
                if (appInfoCard != null && focused !== appInfoChip) closeAppInfo()
            }
            appInfoChip.setOnKeyListener { _, keyCode, event ->
                if (root !== this) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) moveFromAppInfo(keyCode)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            }
            findViewById<View>(R.id.switcher_scroll).setOnScrollChangeListener { _, _, _, _, _ ->
                if (root === this) appInfoCard?.let { positionAppInfo(this, it) }
            }
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
        resetPresses()
        closeAppInfo()
        (content.findFocus() as? SwitcherCard)?.stopGlow()
        root = null
        focusTarget = null
        handler.removeCallbacks(autoDismiss)
        try {
            windowManager.removeView(content)
        } catch (e: RuntimeException) {
            Log.w("TvRecents", "Could not remove switcher", e)
        }
    }

    /** Debug hook: runs a press of [holdMs] through the overlay's own key dispatch (adb can't hold keys). */
    fun debugKey(keyCode: Int, holdMs: Long) {
        val content = root ?: return
        val down = SystemClock.uptimeMillis()
        content.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0))
        handler.postAtTime({
            root?.dispatchKeyEvent(KeyEvent(down, down + holdMs, KeyEvent.ACTION_UP, keyCode, 0))
        }, down + holdMs)
    }

    private fun restartIdleTimer() {
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, IDLE_TIMEOUT_MS)
    }

    private fun schedule(atUptimeMs: Long, block: () -> Unit): () -> Unit {
        val runnable = Runnable(block)
        handler.postAtTime(runnable, atUptimeMs)
        return { handler.removeCallbacks(runnable) }
    }

    private fun resetPresses() {
        okPressTracker.reset()
        chipDownTime = null
    }

    private fun onOkKey(event: KeyEvent) {
        val content = root ?: return
        val focused = content.findFocus()
        if (focused is SwitcherCard) {
            okPressTracker.onKey(
                event.action, event.repeatCount, event.downTime, event.eventTime, event.isCanceled,
            )
        } else if (focused === content.appInfoChip) {
            // The release that opened the chip belongs to the card's press.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                chipDownTime = event.downTime
            } else if (event.action == KeyEvent.ACTION_UP && chipDownTime == event.downTime) {
                chipDownTime = null
                val card = appInfoCard
                if (!event.isCanceled && card != null) {
                    dismiss()
                    onAppInfo(card.pkg)
                }
            }
        }
    }

    private fun showAppInfo(content: SwitcherRoot, card: SwitcherCard) {
        appInfoCard = card
        val chip = content.appInfoChip
        chip.visibility = View.VISIBLE
        if (chip.requestFocus()) {
            card.markSource(true)
            positionAppInfo(content, card)
        } else {
            closeAppInfo()
        }
    }

    private fun positionAppInfo(content: SwitcherRoot, card: SwitcherCard) {
        val chip = content.appInfoChip
        val cardPosition = IntArray(2)
        val rootPosition = IntArray(2)
        card.getLocationInWindow(cardPosition)
        content.getLocationInWindow(rootPosition)
        val gap = content.dp(20)
        val cardLeft = cardPosition[0] - rootPosition[0]
        val cardTop = cardPosition[1] - rootPosition[1]
        val right = cardLeft + card.width + gap
        val maxX = (content.width - chip.width).coerceAtLeast(0)
        val maxY = (content.height - chip.height).coerceAtLeast(0)
        val x = if (right <= maxX) right else cardLeft - gap - chip.width
        val y = cardTop + (card.height - chip.height) / 2
        chip.translationX = (x.coerceIn(0, maxX) - chip.left).toFloat()
        chip.translationY = (y.coerceIn(0, maxY) - chip.top).toFloat()
    }

    private fun closeAppInfo(target: View? = null) {
        val source = appInfoCard ?: return
        appInfoCard = null
        chipDownTime = null
        source.markSource(false)
        target?.requestFocus()
        root?.appInfoChip?.visibility = View.INVISIBLE
    }

    private fun moveFromAppInfo(keyCode: Int) {
        val card = appInfoCard ?: return
        val parent = card.parent as LinearLayout
        val index = parent.indexOfChild(card)
        val step = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        val target = (index + step + parent.childCount) % parent.childCount
        closeAppInfo(parent.getChildAt(target))
    }

    private fun createCard(parent: LinearLayout, entry: AppEntry): View =
        (inflater.inflate(R.layout.switcher_card, parent, false) as SwitcherCard).apply {
            pkg = entry.pkg
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
                    else -> false
                }
            }
        }
}

/** Consumes Back and OK before they reach any child. */
internal class SwitcherRoot(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    var onBack: (() -> Unit)? = null
    var onKey: (() -> Unit)? = null
    var onOk: ((KeyEvent) -> Unit)? = null
    var onFocusChanged: ((View?) -> Unit)? = null
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused ->
        onFocusChanged?.invoke(focused)
    }

    val appInfoChip: View
        get() = findViewById(R.id.switcher_app_info)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onKey?.invoke()
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack?.invoke()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                onOk?.invoke(event)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        onFocusChanged?.invoke(null)
        super.onDetachedFromWindow()
    }
}

/** Draws the animated focus ring and halo outside the card bounds. */
internal class SwitcherCard(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    lateinit var pkg: String
    private lateinit var glow: View
    private lateinit var content: View
    private var glowAnimator: ObjectAnimator? = null
    private var isSource = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        glow = findViewById(R.id.card_glow)
        content = findViewById(R.id.card_content)
        // The card has 8dp corners. A stroke is centred on its path, so the ring's path radius is
        // 8 + 3/2 (outer edge 11dp, flush with the card) and the halo's is 11 + 3/2.
        findViewById<View>(R.id.card_ring).background = outline(3, 9.5f, 0xFFFFB300.toInt())
        findViewById<View>(R.id.card_halo).background = outline(3, 12.5f, 0x55FFB300)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        updateHighlight(focused)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateHighlight(isFocused)
    }

    override fun onDetachedFromWindow() {
        stopGlow()
        super.onDetachedFromWindow()
    }

    fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
        glow.alpha = 1f
        glow.visibility = View.INVISIBLE
    }

    /** Keeps the card lit, with a steady ring, while its App info chip has the focus. */
    fun markSource(on: Boolean) {
        isSource = on
        updateHighlight(isFocused)
    }

    private fun updateHighlight(focused: Boolean) {
        val raised = focused || isSource
        val scale = if (raised) 1.1f else 1f
        scaleX = scale
        scaleY = scale
        // A scrim, not alpha: see-through cards let the app behind bleed into them.
        content.foreground = if (raised) null else ColorDrawable(0x99000000.toInt())
        translationZ = if (raised) dp(1).toFloat() else 0f
        stopGlow()
        if (raised && isAttachedToWindow) glow.visibility = View.VISIBLE
        if (focused && isAttachedToWindow) {
            glowAnimator = ObjectAnimator.ofFloat(glow, View.ALPHA, 1f, 0.55f).apply {
                duration = 600L
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    private fun outline(stroke: Int, radius: Float, color: Int) = GradientDrawable().apply {
        setStroke(dp(stroke), color)
        cornerRadius = radius * resources.displayMetrics.density
    }
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
