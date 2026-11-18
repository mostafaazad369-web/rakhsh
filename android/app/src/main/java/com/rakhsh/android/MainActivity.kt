package com.rakhsh.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Initial Android shell. This screen intentionally does not claim that the
 * Rust tunnel is connected; native TUN and socket-protection integration is
 * a separate, required milestone.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = TextView(this).apply {
            text = getString(R.string.integration_pending)
            textSize = 18f
            setPadding(32, 48, 32, 32)
        }
        setContentView(status)
    }
}
