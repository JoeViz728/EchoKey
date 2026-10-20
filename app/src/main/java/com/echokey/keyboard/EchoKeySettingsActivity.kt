package com.echokey.keyboard

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal settings placeholder so the IME can be enabled and the settings
 * gear in the system input-method screen does not crash.
 *
 * Real preferences (default mode, multi-tap accept style, etc.) come later.
 */
class EchoKeySettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "EchoKey"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Minimal settings placeholder.\n\n" +
                "Enable EchoKey under System → Languages & input → On-screen keyboard, " +
                "then switch to it from the globe key while typing.\n\n" +
                "On the keyboard: tap T9/abc to toggle mode. " +
                "Long-press digit keys for literal numbers. " +
                "Hold action for newline when Send/Go is primary."
            textSize = 15f
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
    }
}
