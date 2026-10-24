package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.ultra.Fx11CarPlayProvider
import com.shilapi.xcertplay.ultra.Fx11VehicleSource
import com.shilapi.xcertplay.ultra.VehicleSignal

internal object BoyueVehicleIntegration {
    @Volatile private var provider: Fx11CarPlayProvider? = null
    @Volatile private var source: Fx11VehicleSource? = null

    fun create(context: Context): Fx11CarPlayProvider? {
        if (!BoyueLSettings.vehicleData(context)) return null
        return Fx11CarPlayProvider({ Fx11VehicleSource(setOf(VehicleSignal.SPEED_KPH, VehicleSignal.GEAR, VehicleSignal.AMBIENT_C)).also { source = it } }, SystemClock::elapsedRealtime)
            .also { provider = it }
    }
    fun diagnosticReport(): String = listOfNotNull(provider?.diagnosticReport() ?: "FX11 CarPlay data disabled", source?.status)
        .joinToString("\n")
}
