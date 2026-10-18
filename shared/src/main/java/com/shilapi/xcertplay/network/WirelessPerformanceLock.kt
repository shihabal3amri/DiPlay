package com.shilapi.xcertplay.network

// 本次增加默认关闭的无线会话性能锁。
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/** Optional per-session hints only; never changes network selection or Wi-Fi settings. */
object WirelessPerformanceSettings {
    private const val KEY = "wireless_performance_lock"
    fun enabled(context: Context): Boolean = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}

internal class WirelessPerformanceLock(
    private val modes: List<Int>,
    private val create: (Int) -> Handle,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    interface Handle { fun acquire(); fun release() }
    private val held = mutableListOf<Handle>()
    @Synchronized fun acquire() {
        if (held.isNotEmpty()) return
        for (mode in modes) {
            var handle: Handle? = null
            try {
                handle = create(mode)
                handle.acquire()
                held.add(handle)
            } catch (_: RuntimeException) {
                // A vendor may reject one mode while allowing the other.
                runCatching { handle?.release() }
                log("Wi-Fi performance hint unavailable mode=$mode")
            }
        }
    }
    @Synchronized override fun close() {
        val previous = held.toList()
        held.clear()
        previous.reversed().forEach { runCatching { it.release() } }
    }
    companion object {
        @Suppress("DEPRECATION")
        fun modes(sdk: Int): List<Int> = listOf(WifiManager.WIFI_MODE_FULL_HIGH_PERF) +
            if (sdk >= 29) listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY) else emptyList()
        fun create(context: Context, log: (String) -> Unit): WirelessPerformanceLock =
            WirelessPerformanceLock(modes(Build.VERSION.SDK_INT), { mode ->
                val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = manager.createWifiLock(mode, "DiPlay:wireless-session").apply { setReferenceCounted(false) }
                object : Handle {
                    override fun acquire() = lock.acquire()
                    override fun release() { if (lock.isHeld) lock.release() }
                }
            }, log)
    }
}
