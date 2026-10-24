package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

internal enum class DashboardStyle(val title: Int) {
    OFF(R.string.settings_dashboard_off), MIDNIGHT(R.string.settings_dashboard_midnight),
    PANORAMA(R.string.settings_dashboard_panorama), REPLICA(R.string.settings_dashboard_replica),
    OEM_OVERLAY(R.string.settings_dashboard_oem_overlay)
}

/** Classic dashboard palette; independent of the phone video and saved style ordinals. */
internal enum class DashboardAppearance(val title: Int) {
    NIGHT(R.string.settings_dashboard_appearance_night), DAY(R.string.settings_dashboard_appearance_day),
    AUTO(R.string.settings_dashboard_appearance_auto);

    fun resolve(localHour: Int): DashboardAppearance {
        require(localHour in 0..23)
        return if (this == AUTO) { if (localHour in 6 until 18) DAY else NIGHT } else this
    }
}

/** Separate the Android window from the vendor's unverified DIM composition mode. */
internal enum class OemOverlayPath(val title: Int) {
    FACTORY(R.string.settings_overlay_path_factory),
    DIM(R.string.settings_overlay_path_dim)
}

/** Map typography only; video dimensions, window bounds and primary CarPlay stay unchanged. */
internal enum class DashboardMapSize(val title: Int, val widthMm: Int, val renderScale: Float) {
    SMALL(R.string.settings_dashboard_map_small, 200, 1f),
    MEDIUM(R.string.settings_dashboard_map_medium, 200, 1.15f),
    LARGE(R.string.settings_dashboard_map_large, 200, 1.3f),
    EXTRA_LARGE(R.string.settings_dashboard_map_extra_large, 200, 1.5f);

}

internal enum class DashboardMode(val title: Int) {
    ALL(R.string.settings_dashboard_all), NAVIGATION(R.string.settings_dashboard_navigation),
    MUSIC(R.string.settings_dashboard_music), TIRES(R.string.settings_dashboard_tires);
    // Keep persisted ordinals stable. Legacy Panorama pages now keep the map plus a compact card.
    fun forStyle(style: DashboardStyle) = when {
        (style == DashboardStyle.MIDNIGHT || style == DashboardStyle.REPLICA) && this == ALL -> MUSIC
        style == DashboardStyle.PANORAMA && this != TIRES -> MUSIC
        (style == DashboardStyle.OFF || style == DashboardStyle.OEM_OVERLAY) && this == TIRES -> ALL
        else -> this
    }
    fun next(style: DashboardStyle): DashboardMode = step(style, 1)
    fun step(style: DashboardStyle, direction: Int): DashboardMode {
        require(direction == 1 || direction == -1)
        val choices = choices(style)
        return choices[(choices.indexOf(forStyle(style)) + direction + choices.size) % choices.size]
    }
    fun titleFor(style: DashboardStyle) = if (style == DashboardStyle.PANORAMA) {
        if (this == TIRES) R.string.settings_dashboard_map_tires else R.string.settings_dashboard_map_music
    } else title
    companion object {
        fun choices(style: DashboardStyle): List<DashboardMode> =
            when (style) {
                DashboardStyle.MIDNIGHT, DashboardStyle.REPLICA -> listOf(MUSIC, NAVIGATION, TIRES)
                DashboardStyle.PANORAMA -> listOf(MUSIC, TIRES)
                DashboardStyle.OFF, DashboardStyle.OEM_OVERLAY -> listOf(ALL, NAVIGATION, MUSIC)
            }
    }
}

/** Native dashboard settings do not depend on iPhone connection or Ultra negotiation. */
internal object NativeDashboardSettings {
    // ECARX adaptapi input.KeyCode.KEYCODE_R_MODE (also R_BUTTON_MODE).
    const val MODE_KEY = 200110
    const val FX11_MODE_KEY = 200400
    internal fun defaultModeKey(context: Context): Int =
        if (BoyueLSettings.selected(context) && android.os.Build.MODEL == "FX11_LOW") FX11_MODE_KEY else MODE_KEY
    private fun savedKey(context: Context, name: String, fallback: Int): Int = try {
        prefs(context).getInt(name, fallback)
    } catch (_: ClassCastException) { fallback }
    /** A calibration belongs to this installation; other Geely vehicles retain the SDK default. */
    fun modeKey(context: Context): Int = savedKey(context, "mode_key", defaultModeKey(context))
        .takeIf(DashboardWheelKeyPolicy::isModeKeyAllowed) ?: defaultModeKey(context)
    fun setModeKey(context: Context, code: Int) {
        require(DashboardWheelKeyPolicy.isModeKeyAllowed(code))
        require(code != savedKey(context, "page_key", 0)) { "MODE and page keys must differ" }
        prefs(context).edit().putInt("mode_key", code).apply()
    }
    fun pageKey(context: Context): Int = savedKey(context, "page_key", 0)
        .takeIf { DashboardWheelKeyPolicy.isPageKeyAllowed(it, modeKey(context)) } ?: 0
    fun setPageKey(context: Context, code: Int) {
        require(code == 0 || DashboardWheelKeyPolicy.isPageKeyAllowed(code, modeKey(context)))
        prefs(context).edit().putInt("page_key", code).apply()
    }
    private fun prefs(context: Context) = context.getSharedPreferences("native_dashboard", Context.MODE_PRIVATE)
    fun appearance(context: Context) = DashboardAppearance.entries.getOrElse(savedKey(context, "appearance", 0)) { DashboardAppearance.NIGHT }
    /** Re-evaluated by the existing render tick; local timezone/clock changes take effect without a reconnect. */
    fun effectiveAppearance(context: Context, now: java.util.Calendar = java.util.Calendar.getInstance()) =
        appearance(context).resolve(now.get(java.util.Calendar.HOUR_OF_DAY))
    fun setAppearance(context: Context, appearance: DashboardAppearance) {
        prefs(context).edit().putInt("appearance", appearance.ordinal).apply()
    }
    fun mapSize(context: Context) = DashboardMapSize.entries.getOrElse(savedKey(context, "map_size", 2)) { DashboardMapSize.LARGE }
    fun setMapSize(context: Context, size: DashboardMapSize) {
        prefs(context).edit().putInt("map_size", size.ordinal).apply()
    }
    fun mapHorizontalPosition(context: Context, style: DashboardStyle = style(context)): Int =
        savedKey(context, "map_horizontal_${style.name}", 50)
            .takeIf { it in NativeDashboardMapConfig.horizontalPositions } ?: 50
    fun setMapHorizontalPosition(context: Context, percent: Int, style: DashboardStyle = style(context)) {
        require(percent in NativeDashboardMapConfig.horizontalPositions)
        prefs(context).edit().putInt("map_horizontal_${style.name}", percent).apply()
    }
    fun style(context: Context) = DashboardStyle.entries.getOrElse(prefs(context).getInt("style", 0)) { DashboardStyle.OFF }
    fun setStyle(context: Context, style: DashboardStyle) {
        val saved = prefs(context)
        val editor = saved.edit().putInt("style", style.ordinal)
        if (style == DashboardStyle.PANORAMA && !saved.contains("panorama_mode")) {
            val legacy = DashboardMode.entries.getOrElse(saved.getInt("mode", 0)) { DashboardMode.ALL }
            editor.putInt("panorama_mode", legacy.forStyle(style).ordinal)
        }
        editor.apply()
        BoyueLSettings.syncDimForDashboard(context, style == DashboardStyle.OFF || style == DashboardStyle.OEM_OVERLAY)
    }
    fun overlayRestoreRequired(context: Context) = prefs(context).getBoolean("overlay_restore_required", false)
    fun setOverlayRestoreRequired(context: Context, required: Boolean) {
        check(prefs(context).edit().putBoolean("overlay_restore_required", required).commit())
    }
    fun overlayPath(context: Context) = OemOverlayPath.entries.getOrElse(savedKey(context, "overlay_path", 0)) { OemOverlayPath.FACTORY }
    // Android IDs only. Never translate them into QNX screen numbers or silently pick another output.
    fun overlayDisplay(context: Context) = savedKey(context, "overlay_display", 4).takeIf { it == 3 || it == 4 } ?: 4
    fun setOverlayOutput(context: Context, path: OemOverlayPath, display: Int) {
        require(display == 3 || display == 4)
        prefs(context).edit().putInt("overlay_path", path.ordinal).putInt("overlay_display", display).apply()
    }
    fun enabled(context: Context) = BoyueLSettings.selected(context) && style(context) != DashboardStyle.OFF
    fun migrateNavigationDefault(context: Context) {
        if (!BoyueLSettings.selected(context) || prefs(context).getBoolean("navigation_default_v38", false)) return
        prefs(context).edit().putInt("mode", DashboardMode.NAVIGATION.ordinal)
            .putInt("replica_mode", DashboardMode.NAVIGATION.ordinal)
            .putInt("overlay_mode", DashboardMode.NAVIGATION.ordinal)
            .putBoolean("navigation_default_v38", true).apply()
    }
    fun modes(context: Context) = DashboardMode.choices(style(context))
    private fun pagePreferenceKey(context: Context) = when (style(context)) {
        DashboardStyle.PANORAMA -> "panorama_mode"
        DashboardStyle.REPLICA -> "replica_mode"
        DashboardStyle.OEM_OVERLAY -> "overlay_mode"
        else -> "mode"
    }
    fun mode(context: Context): DashboardMode {
        val saved = prefs(context)
        // Migrate the old shared selection on read; keep each style's selection independent thereafter.
        val fallback = when (style(context)) {
            DashboardStyle.OEM_OVERLAY, DashboardStyle.REPLICA -> DashboardMode.NAVIGATION.ordinal
            else -> saved.getInt("mode", DashboardMode.NAVIGATION.ordinal)
        }
        return DashboardMode.entries.getOrElse(saved.getInt(pagePreferenceKey(context), fallback)) { DashboardMode.ALL }
            .forStyle(style(context))
    }
    fun setMode(context: Context, mode: DashboardMode) {
        prefs(context).edit().putInt(pagePreferenceKey(context), mode.forStyle(style(context)).ordinal).apply()
    }
    fun cycle(context: Context) = mode(context).next(style(context)).also { setMode(context, it) }
    fun step(context: Context, direction: Int) = mode(context).step(style(context), direction).also { setMode(context, it) }
}

/** A short-click callback is already a complete gesture; long-presses and duplicate callbacks do not cycle. */
internal class DashboardModeKey {
    private var lastClick: Long? = null
    fun accept(code: Int, longPress: Boolean, now: Long): Boolean {
        if (code != NativeDashboardSettings.MODE_KEY || longPress) return false
        if (lastClick?.let { now - it in 0 until 350 } == true) return false
        lastClick = now
        return true
    }
}

/** A new stream must deliver a frame before revealing its texture; never reveal a frozen old map. */
internal class DashboardMapFrames {
    private var active = false
    private var fresh = false
    val visible get() = active && fresh
    fun stream(active: Boolean) { this.active = active; if (!active) fresh = false }
    fun frame() { if (active) fresh = true }
    fun resetSurface() { fresh = false }
}
