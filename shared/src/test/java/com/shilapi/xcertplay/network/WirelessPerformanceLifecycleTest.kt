package com.shilapi.xcertplay.network

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WirelessPerformanceLifecycleTest {
    @Test fun wirelessSessionOwnsTheLockAndStaleTeardownCannotReleaseItsReplacement() {
        exercise(CarPlayTransport.WIRELESS, expectedAcquisitions = 1)
    }
    @Test fun wiredCarPlayNeverAcquiresTheWirelessHints() {
        exercise(CarPlayTransport.WIRED, expectedAcquisitions = 0)
    }
    private fun exercise(transport: CarPlayTransport, expectedAcquisitions: Int) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        WirelessPerformanceSettings.save(app, true)
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int) = false
        }
        val controller = CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = transport,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        var acquisitions = 0
        var releases = 0
        val lease = WirelessPerformanceLock(listOf(3), {
            object : WirelessPerformanceLock.Handle {
                override fun acquire() { acquisitions++ }
                override fun release() { releases++ }
            }
        })
        val leaseField = CarPlayController::class.java.getDeclaredField("wirelessPerformance").apply { isAccessible = true }
        val activeField = CarPlayController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }
        val listener = CarPlayController::class.java.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        fun session() = AirPlaySession(Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val first = session()
        val next = session()
        try {
            leaseField.set(controller, lease)
            // Keep HUD/cluster startup outside this performance-lock test.
            activeField.set(controller, first)
            listener.onSessionActive(first)
            assertEquals(expectedAcquisitions, acquisitions)
            activeField.set(controller, next)
            listener.onSessionActive(next)
            listener.onSessionEnded(first)
            assertEquals(0, releases)
            controller.close()
            assertEquals(expectedAcquisitions, releases)
            controller.close()
            assertEquals(expectedAcquisitions, releases)
        } finally {
            first.close(); next.close(); controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }
}
