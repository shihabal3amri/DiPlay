package com.shilapi.xcertplay.gac

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.wifiFrequencyMhzToChannel
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 8.0/8.1 (API 26-27) Wi-Fi Direct (P2P Group Owner) implementation
 * specifically tailored for the GAC Hycan Z03 head unit.
 *
 * Uses autonomous group creation (`WifiP2pManager.createGroup`) available on Android 8.1,
 * and extracts the framework-generated network name (DIRECT-xx-...) and WPA2 passphrase
 * without depending on Android 10+ builder APIs.
 */
class GACAndroid8P2pManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
) : WirelessHotspotManager {

    private val appContext = context.applicationContext
    private val p2pManager: WifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IllegalStateException("WifiP2pManager is unavailable on this device")

    private val lock = Any()
    private var callbackThread: HandlerThread? = null
    private var channel: WifiP2pManager.Channel? = null
    private var activeGroup: WifiP2pGroup? = null
    private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "GACAndroid8P2pManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val thread = HandlerThread("gac-hycan-p2p").apply { start() }
        synchronized(lock) {
            check(!closed) { "GACAndroid8P2pManager is closed" }
            callbackThread = thread
        }

        val p2pChannel = p2pManager.initialize(appContext, thread.looper) {
            diagnostic("GAC P2P channel disconnected")
        }
        synchronized(lock) {
            channel = p2pChannel
        }

        diagnostic("GAC Hycan: Starting Android 8.1 Autonomous P2P Group Owner...")

        // Step 0: Ensure Wi-Fi radio is enabled
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wifiManager?.isWifiEnabled == false) {
            diagnostic("GAC Hycan: Enabling Wi-Fi radio for Wi-Fi Direct...")
            runCatching { wifiManager.isWifiEnabled = true }
            Thread.sleep(1000)
        }

        // Step 1 & 2: Clear existing group and request group creation with retry
        var createSuccess = false
        var lastError: String? = null
        for (attempt in 1..3) {
            synchronized(lock) {
                if (closed) throw IOException("GACAndroid8P2pManager was closed")
            }

            // Clear old group
            val removeLatch = CountDownLatch(1)
            p2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { removeLatch.countDown() }
                override fun onFailure(reason: Int) { removeLatch.countDown() }
            })
            removeLatch.await(800, TimeUnit.MILLISECONDS)

            val createLatch = CountDownLatch(1)
            var createFailureReason: Int? = null
            p2pManager.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    diagnostic("GAC Hycan: P2P createGroup succeeded on attempt $attempt")
                    createLatch.countDown()
                }

                override fun onFailure(reason: Int) {
                    createFailureReason = reason
                    diagnostic("GAC Hycan: P2P createGroup attempt $attempt failed with reason $reason")
                    createLatch.countDown()
                }
            })

            createLatch.await(4000, TimeUnit.MILLISECONDS)
            if (createFailureReason == null) {
                createSuccess = true
                break
            } else {
                lastError = "P2P createGroup failed (reason $createFailureReason)"
                Thread.sleep(600)
            }
        }

        if (!createSuccess) {
            throw IOException(lastError ?: "P2P group creation timed out after 3 attempts")
        }

        // Step 3: Poll for Group Info until SSID and passphrase are populated
        val deadline = System.currentTimeMillis() + timeoutMillis
        var group: WifiP2pGroup? = null
        val groupRef = AtomicReference<WifiP2pGroup?>()

        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                if (closed) throw IOException("GACAndroid8P2pManager was closed during startup")
            }

            val queryLatch = CountDownLatch(1)
            p2pManager.requestGroupInfo(p2pChannel) { info ->
                if (info != null && info.isGroupOwner && !info.networkName.isNullOrBlank()) {
                    groupRef.set(info)
                }
                queryLatch.countDown()
            }
            queryLatch.await(1000, TimeUnit.MILLISECONDS)

            val candidate = groupRef.get()
            if (candidate != null) {
                val pass = candidate.passphrase?.takeIf { it.isNotBlank() } ?: extractPassphrase(candidate)
                if (!pass.isNullOrBlank()) {
                    group = candidate
                    break
                }
            }
            Thread.sleep(300)
        }

        val resolvedGroup = group ?: groupRef.get() ?: throw IOException(
            "GAC Hycan P2P group created but credentials were not available within ${timeoutMillis}ms"
        )
        synchronized(lock) {
            activeGroup = resolvedGroup
        }

        val ssid = resolvedGroup.networkName
        val passphrase = resolvedGroup.passphrase?.takeIf { it.isNotBlank() }
            ?: extractPassphrase(resolvedGroup)
            ?: ""
        val ifaceName = resolvedGroup.`interface` ?: "p2p0"
        diagnostic("GAC Hycan: P2P group active. SSID='$ssid', Interface='$ifaceName'")

        // Step 4: Resolve network address and BSSID with retry for IP binding
        val hostAddress = awaitHostAddress(ifaceName, 2000)
        val bssid = resolveInterfaceMac(ifaceName) ?: GACHycanConfiguration.resolveWifiMacAddress()

        // Frequency detection on Android 8.1 / Renesas platform
        val frequency = extractFrequency(resolvedGroup)
        val channelNum = wifiFrequencyMhzToChannel(frequency) ?: GACHycanConfiguration.P2P_DEFAULT_CHANNEL
        val bandLabel = if (frequency > 5000) "5 GHz" else GACHycanConfiguration.P2P_DEFAULT_BAND_LABEL

        diagnostic("GAC Hycan: P2P credentials ready for iPhone CarPlay negotiation (host=$hostAddress, bssid=$bssid, channel=$channelNum, freq=${frequency}MHz)")

        return WirelessHotspotInfo(
            ssid = ssid,
            passphrase = passphrase,
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = channelNum,
            frequencyMHz = frequency,
            bssid = bssid,
            interfaceName = ifaceName,
            hostAddress = hostAddress,
            bandLabel = bandLabel,
            backend = WirelessHotspotBackend.WIFI_P2P,
        )
    }

    private fun awaitHostAddress(interfaceName: String, timeoutMillis: Long): InetAddress {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val addr = resolveHostAddress(interfaceName)
            if (addr.hostAddress != GACHycanConfiguration.P2P_DEFAULT_HOST_IP) {
                return addr
            }
            Thread.sleep(200)
        }
        return resolveHostAddress(interfaceName)
    }

    private fun extractPassphrase(group: WifiP2pGroup): String? {
        return runCatching {
            val field = group.javaClass.getDeclaredField("mPassphrase")
            field.isAccessible = true
            field.get(group) as? String
        }.getOrNull()
    }

    private fun extractFrequency(group: WifiP2pGroup): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            val freq = runCatching { group.frequency }.getOrNull()
            if (freq != null && freq > 0) return freq
        }
        val freqFromReflection = runCatching {
            val method = group.javaClass.getMethod("getFrequency")
            method.invoke(group) as? Int
        }.getOrNull()
        if (freqFromReflection != null && freqFromReflection > 0) {
            return freqFromReflection
        }
        return 2437 // Default 2.4 GHz channel 6
    }

    private fun resolveHostAddress(interfaceName: String): InetAddress {
        try {
            val iface = NetworkInterface.getByName(interfaceName)
            if (iface != null) {
                val ipv4 = iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                if (ipv4 != null) return ipv4
            }
        } catch (e: Exception) {
            diagnostic("Could not resolve host address for $interfaceName: ${e.message}")
        }
        return InetAddress.getByName(GACHycanConfiguration.P2P_DEFAULT_HOST_IP)
    }

    private fun resolveInterfaceMac(interfaceName: String): String? {
        return try {
            val iface = NetworkInterface.getByName(interfaceName)
            val bytes = iface?.hardwareAddress ?: return null
            bytes.joinToString(":") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        val p2pChannel: WifiP2pManager.Channel?
        val thread: HandlerThread?
        synchronized(lock) {
            if (closed) return
            closed = true
            p2pChannel = channel
            channel = null
            thread = callbackThread
            callbackThread = null
            activeGroup = null
        }

        if (p2pChannel != null) {
            try {
                p2pManager.removeGroup(p2pChannel, null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    p2pChannel.close()
                }
            } catch (e: Exception) {
                Log.w(GACHycanConfiguration.TAG, "Error cleaning up P2P group", e)
            }
        }

        thread?.quitSafely()
        diagnostic("GAC Hycan: P2P manager closed and resources released")
    }
}
