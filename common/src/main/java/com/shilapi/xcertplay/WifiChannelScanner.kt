package com.shilapi.xcertplay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.network.WifiChannelRanker

/**
 * One-shot 2.4 GHz Wi-Fi scan for the channel scanner next to the Wi-Fi Direct
 * channel control. The ranking itself lives in [WifiChannelRanker]; this class only
 * talks to Android's WifiManager and translates scan results into ranked channels.
 *
 * Android only returns scan results when precise location permission is granted and
 * location services are on, so a scan without those reports [Outcome.NeedsLocation]
 * instead of an empty result. When the car's Wi-Fi client is off, the scan reports
 * [Outcome.WifiOff] instead of asking the driver to try again. A refused or
 * timed-out scan reports [Outcome.ScanFailed]; note that [WifiManager.getScanResults]
 * may return cached results from an earlier scan, so a successful scan can still
 * describe the neighbourhood a minute ago.
 */
class WifiChannelScanner(context: Context) {
    /** The application context: the receiver and the 12 s timeout outlive the Activity. */
    private val appContext: Context = context.applicationContext

    sealed interface Outcome {
        /** Location permission or location services are off, so Android returns no results. */
        data object NeedsLocation : Outcome
        /** The car's Wi-Fi client is off (car hotspot mode keeps it off), so no scan can run. */
        data object WifiOff : Outcome
        /** startScan() was refused (throttled or the radio is busy) or no result arrived. */
        data object ScanFailed : Outcome
        data class Scanned(
            val ranking: List<WifiChannelRanker.ChannelScore>,
            val recommendedChannel: Int?,
        ) : Outcome
    }

    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val handler = Handler(Looper.getMainLooper())

    /** True when Android will hand this app scan results. */
    fun hasLocationAccess(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED && isLocationOn()

    private fun isLocationOn(): Boolean {
        val locationManager =
            appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    /**
     * Starts one scan and calls [onOutcome] once with the result, on the main thread.
     * A second call while a scan is running is ignored.
     */
    fun scanOnce(onOutcome: (Outcome) -> Unit) {
        val manager = wifiManager ?: return onOutcome(Outcome.ScanFailed)
        if (!manager.isWifiEnabled) return onOutcome(Outcome.WifiOff)
        if (!hasLocationAccess()) return onOutcome(Outcome.NeedsLocation)
        if (scanning) return
        scanning = true
        var done = false
        fun finish(outcome: Outcome) {
            if (done) return
            done = true
            scanning = false
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            runCatching { appContext.unregisterReceiver(receiver) }
            onOutcome(outcome)
        }
        timeoutRunnable = Runnable {
            finish(scannedOutcome(manager))
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                finish(scannedOutcome(manager))
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
        } catch (e: SecurityException) {
            finish(Outcome.ScanFailed)
            return
        }
        handler.postDelayed(timeoutRunnable!!, SCAN_TIMEOUT_MILLIS)
        @Suppress("DEPRECATION")
        if (!manager.startScan()) {
            finish(Outcome.ScanFailed)
        }
    }

    private fun scannedOutcome(manager: WifiManager): Outcome {
        val observations = runCatching { manager.scanResults }.getOrNull().orEmpty()
            .mapNotNull { result ->
                WifiChannelRanker.channelForFrequencyMhz(result.frequency)?.let { channel ->
                    WifiChannelRanker.Observation(channel, result.level)
                }
            }
        val ranking = WifiChannelRanker.rank(observations)
        return Outcome.Scanned(ranking, WifiChannelRanker.recommend(observations))
    }

    private var scanning = false
    private var receiver: BroadcastReceiver? = null
    private var timeoutRunnable: Runnable? = null

    companion object {
        /** Long enough for a full 2.4 GHz sweep on a slow head unit, short enough to stay useful. */
        const val SCAN_TIMEOUT_MILLIS = 12_000L
    }
}
