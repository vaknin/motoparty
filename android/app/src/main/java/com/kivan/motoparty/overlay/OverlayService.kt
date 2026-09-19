package com.kivan.motoparty.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
 */
class OverlayService : LifecycleService() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var talk: TextView
    private lateinit var music: TextView
    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        if (!AndroidSettings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        val prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 0)
            y = prefs.getInt("y", dp(200))
        }
        talk = zone("TALK")
        music = zone("MUSIC")
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(talk)
            addView(music)
            setOnTouchListener(DragOrTap())
        }
        wm.addView(root, params)
        lifecycleScope.launch { Hub.status.collect(::render) }
    }

    override fun onDestroy() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    private fun zone(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(112), dp(100)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
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
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        getSharedPreferences("overlay", Context.MODE_PRIVATE).edit()
                            .putInt("x", params.x).putInt("y", params.y).apply()
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
}
