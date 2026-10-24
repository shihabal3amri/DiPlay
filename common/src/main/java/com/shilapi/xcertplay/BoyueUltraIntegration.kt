package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.ultra.*
import java.io.File

internal object BoyueUltraIntegration {
    @Volatile var status = "Ultra inactive"
        private set
    @Volatile private var lastSource: Fx11VehicleSource? = null
    fun config(context: Context, display: AirPlayDisplayConfig?): AirPlayUltraConfig? {
        if (NativeDashboardSettings.enabled(context) || !BoyueLSettings.ultra(context)) { status = "Ultra disabled"; return null }
        if (display == null) { status = "Ultra unavailable: Display 4 must be ON, public, 1920x532"; return null }
        val file = File(context.filesDir, "ultra/fx11-caf-profile.bplist")
        val profile = if (file.isFile) {
            runCatching {
                require(file.length() in 1..524288) { "Invalid CAF profile size" }
                UltraVehicleProfile.decode(file.readBytes())
            }.getOrElse { status = "Ultra disabled: invalid CAF profile (${it.message})"; return null }
        } else UltraVehicleProfile.negotiationOnly()
        status = if (profile.bindings.isEmpty()) "Ultra negotiation only; native gauge CAF schema missing; live data will not reach native gauges"
            else "Ultra configured: ${profile.bindings.size} CAF bindings; iPhone/display acceptance unverified"
        val provisioning = Fx11UltraProvisioning.create(profile, sourceFactory = {
            Fx11VehicleSource().also { lastSource = it }
        }, diagnostic = { android.util.Log.i("DiPlay-Ultra", it) })
        val runtime = provisioning.createRuntime()
        return provisioning.createConfig(display, runtime)
    }
    fun diagnosticReport(): String = buildString {
        appendLine(status)
        appendLine("Ultra Local UI assets: not implemented; independent native dashboard available in settings")
        lastSource?.let { source ->
            appendLine(source.status)
            source.snapshot().forEach { (signal, reading) ->
                appendLine("${signal.name}=${reading.value} ${signal.unit} prop=${reading.propertyId}")
            }
        }
    }
}
