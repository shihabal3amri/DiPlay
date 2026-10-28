package com.shilapi.xcertplay.capabilities

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeadUnitCapabilitiesTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPackageManager: PackageManager

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        `when`(mockPackageManager.hasSystemFeature(anyString())).thenReturn(false)
    }

    @Test
    fun defaultHeadUnitCapabilities_usesGenericFallback() {
        val caps = HeadUnitCapabilities()
        assertEquals(HeadUnitVendor.GENERIC_ANDROID, caps.vendor)
        assertTrue(caps.isGenericFallback)
        assertTrue(caps.display.supportsHardwareDecoding)
        assertFalse(caps.supportsVehicleNavigationIntegration)
    }

    @Test
    fun genericDetection_returnsGenericVendor() {
        val caps = HeadUnitCapabilityDetector.detect(mockContext)
        assertEquals(HeadUnitVendor.GENERIC_ANDROID, caps.vendor)
        assertTrue(caps.isGenericFallback)
    }

    @Test
    fun bydCapabilities_enablesVehicleNavigation() {
        val caps = HeadUnitCapabilities(
            vendor = HeadUnitVendor.BYD,
            supportsVehicleNavigationIntegration = true,
            supportsSteeringWheelKeys = true
        )
        assertEquals(HeadUnitVendor.BYD, caps.vendor)
        assertFalse(caps.isGenericFallback)
        assertTrue(caps.supportsVehicleNavigationIntegration)
        assertTrue(caps.supportsSteeringWheelKeys)
    }

    @Test
    fun geelyCapabilities_enablesSteeringWheelKeys() {
        val caps = HeadUnitCapabilities(
            vendor = HeadUnitVendor.GEELY_LYNK,
            supportsSteeringWheelKeys = true
        )
        assertEquals(HeadUnitVendor.GEELY_LYNK, caps.vendor)
        assertFalse(caps.isGenericFallback)
        assertTrue(caps.supportsSteeringWheelKeys)
        assertFalse(caps.supportsVehicleNavigationIntegration)
    }
}
