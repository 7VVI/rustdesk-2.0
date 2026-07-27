package com.example.rddemo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import com.carriez.flutter_hbb.RDMainRunner

/**
 * Minimal demo host that embeds the RustDesk controlled-end via [RDMainRunner].
 *
 * This exercises the public SDK entry point:
 *   - official server:   RDMainRunner.start(context, id, password)
 *   - self-hosted server: RDMainRunner.start(context, idServer, relayServer, key, id, password)
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        // 1) Start as controlled-end using the official (built-in) server.
        root.addView(Button(this).apply {
            text = "Start (official server)"
            setOnClickListener {
                RDMainRunner.start(this@DemoActivity, /*id*/ "", /*password*/ "")
            }
        })

        // 2) Start as controlled-end using a self-hosted server (runtime config).
        root.addView(Button(this).apply {
            text = "Start (self-hosted server)"
            setOnClickListener {
                RDMainRunner.start(
                    this@DemoActivity,
                    /*idServer*/ "rs.example.com",
                    /*relayServer*/ "rs.example.com",
                    /*key*/ "",
                    /*id*/ "",
                    /*password*/ "123456"
                )
            }
        })

        setContentView(root)
    }
}
