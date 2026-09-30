package com.kivan.motoparty.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kivan.motoparty.Hub
import com.kivan.motoparty.MotopartyApp
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One floating TALK button above the navigation app: a ≥96 dp glove-sized zone, tap to toggle
 * talk, drag to move (position is remembered). It looks and reads like the app's TALK button
 * ([OverlayLook]): orange "TALK", red "END TALK", breathing while the talk is still opening. Since option A (2026-09-29) it is
 * the only button: commands are spoken inside a talk, so the MUSIC zone is gone.
 *
 * The position is kept as a fraction of the usable area — the display minus the system-bar and
 * cutout insets, which is the frame this window's `x`/`y` are relative to (see [OverlayPlacement])
 * — and clamped on every layout and on every configuration change, so neither a rotation nor a
 * drag to the far corner can leave the buttons off-screen where nothing can drag them back.
 *
 * Getting rid of them (F6): drag the buttons and an X target appears at the bottom; dropping them
 * on it turns the `overlayEnabled` setting off and stops this service. They come back from the
 * "Show buttons" action on the Motoparty notification or the switch in the app — never on their
 * own, and never automatically hidden while the setting is on.
 */
class OverlayService : LifecycleService() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var talk: TextView
    private lateinit var params: WindowManager.LayoutParams

    /** What [render] last drew, and the animation of the "opening" look while it runs. */
    private var shown: OverlayLook? = null
    private var pulse: ObjectAnimator? = null

    /** The X target: a second, untouchable window, only present while a drag is running. */
    private var dismiss: TextView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var dismissBox: OverlayPlacement.Box? = null
    private var dismissHot = false

    /** Remembered position, as a fraction of the free travel on each axis. */
    private var fx = 0f
    private var fy = 0f

    override fun onCreate() {
        super.onCreate()
        if (!AndroidSettings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // A name for `dumpsys window windows`; it keeps the package in it so the bench's
            // `bench.py frame <pkg>` still matches this window (see android/HANDOFF.md, F6).
            title = BUTTONS_TITLE
        }
        loadPosition()
        talk = zone("TALK")
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(talk)
            setOnTouchListener(DragOrTap())
            // WRAP_CONTENT: the real size is only known once laid out, and it changes with the
            // font scale, so the clamp is redone on every layout pass.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        }
        place()
        wm.addView(root, params)
        // Only what render() shows: the status changes every second (ping age, position), and
        // each redraw here is a new background drawable on top of the navigation app.
        lifecycleScope.launch {
            Hub.status.map { OverlayLook.of(it.talkOpen, it.talkLive) }.distinctUntilChanged().collect(::render)
        }
    }

    /** Rotation, a resize, a display change: put the buttons back inside the new bounds. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        place()
        placeDismiss()
    }

    override fun onDestroy() {
        pulse?.cancel()
        pulse = null
        hideDismiss()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    // ---- position ----

    private fun prefs() = getSharedPreferences("overlay", Context.MODE_PRIVATE)

    /**
     * The area the window is really laid out in, in pixels.
     *
     * `params.x/y` are relative to the window's parent frame, which excludes the status bar, the
     * display cutout and the navigation bar — on the Pixel 8 in rotation 0 `dumpsys window` shows
     * `parent=[0,132][1080,2337]` while the bounds are 1080x2400. Clamping against the bounds put
     * the far corner 132 px below the screen. So: bounds minus the system-bar and cutout insets,
     * taken *ignoring visibility* so an auto-hidden bar cannot make the area jump. Keeping the
     * buttons inside this safe area is also what we want for a gloved tap.
     */
    private fun usableSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val b = metrics.bounds
            val i = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return OverlayPlacement.usable(b.width(), i.left, i.right) to
                OverlayPlacement.usable(b.height(), i.top, i.bottom)
        }
        // API 29: displayMetrics is already the app area, without the system decor.
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /** The window's own size; before the first layout, what the zones below will add up to. */
    private fun windowSize(): Pair<Int, Int> {
        val v = root
        if (v != null && v.width > 0 && v.height > 0) return v.width to v.height
        return dp(ZONE_W_DP + 2 * ZONE_MARGIN_DP) to dp(ZONE_H_DP + 2 * ZONE_MARGIN_DP)
    }

    private fun loadPosition() {
        val prefs = prefs()
        if (prefs.contains(KEY_FX)) {
            fx = prefs.getFloat(KEY_FX, 0f)
            fy = prefs.getFloat(KEY_FY, 0f)
            return
        }
        // Older versions stored raw pixels, which is what made landscape unreachable. Convert
        // once, against the display we are on now, and clamp whatever was out of bounds.
        val (dw, dh) = usableSize()
        val (w, h) = windowSize()
        fx = OverlayPlacement.fraction(OverlayPlacement.clamp(prefs.getInt("x", 0), w, dw), w, dw)
        fy = OverlayPlacement.fraction(OverlayPlacement.clamp(prefs.getInt("y", dp(200)), h, dh), h, dh)
        savePosition()
    }

    private fun savePosition() {
        prefs().edit().putFloat(KEY_FX, fx).putFloat(KEY_FY, fy).remove("x").remove("y").apply()
    }

    /** Turn the remembered fractions into pixels for the display we have right now. */
    private fun place() {
        val (dw, dh) = usableSize()
        val (w, h) = windowSize()
        val x = OverlayPlacement.position(fx, w, dw)
        val y = OverlayPlacement.position(fy, h, dh)
        if (x == params.x && y == params.y) return
        params.x = x
        params.y = y
        // Before addView the new params are simply picked up by it.
        root?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ---- drag to dismiss ----

    /**
     * Add the X target for the duration of a drag. Its own window: `FLAG_NOT_TOUCHABLE` so it can
     * never take the drag away from the buttons, and the same `FLAG_LAYOUT_NO_LIMITS` /
     * `TOP|START` geometry, so its `x`/`y` live in the same parent frame the drag is clamped in and
     * [OverlayPlacement.overDismiss] can compare the two rectangles directly.
     */
    private fun showDismiss() {
        if (dismiss != null) return
        val view = TextView(this).apply {
            text = "✕"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val p = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = DISMISS_TITLE
        }
        dismiss = view
        dismissParams = p
        dismissHot = false
        placeDismiss()
        renderDismiss(false)
        runCatching { wm.addView(view, p) }
    }

    /** Put the target where the current usable area says, and remember the box for the hit test. */
    private fun placeDismiss() {
        val p = dismissParams ?: return
        val (dw, dh) = usableSize()
        val box = OverlayPlacement.dismissTarget(dw, dh, dp(DISMISS_DP), dp(DISMISS_MARGIN_DP))
        dismissBox = box
        p.x = box.left
        p.y = box.top
        p.width = box.width
        p.height = box.height
        dismiss?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, p) } }
    }

    private fun renderDismiss(hot: Boolean) {
        val view = dismiss ?: return
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (hot) 0xF0C62828.toInt() else 0xB0212121.toInt())
            setStroke(dp(2), if (hot) Color.WHITE else 0x80FFFFFF.toInt())
        }
        // The window is exactly the target box, so growing past 1 would be clipped by its own
        // surface: the idle circle is drawn a little smaller instead and snaps to full size when
        // the buttons are over it. The hit box (the box) does not change with it.
        val scale = if (hot) 1f else 0.85f
        view.scaleX = scale
        view.scaleY = scale
    }

    private fun hideDismiss() {
        dismiss?.let { runCatching { wm.removeView(it) } }
        dismiss = null
        dismissParams = null
        dismissBox = null
        dismissHot = false
    }

    /** Is the dragged window over the X right now? Also drives the highlight. */
    private fun overDismiss(): Boolean {
        val box = dismissBox ?: return false
        val (w, h) = windowSize()
        val over = OverlayPlacement.overDismiss(params.x, params.y, w, h, box)
        if (over != dismissHot) {
            dismissHot = over
            renderDismiss(over)
        }
        return over
    }

    private fun zone(label: String) = TextView(this).apply {
        text = label
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        // "END TALK" takes two lines, broken at the word gap.
        maxLines = 2
        setTextColor(OverlayLook.IDLE.text)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(ZONE_W_DP), dp(ZONE_H_DP))
            .apply { setMargins(dp(ZONE_MARGIN_DP), dp(ZONE_MARGIN_DP), dp(ZONE_MARGIN_DP), dp(ZONE_MARGIN_DP)) }
    }

    private fun render(look: OverlayLook) {
        if (look == shown) return
        shown = look
        talk.background = rounded(look.fill)
        talk.setTextColor(look.text)
        talk.text = look.label
        pulse?.cancel()
        pulse = null
        talk.alpha = 1f
        if (look.pulsing) {
            // Pressed, not live yet: the button breathes until the "live" beep. Alpha only, so the
            // window is neither resized nor laid out again over the navigation app.
            pulse = ObjectAnimator.ofFloat(talk, View.ALPHA, 1f, PULSE_MIN_ALPHA).apply {
                duration = PULSE_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(color)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class DragOrTap : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop * 2
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        /** The fractions as they were when the finger went down: restored on a drop on the X. */
        private var startFx = 0f
        private var startFy = 0f
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    startFx = fx; startFy = fy
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        showDismiss()
                    }
                    if (dragging) {
                        // Clamped as it moves, so the buttons can never be dragged off the edge.
                        val (dw, dh) = usableSize()
                        val (w, h) = windowSize()
                        params.x = OverlayPlacement.clamp(startX + dx.toInt(), w, dw)
                        params.y = OverlayPlacement.clamp(startY + dy.toInt(), h, dh)
                        // The remembered fraction moves *with* the finger, not only on release.
                        // `updateViewLayout` asks for a new traversal, whose layout pass calls
                        // `place()`, which writes params.x/y back from fx/fy: with the old values
                        // still there it undid every move, the window snapped back on release and
                        // the "saved" fraction was the one already stored (so SharedPreferences
                        // skipped the write and overlay.xml never even changed mtime). Updating
                        // them here makes `place()` a no-op mid-drag — position(fraction(x)) == x
                        // exactly, see OverlayPlacementTest — and a rotation during a drag still
                        // lands the window proportionally, like any other placement.
                        fx = OverlayPlacement.fraction(params.x, w, dw)
                        fy = OverlayPlacement.fraction(params.y, h, dh)
                        wm.updateViewLayout(root, params)
                        overDismiss() // highlight follows the finger
                    }
                }
                // CANCEL ends the gesture just as UP does (the system took the touch away, e.g.
                // the window moved out from under the finger). Without it the drag was never
                // saved and the next layout pass snapped the buttons back to the old fraction.
                // A cancelled *tap*, though, is not a tap: never fire a trigger on CANCEL.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Dropping on the X only counts on a real release: a CANCEL is the system
                    // taking the gesture away, not the rider letting go, so it ends the drag and
                    // saves the position exactly as before — it never hides the buttons.
                    val dropped = dragging && e.actionMasked == MotionEvent.ACTION_UP && overDismiss()
                    hideDismiss()
                    if (dropped) {
                        // Not a move: put the buttons back where this drag started (nothing is
                        // saved, so the stored fractions are still the old ones) and hide them.
                        fx = startFx
                        fy = startFy
                        place()
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        MotopartyApp.instance.settings.update { it.copy(overlayEnabled = false) }
                        Hub.log("overlay: hidden by drag to the X (Show buttons on the notification)")
                        dragging = false
                        stopSelf()
                        return true
                    }
                    if (dragging) {
                        val (dw, dh) = usableSize()
                        val (w, h) = windowSize()
                        fx = OverlayPlacement.fraction(params.x, w, dw)
                        fy = OverlayPlacement.fraction(params.y, h, dh)
                        savePosition()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        Triggers.fire(TriggerKind.TALK, TriggerSource.OVERLAY)
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    private companion object {
        const val KEY_FX = "fx"
        const val KEY_FY = "fy"
        /** One square button, well over the 96 dp glove minimum. */
        const val ZONE_W_DP = 120
        const val ZONE_H_DP = 120
        const val ZONE_MARGIN_DP = 4

        /** The X target: a 112 dp circle (> the 96 dp glove minimum), 12 dp off the bottom. */
        const val DISMISS_DP = 112
        const val DISMISS_MARGIN_DP = 12
        const val BUTTONS_TITLE = "com.kivan.motoparty:buttons"
        const val DISMISS_TITLE = "MotopartyDismiss"
        /** The "opening" breath: half a second down, half a second up, never dimmer than this. */
        const val PULSE_MS = 500L
        const val PULSE_MIN_ALPHA = 0.55f
    }
}
