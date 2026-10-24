package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.uisync.*
import org.junit.Assert.*
import org.junit.Test

class Fx11UltraTest {
    @Test fun fx11ConversionsDoNotGuessByteArraysOrUnits() {
        fun binding(s: VehicleSignal) = Fx11Signals.bindings.first { it.signal == s }
        assertEquals(36.0, binding(VehicleSignal.SPEED_KPH).decode(10.0))
        assertEquals(1500.0, binding(VehicleSignal.ENGINE_RPM).decode(3000))
        assertEquals("R", binding(VehicleSignal.GEAR).decode(1))
        assertNull(binding(VehicleSignal.GEAR).decode(255))
        assertNull(binding(VehicleSignal.ENGINE_RPM).decode(65535))
        assertEquals(100.0, binding(VehicleSignal.RANGE_KM).decode(100000))
        assertTrue(Fx11Signals.bindings.none { it.propertyId in Fx11Signals.undecodedVendorProperties })
    }
    @Test fun staleAndFailedReadingsAreUnavailableNotZero() {
        var time = 10L; val state = VehicleTelemetry({ time }, 100)
        state.accept(VehicleSignal.SPEED_KPH, 20.0, 1)
        time += 101; assertTrue(state.snapshot().isEmpty())
        state.accept(VehicleSignal.SPEED_KPH, Double.NaN, 1); assertTrue(state.snapshot().isEmpty())
        state.accept(VehicleSignal.GEAR, "D", 2); state.clear(); assertTrue(state.snapshot().isEmpty())
    }
    @Test fun ultraIsOptInAndDoesNotEnableUnrequestedFeatures() {
        val profile = UltraVehicleProfile.negotiationOnly()
        val provisioning = Fx11UltraProvisioning.create(profile, { error("No source before a data channel") })
        val runtime = provisioning.createRuntime()
        val base = AirPlayConfig("test", "00", "00", "1", AirPlayDisplayConfig(1280, 720),
            cluster = AirPlayDisplayConfig(1920, 532))
        assertFalse(AirPlayInfoPlist.build(base).containsKey("vehicleStateProtocolInfo"))
        val config = base.copy(ultra = provisioning.createConfig(requireNotNull(base.cluster), runtime))
        assertTrue(AirPlayInfoPlist.build(config).containsKey("vehicleStateProtocolInfo"))
        val negotiated = setupEnabledFeatures(config, listOf("uiSync", "vehicleStateProtocol"))
        assertTrue(negotiated.containsAll(listOf("uiSync", "vehicleStateProtocol")))
        assertFalse(setupEnabledFeatures(config, emptyList<String>()).contains("uiSync"))
        assertFalse(negotiated.contains("fileTransfer"))
        assertEquals(532, ((AirPlayInfoPlist.build(config)["displays"] as List<*>)[1] as Map<*, *>)["heightPixels"])
    }
    @Test fun uiSyncResetDuplicatesAndForeignSessionAreHandled() {
        val state = UltraUiSync()
        val reset = CarPlayUiSyncMessageFactory.reset(2, 0, 0, version = CarPlayUiSyncProtocolVersion.V3)
        val reply = state.receive(reset)!!
        assertEquals(CarPlayUiSyncMessageType.RESET_COMPLETE, reply.type)
        assertEquals(reply, state.receive(reset))
        val command = CarPlayUiSyncMessageFactory.command(2, 1, 0,
            CarPlayUiSyncCommand.TARGET_APPEARANCE_CHANGE, mapOf("appearanceMode" to "dark"))
        val ack = state.receive(command)
        assertEquals(1L, ack!!.acknowledgementSequenceNumber)
        assertEquals(ack, state.receive(command))
        assertNull(state.receive(command.copy(sessionSequenceNumber = 1, packetSequenceNumber = 8)))
        assertNull(state.receive(command.copy(packetSequenceNumber = 2, noAck = true)))
        assertEquals("targetAppearanceChange", state.lastCommand)
    }
    @Test fun liveCafSubscriptionExpiresAndUnregistersWithoutVehicleWrites() {
        val profile = profile()
        var current: Map<VehicleSignal, VehicleReading> = mapOf(VehicleSignal.ENGINE_RPM to VehicleReading(1500.0, 0, 1))
        val source = object : UltraVehicleSource {
            override fun start() {}; override fun close() {}
            override fun snapshot() = current
        }
        val state = VehicleCafState(profile, source)
        fun handle(frame: CafRcsFrame) = CarAccessoryMessages.reader(state.handle(CarAccessoryMessages.reader(frame.rcsBody)).rcsBody)
        assertEquals(1500.0, handle(CarAccessoryMessages.readRequest(1, 1, listOf(3))).valuesMap()[3L])
        handle(CarAccessoryMessages.registerRequest(1, 2))
        assertNull(state.updates())
        current = emptyMap()
        val expired = CarAccessoryMessages.reader(state.updates()!!.rcsBody)
        assertTrue(expired.valuesMap().containsKey(3L)); assertNull(expired.valuesMap()[3L])
        assertEquals(7L, handle(CarAccessoryMessages.writeRequest(1, 3, mapOf(3L to 9000.0))).errors()[3L])
        handle(CarAccessoryMessages.unregisterRequest(1, 4, listOf(3)))
        current = mapOf(VehicleSignal.ENGINE_RPM to VehicleReading(800.0, 0, 1))
        assertNull(state.updates())
    }
    @Test fun missingAndWritableProfileSchemasAreRejected() {
        assertTrue(runCatching { UltraVehicleProfile.decode(BplistCodec.encode(emptyMap<String, Any>())) }.isFailure)
        assertTrue(runCatching { profile(mutable = true) }.isFailure)
        assertTrue(UltraVehicleProfile.negotiationOnly().bindings.isEmpty())
    }
    private fun profile(mutable: Boolean = false): UltraVehicleProfile {
        // Synthetic IDs are strictly test fixtures, not production Apple characteristic definitions.
        val characteristic = mapOf("type" to 99L, "iid" to 3L, "format" to 9L, "mutable" to mutable,
            "supportsInvalid" to true, "units" to "rpm")
        val service = mapOf("type" to 98L, "iid" to 2L, "characteristics" to listOf(characteristic), "controls" to emptyList<Any>())
        val accessory = mapOf("type" to 97L, "iid" to 1L, "version" to "1.0", "services" to listOf(service))
        return UltraVehicleProfile.decode(BplistCodec.encode(mapOf("evidence" to "UNIT TEST ONLY", "pluginId" to 1L,
            "pluginName" to "test", "unavailableError" to 7L, "unsupportedError" to 7L,
            "config" to mapOf("accessories" to listOf(accessory)),
            "bindings" to listOf(mapOf("iid" to 3L, "signal" to "ENGINE_RPM", "sourceUnit" to "rpm", "targetUnit" to "rpm")))))
    }
}
