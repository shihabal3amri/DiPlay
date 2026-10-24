package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiIdentityStore
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false
    @Volatile private var authenticationStatus = "not_checked"

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        BoyueLSettings.migrateDashboardControls(context)
        AirPlayPersistence.migrateFx11MusicBuffer(context)
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        try {
            authenticationStatus = LocalMfiIdentityStore.ensure(target) { name ->
                context.assets.open("offline-mfi/$name")
            }.name
            android.util.Log.i("DiPlaySetup", "Local authentication: $authenticationStatus")
        } catch (error: Exception) {
            authenticationStatus = "failed:${error.javaClass.simpleName}"
            throw error
        }
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    /** Metadata only: never export private keys, certificates or their encoded content. */
    fun authenticationDiagnostic(context: Context): String = buildString {
        appendLine("Authentication target: ${AirPlayPersistence.loadMfiTarget(context)}")
        appendLine("Authentication bootstrap: $authenticationStatus")
        val directory = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        for (name in listOf("identity.pk8", "certificate.p7b")) {
            val bundled = try {
                context.assets.open("offline-mfi/$name").use { }
                "present"
            } catch (_: Exception) {
                "unavailable"
            }
            val file = File(directory, name)
            appendLine("Authentication $name: bundled=$bundled installed=${file.isFile} bytes=${file.length()}")
        }
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal enum class DefaultConnectionMode(val key: String) {
    LAST_USED("last_used"), WIRELESS("wireless"), USB("usb");

    fun wireless(lastUsedWireless: Boolean): Boolean = when (this) {
        LAST_USED -> lastUsedWireless
        WIRELESS -> true
        USB -> false
    }

    companion object {
        fun fromKey(key: String?): DefaultConnectionMode = entries.firstOrNull { it.key == key } ?: LAST_USED
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun defaultConnectionMode(context: Context) =
        DefaultConnectionMode.fromKey(prefs(context).getString("default_connection_mode", null))
    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) {
        prefs(context).edit().putString("default_connection_mode", mode.key).apply()
    }
    fun autoConnectWireless(context: Context) =
        defaultConnectionMode(context).wireless(AirPlayPersistence.loadWirelessEnabled(context))
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
    fun connectOnPhoneBluetooth(context: Context) = prefs(context).getBoolean("connect_on_phone_bluetooth", false)
    fun saveConnectOnPhoneBluetooth(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("connect_on_phone_bluetooth", value).apply()
    }
}
