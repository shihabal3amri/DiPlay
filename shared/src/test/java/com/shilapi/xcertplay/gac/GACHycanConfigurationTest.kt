package com.shilapi.xcertplay.gac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GACHycanConfigurationTest {

    @Test
    fun verifyGacHardwareSpecs() {
        assertEquals("GAC", GACHycanConfiguration.BRAND_NAME)
        assertEquals("Hycan Z03", GACHycanConfiguration.VEHICLE_MODEL)
        assertEquals("G6SA-r8a7796", GACHycanConfiguration.HARDWARE_PLATFORM)
        assertEquals(1920, GACHycanConfiguration.DISPLAY_WIDTH_PIXELS)
        assertEquals(1080, GACHycanConfiguration.DISPLAY_HEIGHT_PIXELS)
        assertEquals(160, GACHycanConfiguration.DISPLAY_DENSITY_DPI)
        assertEquals(305, GACHycanConfiguration.DISPLAY_PHYSICAL_WIDTH_MM)
        assertEquals(171, GACHycanConfiguration.DISPLAY_PHYSICAL_HEIGHT_MM)
        assertEquals("a0:cd:f3:69:ef:4a", GACHycanConfiguration.DEFAULT_HARDWARE_MAC)
        assertEquals("a0:cd:f3", GACHycanConfiguration.GAC_MAC_OUI)
    }

    @Test
    fun verifyDiagnosticSummaryGeneratesOutput() {
        val context = RuntimeEnvironment.getApplication()
        val summary = GACHycanConfiguration.getDiagnosticsSummary(context)
        assertTrue(summary.contains("GAC Hycan Z03"))
        assertTrue(summary.contains("1920x1080"))
        assertTrue(summary.contains("160dpi"))
    }

    @Test
    fun verifyWifiMacAddressFormat() {
        val mac = GACHycanConfiguration.resolveWifiMacAddress()
        assertNotNull(mac)
        val macRegex = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        assertTrue("MAC $mac should match standard format", macRegex.matches(mac))
    }

    @Test
    fun verifyP2pManagerCreation() {
        val context = RuntimeEnvironment.getApplication()
        val manager = GACHycanConfiguration.createAndroid8P2pManager(context)
        assertNotNull(manager)
        manager.close()
    }
}
