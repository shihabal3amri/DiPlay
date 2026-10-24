package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** Uses exactly the physical-panel renderer and live sources, not a demonstration screenshot. */
class NativeDashboardPreviewActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(Button(this).apply {
            setText(android.R.string.cancel); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            setText(R.string.settings_dashboard_cycle)
            setOnClickListener { NativeDashboardSettings.cycle(this@NativeDashboardPreviewActivity) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        root.addView(controls)
        root.addView(NativeDashboardView(this, preview = true), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
