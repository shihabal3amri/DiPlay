package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Projects the next CarPlay turn onto a secondary display (HUD) through the standard
 * Android [DisplayManager] and [android.app.Presentation] APIs.
 *
 * Originally developed for the Geely/Lynk head unit, this mechanism works on any
 * Android head unit that exposes its HUD as a secondary [Display], including Meizu
 * Flyme automotive. It does not use vendor-specific IPC.
 */
object GeelyHudProjection : DisplayManager.DisplayListener {
    private const val TAG = "DiPlay-GeelyHud"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activityRef: WeakReference<Activity>? = null
    private var displayManager: DisplayManager? = null
    private var hudPresentation: GeelyHudPresentation? = null
    private var attachedDisplayId: Int = -1
    private var attachedWidth: Int = 0
    private var attachedHeight: Int = 0
    private var guidance: ClusterTurnGuidance? = null

    fun attach(activity: Activity) {
        mainHandler.post {
            val current = activityRef?.get()
            if (current != activity) {
                detachWindow()
                displayManager?.unregisterDisplayListener(this)
                activityRef = WeakReference(activity)
                val manager = activity.getSystemService(DisplayManager::class.java)
                displayManager = manager
                manager?.registerDisplayListener(this, mainHandler)
            }
            refresh()
        }
    }

    fun detach(activity: Activity) {
        mainHandler.post {
            if (activityRef?.get() != activity) return@post
            detachWindow()
            displayManager?.unregisterDisplayListener(this)
            displayManager = null
            activityRef = null
        }
    }

    fun update(value: ClusterTurnGuidance?) {
        mainHandler.post {
            guidance = value
            refresh()
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveGeelyHudEnabled(context, enabled)
        mainHandler.post { refresh() }
    }

    fun availableDisplays(context: Context): List<GeelyHudDisplay> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return manager.getDisplays()
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
            .map { display ->
                val (width, height) = displaySize(context, display)
                GeelyHudDisplay(display.displayId, display.name ?: "", width, height)
            }
            .sortedBy { it.id }
    }

    fun selectDisplay(context: Context, display: GeelyHudDisplay?) {
        AirPlayPersistence.saveGeelyHudDisplay(context, display?.id ?: -1, display?.name)
        mainHandler.post { refresh() }
    }

    fun setScale(context: Context, percent: Int) {
        AirPlayPersistence.saveGeelyHudScalePercent(context, percent)
        mainHandler.post {
            hudPresentation?.setScalePercent(percent)
            refresh()
        }
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()

    private fun refresh() {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            detachWindow()
            return
        }
        if (!AirPlayPersistence.loadGeelyHudEnabled(activity) || guidance == null) {
            detachWindow()
            return
        }

        val manager = displayManager ?: return
        val displays = manager.getDisplays()
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
        if (displays.isEmpty()) {
            detachWindow()
            return
        }

        val savedDisplayId = AirPlayPersistence.loadGeelyHudDisplayId(activity)
        val savedDisplayName = AirPlayPersistence.loadGeelyHudDisplayName(activity)
        val display = if (savedDisplayId != -1 || savedDisplayName != null) {
            displays.firstOrNull { it.displayId == savedDisplayId && (savedDisplayName == null || it.name == savedDisplayName) }
                ?: savedDisplayName?.let { name -> displays.firstOrNull { it.name == name } }
        } else {
            displays.firstOrNull { it.name?.contains("hud", ignoreCase = true) == true }
                ?: displays.singleOrNull()
        } ?: run {
            detachWindow()
            return
        }

        val displayContext = activity.createDisplayContext(display)
        val (width, height) = displaySize(activity, display)
        if (attachedDisplayId != display.displayId || attachedWidth != width || attachedHeight != height) {
            detachWindow()
            val scalePercent = AirPlayPersistence.loadGeelyHudScalePercent(activity)
            try {
                val presentation = GeelyHudPresentation(displayContext, display, scalePercent)
                presentation.show()
                hudPresentation = presentation
                attachedDisplayId = display.displayId
                attachedWidth = width
                attachedHeight = height
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not open the HUD display", error)
                try { hudPresentation?.dismiss() } catch (_: RuntimeException) {}
                hudPresentation = null
                return
            }
        }
        hudPresentation?.setGuidance(guidance)
    }

    private fun displaySize(context: Context, display: Display): Pair<Int, Int> {
        val metrics: DisplayMetrics = context.createDisplayContext(display)
            .resources.displayMetrics
        val width = metrics.widthPixels.takeIf { it > 0 } ?: display.mode.physicalWidth
        val height = metrics.heightPixels.takeIf { it > 0 } ?: display.mode.physicalHeight
        return max(width, 1) to max(height, 1)
    }

    private fun detachWindow() {
        try { hudPresentation?.dismiss() } catch (_: RuntimeException) {}
        hudPresentation = null
        attachedDisplayId = -1
        attachedWidth = 0
        attachedHeight = 0
    }
}
