package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import android.provider.Settings
import androidx.annotation.StringRes
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface ConnectionProblem {
    data class Cause(val cause: ConnectionFailureCause) : ConnectionProblem
    data class Startup(val failure: WirelessStartupFailure) : ConnectionProblem
    data class UsbNotListed(val otherDevice: Boolean) : ConnectionProblem

    /**
     * Video while parked is on, but the gear cannot be read, so video stays off (#1180). Not a connection
     * failure: it happens while CarPlay runs, so it is shown during a session too.
     */
    data class VideoGearUnreadable(val adb: LocalAdb.Access?, val replied: Boolean) : ConnectionProblem {
        /** What the driver can do about it, from the ADB texts the Vehicle settings already show. */
        @StringRes
        fun hint(context: Context): Int = when (adb) {
            LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
            LocalAdb.Access.UNREACHABLE ->
                // #1002: the developer-options switch can stay on while nothing listens on 5555.
                if (runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) }
                        .getOrDefault(0) == 1) R.string.adb_network_not_answering else R.string.adb_off
            LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
            LocalAdb.Access.READY -> if (replied) R.string.adb_vehicle_gear_unreadable else R.string.adb_check_failed
            null -> R.string.adb_not_checked
        }
    }

    @StringRes
    fun line(): Int = when (this) {
        is Cause -> ConnectionFailureGuidance.stage(cause)
        is Startup -> when (failure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> R.string.hotspot_network_not_ready
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> R.string.first_tcp_timeout
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> R.string.wifi_direct_refused_beside_hotspot
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> R.string.connection_help_bluetooth_unusable
        }
        is UsbNotListed -> if (otherDevice) R.string.car_sees_usb_device_not_iphone else R.string.car_sees_no_usb_device
        is VideoGearUnreadable -> R.string.video_off_gear_unreadable
    }

    val token: String get() = when (this) {
        is Cause -> "cause:${cause.name}"
        is Startup -> "startup:${failure.name}"
        is UsbNotListed -> if (otherDevice) "usb:other" else "usb:none"
        is VideoGearUnreadable -> "video:gear:${adb?.name ?: "unknown"}${if (replied) ":replied" else ""}"
    }
}

internal object LastConnectionProblem {
    data class Record(val problem: ConnectionProblem, val atMillis: Long)

    private const val PREFS = "diplay_last_problem"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_AT = "at"
    private const val VIDEO_PREFIX = "video:"

    fun follow(context: Context, status: CarPlayStatus) {
        val problem = when (status) {
            is CarPlayStatus.Failed -> status.knownCause?.let(ConnectionProblem::Cause)
                ?: status.startupFailure?.let(ConnectionProblem::Startup)
                ?: return clear(context)
            is CarPlayStatus.IphoneNotListed -> ConnectionProblem.UsbNotListed(status.nonHubDeviceListed)
            else -> return
        }
        prefs(context).edit().putString(KEY_PROBLEM, problem.token).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /** Video while parked stays off because the gear cannot be read (#1180). Gate thread; written on a change only. */
    fun videoGearUnreadable(context: Context, adb: LocalAdb.Access?, replied: Boolean) {
        val problem = ConnectionProblem.VideoGearUnreadable(adb, replied)
        if (prefs(context).getString(KEY_PROBLEM, null) == problem.token) return
        prefs(context).edit().putString(KEY_PROBLEM, problem.token).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    /** The gear reads again; drops only a video record, never a connection problem. */
    fun videoGearReadable(context: Context) {
        if (videoRecord(context)) clear(context)
    }

    /**
     * A session became active, which answers a connection problem. The video gate reads the gear as the
     * controller starts, before that, so a video record stays.
     */
    fun sessionStarted(context: Context) {
        if (!videoRecord(context)) clear(context)
    }

    private fun videoRecord(context: Context): Boolean =
        prefs(context).getString(KEY_PROBLEM, null)?.startsWith(VIDEO_PREFIX) == true

    fun load(context: Context): Record? {
        val prefs = prefs(context)
        val token = prefs.getString(KEY_PROBLEM, null) ?: return null
        val problem = parse(token)
        val at = prefs.getLong(KEY_AT, -1)
        if (problem == null || at < 0) {
            clear(context)
            return null
        }
        return Record(problem, at)
    }

    /** While CarPlay runs only a problem of the running session shows: video that stays off. */
    fun homeLine(context: Context, sessionActive: Boolean = false): String? {
        val record = load(context) ?: return null
        val problem = record.problem
        if (problem is ConnectionProblem.VideoGearUnreadable) {
            // Turning the setting off answers it.
            if (!BydOutputSettings.videoWhileParked(context)) return null
            return context.getString(problem.line()) + " " + context.getString(problem.hint(context))
        }
        if (sessionActive) return null
        val time = DateUtils.formatSameDayTime(record.atMillis, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT)
        return context.getString(R.string.last_connection_attempt, time, context.getString(problem.line()))
    }

    fun report(context: Context): String {
        val record = load(context) ?: return "Last problem: none recorded"
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).resources
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(record.atMillis))
        val problem = record.problem
        val text = english.getString(problem.line()) +
            if (problem is ConnectionProblem.VideoGearUnreadable) " " + english.getString(problem.hint(context)) else ""
        return "Last problem: $text (${problem.token}) at $at"
    }

    private fun parse(token: String): ConnectionProblem? {
        val value = token.substringAfter(':')
        return when (token.substringBefore(':')) {
            "cause" -> ConnectionFailureCause.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Cause)
            "startup" -> WirelessStartupFailure.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Startup)
            "video" -> value.split(':').takeIf { it.firstOrNull() == "gear" }?.let { parts ->
                val adb = parts.getOrNull(1)
                ConnectionProblem.VideoGearUnreadable(
                    LocalAdb.Access.entries.firstOrNull { it.name == adb }
                        ?: if (adb == "unknown") null else return null,
                    replied = parts.getOrNull(2) == "replied",
                )
            }
            "usb" -> when (value) {
                "none" -> ConnectionProblem.UsbNotListed(false)
                "other" -> ConnectionProblem.UsbNotListed(true)
                else -> null
            }
            else -> null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
