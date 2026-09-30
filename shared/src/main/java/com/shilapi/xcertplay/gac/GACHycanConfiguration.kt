package com.shilapi.xcertplay.gac

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.network.WirelessHotspotManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.util.Collections

/**
 * Dedicated hardware profile and connectivity configuration for the GAC Hycan Z03
 * head unit (Model: G6SA-r8a7796, SoC: Renesas R-Car H3/M3, Android 8.1.0 API 27).
 *
 * Provides Android 8-specific implementations for:
 * 1. Bluetooth MAC address extraction and RFCOMM handshake negotiation.
 * 2. Autonomous Wi-Fi Direct (P2P Group Owner) handshake protocol without Android 10 APIs.
 * 3. Hardware specifications (1920x1080 @ 160dpi, 305mm physical basis, R-Car hardware decoder).
 */
object GACHycanConfiguration {
    const val TAG = "GACHycanConfig"

    // Vehicle & Hardware Specifications
    const val BRAND_NAME = "GAC"
    const val VEHICLE_MODEL = "Hycan Z03"
    const val HARDWARE_PLATFORM = "G6SA-r8a7796"
    const val CHIPSET = "Renesas R-Car H3/M3 (r8a7796)"
    const val KERNEL_TARGET = "4.14.86+"
    const val TARGET_ANDROID_VERSION = "8.1.0"
    const val TARGET_API_LEVEL = 27

    // Display & Graphics Specifications
    const val DISPLAY_WIDTH_PIXELS = 1920
    const val DISPLAY_HEIGHT_PIXELS = 1080
    const val DISPLAY_DENSITY_DPI = 160 // mdpi (1.0x scale)
    const val DISPLAY_PHYSICAL_WIDTH_MM = 305 // ~12 inch diagonal widescreen
    const val DISPLAY_PHYSICAL_HEIGHT_MM = 171
    const val DEFAULT_FRAME_RATE = 30 // Rock-solid on Renesas R-Car AVC hardware decoder

    // Connectivity Specifications
    const val DEFAULT_HARDWARE_MAC = "a0:cd:f3:69:ef:4a"
    const val GAC_MAC_OUI = "a0:cd:f3"
    const val P2P_DEFAULT_HOST_IP = "192.168.49.1"
    const val P2P_DEFAULT_BAND_LABEL = "2.4 GHz"
    const val P2P_DEFAULT_CHANNEL = 6

    /**
     * Determines whether the current device is a GAC Hycan Z03 or compatible G6SA platform.
     */
    fun isGacHycan(): Boolean {
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val board = Build.BOARD.orEmpty()
        val device = Build.DEVICE.orEmpty()

        return brand.contains("GAC", ignoreCase = true) ||
            manufacturer.contains("GAC", ignoreCase = true) ||
            model.contains("G6SA", ignoreCase = true) ||
            model.contains("Hycan", ignoreCase = true) ||
            hardware.contains("r8a7796", ignoreCase = true) ||
            board.contains("g6sa", ignoreCase = true) ||
            device.contains("g6sa", ignoreCase = true)
    }

    /**
     * Checks if the device runs Android 8.0 or 8.1 (API 26-27).
     */
    fun isAndroid8(): Boolean = Build.VERSION.SDK_INT in 26..27

    /**
     * Retrieves the real Bluetooth MAC address on Android 8.1.
     *
     * In Android 8+, BluetoothAdapter.getAddress() returns a dummy 02:00:00:00:00:00.
     * This method traverses:
     * 1. Settings.Secure "bluetooth_address"
     * 2. Hidden reflection on IBluetooth / BluetoothAdapter.getRawAddress
     * 3. System properties (persist.vendor.service.bdroid.bdaddr, ro.boot.btmacaddr, etc.)
     * 4. Known GAC default MAC fallback
     */
    fun resolveBluetoothAddress(context: Context): String? {
        val macRegex = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")

        // Strategy 1: Settings.Secure
        val fromSettings = runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        if (fromSettings != null && macRegex.matches(fromSettings) && !isDummyMac(fromSettings)) {
            Log.d(TAG, "Bluetooth address resolved from Settings.Secure: $fromSettings")
            return fromSettings.lowercase()
        }

        // Strategy 2: System Properties on Android 8 / Renesas R-Car
        val propKeys = listOf(
            "persist.vendor.service.bdroid.bdaddr",
            "persist.vendor.bt.address",
            "ro.boot.btmacaddr",
            "ro.bt.bdaddr_path",
            "net.bluetooth.mac",
        )
        for (key in propKeys) {
            val fromProp = readSystemProperty(key)
            if (fromProp != null && macRegex.matches(fromProp) && !isDummyMac(fromProp)) {
                Log.d(TAG, "Bluetooth address resolved from property $key: $fromProp")
                return fromProp.lowercase()
            }
        }

        // Strategy 3: Hidden Reflection on BluetoothAdapter
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter != null) {
            val fromReflection = runCatching {
                val serviceField = BluetoothAdapter::class.java.getDeclaredField("mService")
                serviceField.isAccessible = true
                val bluetoothService = serviceField.get(adapter)
                val getAddressMethod = bluetoothService?.javaClass?.getMethod("getAddress")
                getAddressMethod?.invoke(bluetoothService) as? String
            }.getOrNull()
            if (fromReflection != null && macRegex.matches(fromReflection) && !isDummyMac(fromReflection)) {
                Log.d(TAG, "Bluetooth address resolved via mService reflection: $fromReflection")
                return fromReflection.lowercase()
            }
        }

        // Strategy 4: Fallback for GAC Hycan environment
        if (isGacHycan()) {
            Log.i(TAG, "Using GAC Hycan default MAC profile: $DEFAULT_HARDWARE_MAC")
            return DEFAULT_HARDWARE_MAC
        }

        return null
    }

    /**
     * Resolves the Wi-Fi hardware MAC address for the GAC head unit interface.
     */
    fun resolveWifiMacAddress(): String {
        val macRegex = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("p2p") || iface.name.startsWith("ap")) {
                    val bytes = iface.hardwareAddress ?: continue
                    val mac = bytes.joinToString(":") { "%02x".format(it) }
                    if (macRegex.matches(mac) && !isDummyMac(mac)) {
                        return mac
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query network interfaces for Wi-Fi MAC", e)
        }
        return DEFAULT_HARDWARE_MAC
    }

    /**
     * Instantiates an Android 8-specific Wi-Fi Direct (P2P Group Owner) manager
     * designed for the GAC Hycan Z03 and Android 8.1 firmware.
     */
    fun createAndroid8P2pManager(
        context: Context,
        diagnostic: (String) -> Unit = {},
    ): WirelessHotspotManager = GACAndroid8P2pManager(context, diagnostic)

    private fun isDummyMac(mac: String): Boolean =
        mac.equals("02:00:00:00:00:00", ignoreCase = true) ||
            mac.equals("00:00:00:00:00:00", ignoreCase = true) ||
            mac.equals("ff:ff:ff:ff:ff:ff", ignoreCase = true)

    private fun readSystemProperty(propName: String): String? {
        return try {
            val getMethod = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
            val value = getMethod.invoke(null, propName) as? String
            value?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", propName))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val line = reader.readLine()?.trim()
                reader.close()
                line?.takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Generates a descriptive diagnostic summary of the GAC Hycan environment.
     */
    fun getDiagnosticsSummary(context: Context): String = buildString {
        appendLine("--- GAC Hycan Z03 Configuration ---")
        appendLine("Detected GAC Platform: ${isGacHycan()}")
        appendLine("Model: ${Build.MODEL} (Hardware: ${Build.HARDWARE}, Board: ${Build.BOARD})")
        appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Display: ${DISPLAY_WIDTH_PIXELS}x$DISPLAY_HEIGHT_PIXELS (${DISPLAY_DENSITY_DPI}dpi)")
        appendLine("Bluetooth Address: ${resolveBluetoothAddress(context) ?: "unavailable"}")
        appendLine("Wi-Fi Interface MAC: ${resolveWifiMacAddress()}")
        appendLine("Wi-Fi Direct Protocol: Android 8 Autonomous Group Owner (API 27)")
    }
}
