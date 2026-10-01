package com.shilapi.xcertplay.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.airplay.ContentRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manages the floating picture-in-picture (PIP) window overlay for CarPlay.
 * Enables CarPlay to remain visible and interactive while running other apps (e.g. DiYou Desktop).
 */
@SuppressLint("StaticFieldLeak")
object CarPlayFloatingManager {
    private const val TAG = "CarPlayFloating"
    private const val SCREEN_TYPE_MAIN = 1

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var textureView: TextureView? = null
    private var currentSurface: Surface? = null
    private var windowParams: WindowManager.LayoutParams? = null

    var isFloating = false
        private set

    enum class FloatingSize(val widthDp: Int, val heightDp: Int) {
        SMALL(360, 225),
        MEDIUM(480, 300),
        LARGE(640, 400);

        fun next(): FloatingSize = when (this) {
            SMALL -> MEDIUM
            MEDIUM -> LARGE
            LARGE -> SMALL
        }
    }

    private var currentSize = FloatingSize.MEDIUM

    fun hasOverlayPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, context.getString(R.string.grant_overlay_permission_desc), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Shows the floating overlay window using the active background session.
     */
    fun show(context: Context): Boolean {
        if (!hasOverlayPermission(context)) {
            requestOverlayPermission(context)
            return false
        }
        val snapshot = CarPlayBackgroundSession.snapshot()
        if (snapshot == null || snapshot.controller.isClosed()) {
            Log.w(TAG, "Cannot show floating window: No active CarPlay session")
            return false
        }

        if (isFloating) return true

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = context.resources.displayMetrics.density
        val widthPx = (currentSize.widthDp * density).roundToInt()
        val heightPx = (currentSize.heightDp * density).roundToInt()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - widthPx - (24 * density).toInt()).coerceAtLeast(0)
            y = (48 * density).toInt()
        }
        windowParams = params

        val view = buildFloatingLayout(context, snapshot)
        floatingView = view

        try {
            wm.addView(view, params)
            isFloating = true
            Log.i(TAG, "CarPlay floating window added at ${params.x}, ${params.y}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window", e)
            dismiss()
            return false
        }
    }

    fun dismiss() {
        if (!isFloating && floatingView == null) return
        try {
            currentSurface?.let { surface ->
                CarPlayBackgroundSession.snapshot()?.sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                surface.release()
            }
            currentSurface = null
            textureView = null
            floatingView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing floating window", e)
        } finally {
            floatingView = null
            isFloating = false
        }
    }

    private fun buildFloatingLayout(
        context: Context,
        snapshot: CarPlayBackgroundSession.Snapshot
    ): View {
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).roundToInt() }

        // Root container with rounded corners and border
        val root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 12, 17, 27))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.argb(160, 166, 200, 255))
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
        }

        // TextureView for CarPlay video rendering
        val texture = TextureView(context).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val surface = Surface(st)
                    currentSurface = surface
                    snapshot.sink.setSurface(SCREEN_TYPE_MAIN, surface)
                    updateTextureAspect(this@apply, w, h, snapshot)
                    Log.i(TAG, "Floating TextureView surface available: ${w}x$h")
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    updateTextureAspect(this@apply, w, h, snapshot)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    currentSurface?.let { surface ->
                        snapshot.sink.clearSurface(SCREEN_TYPE_MAIN, surface)
                        surface.release()
                    }
                    currentSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        textureView = texture
        root.addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Touch event dispatch overlay for CarPlay
        val touchOverlay = View(context).apply {
            isClickable = true
            setOnTouchListener { v, event ->
                val canvas = snapshot.canvas
                val content = if (canvas != null && v.width > 0 && v.height > 0) {
                    ContentRect.fit(canvas, v.width, v.height)
                } else {
                    ContentRect(0f, 0f, v.width.toFloat(), v.height.toFloat())
                }
                val contacts = CarPlayTouchMapper.contacts(event, content)
                snapshot.controller.sendTouch(contacts)
                true
            }
        }
        root.addView(touchOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top Control Header Bar
        val headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(190, 15, 23, 42))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        // Drag handle title
        val titleView = TextView(context).apply {
            text = "DiPlay · " + context.getString(R.string.floating_window)
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
        }
        headerBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Resize button
        val resizeBtn = TextView(context).apply {
            text = "⤢"
            setTextColor(Color.rgb(166, 200, 255))
            textSize = 16f
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                cycleSize(context, root)
            }
        }
        headerBar.addView(resizeBtn)

        // Expand to fullscreen button
        val expandBtn = TextView(context).apply {
            text = "🗖"
            setTextColor(Color.rgb(166, 200, 255))
            textSize = 16f
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                expandToFullscreen(context)
            }
        }
        headerBar.addView(expandBtn)

        // Close button
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.rgb(255, 107, 107))
            textSize = 16f
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener {
                dismiss()
            }
        }
        headerBar.addView(closeBtn)

        // Setup drag listener on header
        setupDragListener(headerBar, context)

        root.addView(headerBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.TOP))

        return root
    }

    private fun setupDragListener(headerView: View, context: Context) {
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var isDragging = false

        headerView.setOnTouchListener { _, event ->
            val params = windowParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (abs(dx) > 5 || abs(dy) > 5 || isDragging) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        floatingView?.let { windowManager?.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        headerView.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun cycleSize(context: Context, root: View) {
        currentSize = currentSize.next()
        val density = context.resources.displayMetrics.density
        val params = windowParams ?: return
        params.width = (currentSize.widthDp * density).roundToInt()
        params.height = (currentSize.heightDp * density).roundToInt()
        floatingView?.let { windowManager?.updateViewLayout(it, params) }
    }

    private fun expandToFullscreen(context: Context) {
        dismiss()
        val intent = Intent(context, CarPlayHostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    private fun updateTextureAspect(
        view: TextureView,
        viewWidth: Int,
        viewHeight: Int,
        snapshot: CarPlayBackgroundSession.Snapshot
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val canvas = snapshot.canvas ?: return
        val rect = ContentRect.fit(canvas, viewWidth, viewHeight)
        val matrix = Matrix()
        if (!rect.isFullView) {
            matrix.setScale(rect.width / viewWidth, rect.height / viewHeight)
            matrix.postTranslate(rect.left, rect.top)
        }
        view.setTransform(matrix)
    }
}
