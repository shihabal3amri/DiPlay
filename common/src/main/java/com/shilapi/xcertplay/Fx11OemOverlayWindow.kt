package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

/** AutoDisplay's ordinary physical-screen path. No virtual display, HAB or vehicle writes. */
internal class Fx11OemOverlayWindow(context: Context, val display: Display) {
    private val displayContext = context.createDisplayContext(display)
    private val manager = displayContext.getSystemService(WindowManager::class.java)
    val view = NativeDashboardView(displayContext, viewport = bounds()).apply {
        artworkView.style = DashboardStyle.OEM_OVERLAY
        setBackgroundColor(Color.TRANSPARENT)
    }
    val params = layoutParams()
    var isShowing = false
        private set
    init {
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) = Unit
            override fun onViewDetachedFromWindow(view: android.view.View) { isShowing = false }
        })
    }

    fun show() {
        check(Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(displayContext)) {
            "OEM overlay requires the existing display-over-other-apps permission"
        }
        manager.addView(view, params)
        isShowing = true
    }

    fun setAlpha(alpha: Float) {
        require(alpha == 1f || alpha == 0.8f)
        params.alpha = alpha
        if (isShowing) manager.updateViewLayout(view, params)
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        manager.removeViewImmediate(view)
    }

    companion object {
        internal fun bounds() = RectF(600f, 48f, 1320f, 526f)
        internal fun layoutParams() = WindowManager.LayoutParams(
            bounds().width().toInt(), bounds().height().toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Required for the CarPlay map's TextureView, also used by AutoDisplay maps.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Physical coordinates must not flip to the right edge in an RTL locale.
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds().left.toInt(); y = bounds().top.toInt(); dimAmount = 0f
            title = "DiPlay central OEM overlay"
        }
    }
}
