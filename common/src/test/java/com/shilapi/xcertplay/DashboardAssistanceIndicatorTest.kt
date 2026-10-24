package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.VehicleReading
import com.shilapi.xcertplay.ultra.VehicleSignal
import org.junit.Assert.*
import org.junit.Test

class DashboardAssistanceIndicatorTest {
    private fun snapshot(mode: String, state: Int, lateral: Boolean? = null): DashboardSnapshot =
        DashboardSnapshot(buildMap {
            put(VehicleSignal.ASSIST_MODE, VehicleReading(mode, 0, 0))
            put(VehicleSignal.ACC_STATE, VehicleReading(state, 0, 0))
            lateral?.let { put(VehicleSignal.LANE_CENTERING_ACTIVE, VehicleReading(it, 0, 0)) }
        })

    @Test fun longitudinalActiveDoesNotClaimGpilotLateralControl() {
        assertFalse(snapshot("GPILOT", 2).assistanceIconActive)
        assertFalse(snapshot("GPILOT", 2, false).assistanceIconActive)
        assertTrue(snapshot("GPILOT", 2, true).assistanceIconActive)
        assertFalse(snapshot("GPILOT", 1, true).assistanceIconActive)
        assertFalse(snapshot("GPILOT", 7, true).assistanceIconActive)
    }

    @Test fun adaptiveCruiseCanShowConfirmedLongitudinalControlWithoutLateral() {
        assertTrue(snapshot("ACC", 2).assistanceIconActive)
        assertFalse(snapshot("ACC", 1).assistanceIconActive)
        assertFalse(snapshot("UNKNOWN", 2, true).assistanceIconActive)
    }
}
