package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** Explicit vehicle selection: never infer permission to publish to DIM from a device name. */
internal object BoyueLSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("boyue_l", Context.MODE_PRIVATE)
    fun selected(context: Context) = prefs(context).getBoolean("selected", false)
    fun select(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("selected", value).apply()
        if (value) migrateFx11ModeBindings(context)
    }
    fun dim(context: Context) = selected(context) && prefs(context).getBoolean("dim", false)
    fun setDim(context: Context, value: Boolean) { prefs(context).edit().putBoolean("dim", value).apply() }
    fun map(context: Context) = selected(context) && prefs(context).getBoolean("map", false)
    fun setMap(context: Context, value: Boolean) { prefs(context).edit().putBoolean("map", value).apply() }
    fun ultra(context: Context) = selected(context) && prefs(context).getBoolean("ultra", false)
    fun setUltra(context: Context, value: Boolean) { prefs(context).edit().putBoolean("ultra", value).apply() }
    fun vehicleData(context: Context) = selected(context) && prefs(context).getBoolean("vehicle_data", false)
    fun setVehicleData(context: Context, value: Boolean) { prefs(context).edit().putBoolean("vehicle_data", value).apply() }
    fun clusterEnabled(context: Context) = NativeDashboardSettings.enabled(context) || map(context) || ultra(context)
    fun wheel(context: Context) = prefs(context).getBoolean("wheel", true)
    fun setWheel(context: Context, value: Boolean) { prefs(context).edit().putBoolean("wheel", value).apply() }
    private fun actionKey(key: Int, longPress: Boolean) = when {
        longPress -> "long_key_$key"
        key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST -> "short_key_$key"
        else -> "key_$key"
    }
    fun action(context: Context, key: Int, longPress: Boolean = false): BoyueWheelAction {
        val saved = prefs(context)
        val value = saved.getString(actionKey(key, longPress), null)
            ?: if (longPress && key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) saved.getString("key_$key", null) else null
        val fallback = when {
            longPress -> if (key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) BoyueWheelAction.SIRI else BoyueWheelAction.NONE
            key == NativeDashboardSettings.modeKey(context) -> BoyueWheelAction.TOGGLE_DASHBOARD
            key != 0 && key == NativeDashboardSettings.pageKey(context) && key !in Fx11MenuWheelPolicy.trackKeys -> BoyueWheelAction.CYCLE_DASHBOARD
            key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST -> BoyueWheelAction.NONE
            else -> BoyueWheelPolicy.defaultAction(key)
        }
        return runCatching { BoyueWheelAction.valueOf(value.orEmpty()) }.getOrDefault(fallback)
    }
    fun hasAction(context: Context, key: Int, longPress: Boolean = false) = prefs(context).contains(actionKey(key, longPress))
    fun setAction(context: Context, key: Int, action: BoyueWheelAction, longPress: Boolean = false) {
        require(key in configurableKeys(context))
        prefs(context).edit().putString(actionKey(key, longPress), action.name).apply()
    }
    fun configurableKeys(context: Context): List<Int> = (listOf(NativeDashboardSettings.modeKey(context),
        Fx11MenuWheelPolicy.MENU_KEY) + GeelyWheelKeyCodes.ALL.toList() +
        listOf(NativeDashboardSettings.pageKey(context)).filter { it != 0 }).distinct()
    val originalOnlyKeys = setOf(Fx11MenuWheelPolicy.MENU_KEY, GeelyWheelKeyCodes.KEY_VOICE_ASSIST)
    fun editableKeys(context: Context) = configurableKeys(context).filterNot { it in originalOnlyKeys }

    fun hasConfiguredPageAction(context: Context) = editableKeys(context).any { key ->
        listOf(false, true).any { longPress -> hasAction(context, key, longPress) &&
            action(context, key, longPress) == BoyueWheelAction.CYCLE_DASHBOARD }
    }

    /** Retire hidden experimental options once on upgrade, before building the next connection. */
    fun migrateDashboardControls(context: Context) {
        migrateFx11ModeBindings(context)
        NativeDashboardSettings.migrateNavigationDefault(context)
        val saved = prefs(context)
        if (saved.getBoolean("dashboard_controls_v36", false)) return
        saved.edit().putBoolean("dashboard_controls_v36", true).putBoolean("map", false)
            .putBoolean("ultra", false).putBoolean("vehicle_data", false).apply()
        if (selected(context)) syncDimForDashboard(context, NativeDashboardSettings.style(context) in
            listOf(DashboardStyle.OFF, DashboardStyle.OEM_OVERLAY))
    }
    /** Preserve explicit calibrations; repair only v36 bindings made against the SDK fallback. */
    private fun migrateFx11ModeBindings(context: Context) {
        if (NativeDashboardSettings.defaultModeKey(context) != NativeDashboardSettings.FX11_MODE_KEY) return
        val dashboard = context.getSharedPreferences("native_dashboard", Context.MODE_PRIVATE)
        if (dashboard.contains("mode_key") || dashboard.getInt("page_key", 0) == NativeDashboardSettings.FX11_MODE_KEY) return
        val saved = prefs(context)
        if (saved.getBoolean("fx11_mode_bindings_v37", false)) return
        val editor = saved.edit()
        for (longPress in listOf(false, true)) {
            val oldKey = actionKey(NativeDashboardSettings.MODE_KEY, longPress)
            val newKey = actionKey(NativeDashboardSettings.FX11_MODE_KEY, longPress)
            if (!saved.contains(newKey) && saved.contains(oldKey)) editor.putString(newKey, saved.getString(oldKey, null))
        }
        editor.putBoolean("fx11_mode_bindings_v37", true).apply()
    }
    fun syncDimForDashboard(context: Context, factory: Boolean) {
        if (dim(context) == factory && selected(context)) return
        setDim(context, factory)
        BoyueDimIntegration.refresh()
    }

    fun display(context: Context): Display? = context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(4)?.takeIf { display ->
            val size = ClusterMapPresentation.sizeOf(display)
            acceptsDisplay(display.displayId, size.x, size.y, display.isValid,
                display.state == Display.STATE_ON, display.flags)
        }

    fun acceptsDisplay(id: Int, width: Int, height: Int, valid: Boolean, on: Boolean, flags: Int) =
        id == 4 && width == 1920 && height == 532 && valid && on &&
            flags and Display.FLAG_PRESENTATION != 0 && flags and Display.FLAG_PRIVATE == 0

    // Exact panel geometry; BYD's scale/round-to-8 policy must not change 532 to 536.
    fun streamConfig() = AirPlayDisplayConfig(1920, 532, fps = 30, primaryInputDevice = 0,
        features = 0, initialUrl = CarPlayClusterDisplay.Content.MAP.url,
        safeArea = AirPlayInsets(), safeAreaDrawOutside = true)
}

internal enum class BoyueWheelAction {
    NONE, PLAY_PAUSE, NEXT, PREVIOUS, SIRI, TOGGLE_DASHBOARD, CYCLE_DASHBOARD;
    val dashboard get() = this == TOGGLE_DASHBOARD || this == CYCLE_DASHBOARD
}

internal object BoyueWheelPolicy {
    fun defaultAction(key: Int) = when (key) {
        GeelyWheelKeyCodes.KEY_PLAY_PAUSE -> BoyueWheelAction.PLAY_PAUSE
        GeelyWheelKeyCodes.KEY_NEXT -> BoyueWheelAction.NEXT
        GeelyWheelKeyCodes.KEY_PREVIOUS -> BoyueWheelAction.PREVIOUS
        GeelyWheelKeyCodes.KEY_VOICE_ASSIST -> BoyueWheelAction.SIRI
        else -> BoyueWheelAction.NONE
    }

    fun accepts(key: Int, longPress: Boolean, bluetoothAudio: Boolean): Boolean =
        key in GeelyWheelKeyCodes.ALL &&
            longPress == (key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST) &&
            (!bluetoothAudio || key == GeelyWheelKeyCodes.KEY_VOICE_ASSIST)
}
