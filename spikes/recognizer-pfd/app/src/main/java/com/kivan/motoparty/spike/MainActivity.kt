package com.kivan.motoparty.spike

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Throwaway spike host. Three tests, each startable by a button or by an intent extra:
 *
 *     am start -n com.kivan.motoparty.spike/.MainActivity --es test pfd
 *     am start -n com.kivan.motoparty.spike/.MainActivity --es test pfd --es recognizer default
 *     am start -n com.kivan.motoparty.spike/.MainActivity --es test usage
 *     am start -n com.kivan.motoparty.spike/.MainActivity --es test props
 *     am start -n com.kivan.motoparty.spike/.MainActivity --es test all
 *
 * Everything it prints also goes to logcat under the tag `Spike`.
 */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        SpikeLog.attach { line -> runOnUiThread { append(line) } }
        SpikeLog.log("spike ready: ${android.os.Build.MODEL}, SDK ${android.os.Build.VERSION.SDK_INT}")
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        SpikeLog.detach()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val test = intent?.getStringExtra("test") ?: return
        val recognizer = intent.getStringExtra("recognizer") ?: "ondevice"
        start(test, recognizer == "default")
    }

    private fun start(test: String, defaultRecognizer: Boolean) {
        if (!busy.compareAndSet(false, true)) {
            SpikeLog.warn("a test is already running, ignoring '$test'")
            return
        }
        Thread({
            try {
                when (test.lowercase()) {
                    "pfd" -> PfdTest.run(this, defaultRecognizer)
                    "usage" -> UsageTest.run(this)
                    "props" -> PropsTest.run(this)
                    "all" -> {
                        PropsTest.run(this)
                        UsageTest.run(this)
                        PfdTest.run(this, defaultRecognizer)
                    }
                    else -> SpikeLog.warn("unknown test '$test' (use pfd|usage|props|all)")
                }
                SpikeLog.log("--- done: $test ---")
            } catch (t: Throwable) {
                SpikeLog.err("test '$test' threw", t)
            } finally {
                busy.set(false)
            }
        }, "spike-$test").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- ui

    private fun buildUi(): LinearLayout {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        for ((label, test) in listOf("PFD" to "pfd", "USAGE" to "usage", "PROPS" to "props", "ALL" to "all")) {
            buttons.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { start(test, false) }
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            )
        }
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(
            Button(this).apply {
                text = "PFD (default recognizer)"
                setOnClickListener { start("pfd", true) }
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(Color.parseColor("#B8F0B8"))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun append(line: String) {
        output.append(line)
        output.append("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
