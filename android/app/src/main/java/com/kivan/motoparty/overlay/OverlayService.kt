package com.kivan.motoparty.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kivan.motoparty.Hub
import com.kivan.motoparty.LinkStatus
import com.kivan.motoparty.trigger.TriggerKind
import com.kivan.motoparty.trigger.TriggerSource
import com.kivan.motoparty.trigger.Triggers
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Floating TALK / MUSIC buttons above the navigation app. Two ≥96 dp zones, tap to trigger,
 * drag anywhere to move (position is remembered). Colour = state.
 *
 * The position is kept as a fraction of the display (see [OverlayPlacement]) and clamped on every
 * layout and on every configuration change, so a rotation cannot leave the buttons off-screen
 * where nothing can drag them back.
 */
class OverlayService : LifecycleService() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var talk: TextView
    private lateinit var music: TextView
    private lateinit var params: WindowManager.LayoutParams

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
        ).apply { gravity = Gravity.TOP or Gravity.START }
        loadPosition()
        talk = zone("TALK")
        music = zone("MUSIC")
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(talk)
            addView(music)
            setOnTouchListener(DragOrTap())
            // WRAP_CONTENT: the real size is only known once laid out, and it changes with the
            // font scale, so the clamp is redone on every layout pass.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        }
        place()
        wm.addView(root, params)
        lifecycleScope.launch { Hub.status.collect(::render) }
    }

    /** Rotation, a resize, a display change: put the buttons back inside the new bounds. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        place()
    }

    override fun onDestroy() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    // ---- position ----

    private fun prefs() = getSharedPreferences("overlay", Context.MODE_PRIVATE)

    /** Display bounds in pixels, the whole display: the window may use all of it. */
    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /** The window's own size; before the first layout, what the zones below will add up to. */
    private fun windowSize(): Pair<Int, Int> {
        val v = root
        if (v != null && v.width > 0 && v.height > 0) return v.width to v.height
        return dp(ZONE_W_DP + 2 * ZONE_MARGIN_DP) to dp(2 * (ZONE_H_DP + 2 * ZONE_MARGIN_DP))
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
        val (dw, dh) = displaySize()
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
        val (dw, dh) = displaySize()
        val (w, h) = windowSize()
        val x = OverlayPlacement.position(fx, w, dw)
        val y = OverlayPlacement.position(fy, h, dh)
        if (x == params.x && y == params.y) return
        params.x = x
        params.y = y
        // Before addView the new params are simply picked up by it.
        root?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun zone(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(ZONE_W_DP), dp(ZONE_H_DP))
            .apply { setMargins(dp(ZONE_MARGIN_DP), dp(ZONE_MARGIN_DP), dp(ZONE_MARGIN_DP), dp(ZONE_MARGIN_DP)) }
    }

    private fun render(s: LinkStatus) {
        val talkColor = when {
            s.talkOpen -> 0xE02E7D32.toInt() // green: live
            s.clientName == null -> 0xC0616161.toInt() // grey: nobody to talk to
            else -> 0xE01565C0.toInt() // blue: ready
        }
        val musicColor = when {
            s.listening -> 0xE0C62828.toInt() // red: mic open for a command
            s.busy != null -> 0xE0EF6C00.toInt() // amber: searching / loading
            s.playing -> 0xE06A1B9A.toInt() // purple: music playing
            else -> 0xC0424242.toInt()
        }
        talk.background = rounded(talkColor)
        music.background = rounded(musicColor)
        talk.text = if (s.talkOpen) "TALKING" else "TALK"
        music.text = when {
            s.listening -> "LISTENING"
            s.busy != null -> "…"
            else -> "MUSIC"
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
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // Clamped as it moves, so the buttons can never be dragged off the edge.
                        val (dw, dh) = displaySize()
                        val (w, h) = windowSize()
                        params.x = OverlayPlacement.clamp(startX + dx.toInt(), w, dw)
                        params.y = OverlayPlacement.clamp(startY + dy.toInt(), h, dh)
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val (dw, dh) = displaySize()
                        val (w, h) = windowSize()
                        fx = OverlayPlacement.fraction(params.x, w, dw)
                        fy = OverlayPlacement.fraction(params.y, h, dh)
                        savePosition()
                    } else {
                        // Which zone was hit: TALK is the top half.
                        val kind = if (e.y < talk.bottom + dp(4)) TriggerKind.TALK else TriggerKind.MUSIC
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        Triggers.fire(kind, TriggerSource.OVERLAY)
                    }
                }
            }
            return true
        }
    }

    private companion object {
        const val KEY_FX = "fx"
        const val KEY_FY = "fy"
        const val ZONE_W_DP = 112
        const val ZONE_H_DP = 100
        const val ZONE_MARGIN_DP = 4
    }
}
