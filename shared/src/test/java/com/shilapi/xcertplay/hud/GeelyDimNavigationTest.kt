package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class GeelyDimNavigationTest {
    private fun tlv(type: Int, vararg value: Int) = byteArrayOf(0, (value.size + 4).toByte(),
        (type shr 8).toByte(), type.toByte()) + value.map(Int::toByte).toByteArray()

    @Test fun disconnectClearsRouteAndOldManeuversCannotReappear() {
        GeelyDimNavigation.clear()
        GeelyDimNavigation.onFrame(Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(1, 0, 7) + tlv(3, 1) + tlv(8, 0)))
        val active = Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(1, 1) + tlv(10, 0, 0, 0, 150) + tlv(13, 0, 7))
        GeelyDimNavigation.onFrame(active)
        assertEquals(150, GeelyDimNavigation.snapshot()!!.distanceMeters)
        GeelyDimNavigation.clear()
        assertNull(GeelyDimNavigation.snapshot())
        GeelyDimNavigation.onFrame(active)
        assertNull(GeelyDimNavigation.snapshot())
        GeelyDimNavigation.clear()
    }
}
