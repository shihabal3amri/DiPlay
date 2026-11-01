package com.shilapi.xcertplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect

/**
 * Generic cluster/HUD route for head units that are not BYD DiLink or Geely — for example the
 * Q07 and other aftermarket "Android for car" units.
 *
 * Unlike [DiLink4ClusterDisplay] (one OEM surface name + geometry, launched over ADB) and the
 * Geely / DiLink 5 routes, this reaches the cluster the way an independent nav app does and the
 * way the Q07's own nav app was observed to (decompiled from EzNav 2.7.3, see
 * docs — EzNav-Q07-mechanism-REA):
 *
 *  1. enumerate **every** display from [DisplayManager.getDisplays] — NOT only the
 *     presentation-category list — and skip the main screen (id 0);
 *  2. choose the cluster by the driver-pinned resolution if set, else the sole secondary display,
 *     else the widest-aspect one (clusters are far wider than 16:9);
 *  3. launch DiPlay's existing cluster Activity ([AdbClusterActivity]) onto that display with
 *     `ActivityOptions.setLaunchDisplayId` and no ADB.
 *
 * The render surface, stream sizing, turn card, speedometer, music overlay and safe-area editor
 * are DiPlay's existing cluster pipeline (driven through [ClusterActivityOutput] /
 * [AdbClusterRouter]); only the display discovery and the launch transport are new here.
 *
 * It is opt-in ([AirPlayPersistence.loadGenericClusterEnabled]) and never auto-selects, so BYD and
 * Geely units keep their measured route and no unit silently projects onto a passenger screen.
 */
internal object GenericClusterDisplay {
    private const val TAG = "DiPlay-Cluster"

    fun isEnabled(context: Context): Boolean = AirPlayPersistence.loadGenericClusterEnabled(context)

    /** Real pixel size of [display] via getRealMetrics (what the cluster panel actually reports). */
    private fun realSize(display: Display): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun aspect(size: Pair<Int, Int>): Double {
        val (w, h) = size
        if (w <= 0 || h <= 0) return 0.0
        return maxOf(w, h).toDouble() / minOf(w, h)
    }

    /**
     * The cluster display id, or a value <= 0 when none is found. Scans all displays (skipping the
     * main screen), then applies the preference order above. This is the EzNav-equivalent of
     * `DisplayManager.getDisplays()` + pick-by-resolution.
     */
    fun pickDisplayId(context: Context): Int {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return -1
        val all = dm.displays.orEmpty()
        // Log every display so a single logcat reveals the panel to target. Mirrors the
        // reference nav app: the only hard filter is "not the main screen" (id 0).
        all.forEach { d ->
            val (w, h) = realSize(d)
            Log.i(TAG, "display id=${d.displayId} name=${d.name} ${w}x${h} flags=${d.flags} valid=${d.isValid}")
        }
        val candidates = all.filter { d ->
            d.displayId != Display.DEFAULT_DISPLAY && realSize(d).let { it.first > 0 && it.second > 0 }
        }
        if (candidates.isEmpty()) { Log.w(TAG, "no secondary display found"); return -1 }

        AirPlayPersistence.loadGenericClusterTarget(context)?.let { (w, h) ->
            candidates.firstOrNull { realSize(it) == (w to h) }?.let {
                Log.i(TAG, "picked pinned ${w}x${h} -> display ${it.displayId}"); return it.displayId
            }
        }
        candidates.singleOrNull()?.let {
            Log.i(TAG, "picked sole secondary -> display ${it.displayId}"); return it.displayId
        }
        return candidates.maxWithOrNull(
            compareBy({ aspect(realSize(it)) }, { realSize(it).first }),
        )?.also { Log.i(TAG, "picked widest secondary -> display ${it.displayId}") }?.displayId ?: -1
    }

    /** Real pixel size of the display this route would target, or null when none is found. */
    fun pickedSize(context: Context): Point? {
        val id = pickDisplayId(context)
        if (id <= 0) return null
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = dm.displays.orEmpty().firstOrNull { it.displayId == id } ?: return null
        val (w, h) = realSize(display)
        return if (w > 0 && h > 0) Point(w, h) else null
    }

    /**
     * CarPlay cluster stream sized to the actual cluster panel (not the fixed BYD 1920x720),
     * with the driver's safe area and marker applied — the generic equivalent of
     * [DiLink4ClusterDisplay.streamConfig]. Returns null when no cluster display is found.
     */
    fun streamConfig(
        context: Context,
        content: CarPlayClusterDisplay.Content,
        safeAreaRect: SafeAreaRect? = null,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
        scalePercent: Int = 100,
    ): AirPlayDisplayConfig? {
        val size = pickedSize(context) ?: return null
        val config = CarPlayClusterDisplay.config(
            size.x, size.y, scalePercent = scalePercent, content = content,
            markerXPercent = markerXPercent, markerYPercent = markerYPercent,
        )
        return if (safeAreaRect == null) config
        else config.copy(safeArea = AirPlaySafeArea.toInsets(safeAreaRect, size.x, size.y, size.x, size.y))
    }

    /**
     * Launch [AdbClusterActivity] onto the chosen cluster display with no ADB, exactly as an
     * ordinary app targets a second display. [prepare] is the same gate the ADB route uses: it
     * records the chosen display id and confirms the launch generation is still current.
     * Returns a report line pair suitable for the diagnostic log and whether the start was issued.
     */
    fun launch(context: Context, token: String, prepare: (Int) -> Boolean): AdbClusterRouter.Result {
        val report = StringBuilder("generic cluster launch (no ADB) capturedAt=${java.util.Date()}\n")
        val display = pickDisplayId(context)
        report.append("routeTarget=${if (display > 0) display else "none"}\n")
        if (display <= 0 || !prepare(display)) {
            return AdbClusterRouter.Result(false, report.append("no target display\n").toString())
        }
        return try {
            // NEW_TASK | SINGLE_TOP, matching the unit's own nav app; AdbClusterActivity is a
            // singleTask activity with its own taskAffinity, so this lands one cluster instance
            // on the target display and reuses it on repeat launches.
            val intent = Intent(context, AdbClusterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("cluster_launch_token", token)
            val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(display) }
            context.startActivity(intent, options.toBundle())
            Log.i(TAG, "generic cluster: launched AdbClusterActivity on display $display")
            AdbClusterRouter.Result(true, report.append("launched on display $display\n").toString())
        } catch (t: Throwable) {
            Log.w(TAG, "generic cluster launch failed", t)
            AdbClusterRouter.Result(false, report.append("launch failed: $t\n").toString())
        }
    }
}
