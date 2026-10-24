package com.shilapi.xcertplay

import android.content.Context
import android.hardware.automotive.vehicle.V2_0.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Fx11OemOverlayBridgeTest {
    private class Fixture {
        val vehicle = mock(IVehicle::class.java)
        var getterStatus = 0
        var reply = VehiclePropValue().apply { prop = Fx11OemOverlayBridge.READ_PROPERTY; value.int32Values.add(0) }
        val readConfig = VehiclePropConfig().apply { prop = Fx11OemOverlayBridge.READ_PROPERTY; access = 1 }
        val writeConfig = VehiclePropConfig().apply { prop = Fx11OemOverlayBridge.WRITE_PROPERTY; access = 2 }
        val bridge = Fx11OemOverlayBridge { vehicle }
        init {
            `when`(vehicle.allPropConfigs).thenReturn(arrayListOf(readConfig, writeConfig))
            doAnswer { call ->
                val request = call.getArgument<VehiclePropValue>(0)
                assertEquals(Fx11OemOverlayBridge.READ_PROPERTY, request.prop)
                assertEquals(0, request.areaId)
                call.getArgument<IVehicle.getCallback>(1).onValues(getterStatus, reply)
                null
            }.`when`(vehicle).get(any(VehiclePropValue::class.java), any(IVehicle.getCallback::class.java))
            bridge.connect(mock(Context::class.java))
        }
        fun validate() = bridge.validate(Fx11ProjectionMode.OEM_OVERLAY)
    }

    @Test fun scalarAndActivePaFeedbackDecodeWithoutInventingZero() {
        assertEquals(3, Fx11OemOverlayBridge.decode(listOf(3), byteArrayOf()))
        val pa = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(3).putInt(0).putInt(0).array()
        assertEquals(3, Fx11OemOverlayBridge.decode(emptyList(), pa))
        pa[0] = 0
        assertNull(Fx11OemOverlayBridge.decode(emptyList(), pa))
        assertNull(Fx11OemOverlayBridge.decode(emptyList(), byteArrayOf()))
        assertNull(Fx11OemOverlayBridge.decode(listOf(0), pa))
        assertNull(Fx11OemOverlayBridge.decode(listOf(0, 3), byteArrayOf()))
        assertNull(Fx11OemOverlayBridge.decode(emptyList(), byteArrayOf(3)))
        assertEquals(255, Fx11OemOverlayBridge.decode(listOf(255), byteArrayOf()))
    }

    @Test fun setUsesOnlyTheFixedCommandPropertyAndNeverEchoesFeedbackBytes() {
        val f = Fixture(); f.validate()
        assertEquals(0, f.bridge.read())
        assertTrue(f.bridge.write(3)); assertTrue(f.bridge.write(0))
        val values = org.mockito.ArgumentCaptor.forClass(VehiclePropValue::class.java)
        verify(f.vehicle, times(2)).set(values.capture())
        assertEquals(listOf(3, 0), values.allValues.map { it.value.int32Values.single() })
        assertTrue(values.allValues.all { it.prop == Fx11OemOverlayBridge.WRITE_PROPERTY && it.areaId == 0 && it.value.bytes.isEmpty() })
        assertThrows(IllegalArgumentException::class.java) { f.bridge.write(1) }
    }

    @Test fun declaredRestrictionsStillPreventWrites() {
        val f = Fixture()
        f.writeConfig.access = 1
        assertThrows(IllegalStateException::class.java) { f.validate() }
        assertThrows(IllegalStateException::class.java) { f.bridge.write(3) }
        verify(f.vehicle, never()).set(any(VehiclePropValue::class.java))
        f.writeConfig.access = 2
        f.writeConfig.areaConfigs.add(VehicleAreaConfig().apply { areaId = 1 })
        assertThrows(IllegalStateException::class.java) { f.validate() }
        f.writeConfig.areaConfigs.clear(); f.readConfig.access = 2
        assertThrows(IllegalStateException::class.java) { f.validate() }
        verify(f.vehicle, never()).set(any(VehiclePropValue::class.java))
    }

    @Test fun omittedDirectoryAllowsOnlyFixedPairAfterValidLiveFeedback() {
        for (feedback in listOf(0, 3)) {
            val f = Fixture()
            `when`(f.vehicle.allPropConfigs).thenReturn(arrayListOf())
            f.reply.value.int32Values.clear()
            f.reply.value.bytes.addAll(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1).putInt(feedback).putInt(0).putInt(0).array().toList())
            f.validate()
            assertEquals(feedback, f.bridge.read())
            verify(f.vehicle, never()).set(any(VehiclePropValue::class.java))
            assertTrue(f.bridge.write(if (feedback == 0) 3 else 0))
            val request = org.mockito.ArgumentCaptor.forClass(VehiclePropValue::class.java)
            verify(f.vehicle).set(request.capture())
            assertEquals(Fx11OemOverlayBridge.WRITE_PROPERTY, request.value.prop)
            assertEquals(0, request.value.areaId)
        }
    }

    @Test fun omittedDirectoryNeverAuthorizesWriteWithMissingFailedOrUnknownFeedback() {
        for (invalid in listOf<(Fixture) -> Unit>(
            { it.getterStatus = 1 },
            { it.reply.status = 1 },
            { it.reply.areaId = 1 },
            { it.reply.prop = 123 },
            { it.reply.value.int32Values.clear() },
            { it.reply.value.int32Values[0] = 1 },
            { it.reply.value.int32Values[0] = 255 },
        )) {
            val f = Fixture()
            `when`(f.vehicle.allPropConfigs).thenReturn(arrayListOf())
            invalid(f)
            assertThrows(IllegalStateException::class.java) { f.validate() }
            assertThrows(IllegalStateException::class.java) { f.bridge.write(3) }
            verify(f.vehicle, never()).set(any(VehiclePropValue::class.java))
        }
    }

    @Test fun unavailableMalformedWrongAreaAndFailedGetterRemainUnknown() {
        val f = Fixture(); f.validate()
        f.getterStatus = 1
        assertThrows(IllegalStateException::class.java) { f.bridge.read() }
        f.getterStatus = 0; f.reply.areaId = 1
        assertThrows(IllegalStateException::class.java) { f.bridge.read() }
        f.reply.areaId = 0; f.reply.status = 1
        assertThrows(IllegalStateException::class.java) { f.bridge.read() }
        f.reply.status = 0; f.reply.value.int32Values.clear()
        assertThrows(IllegalStateException::class.java) { f.bridge.read() }
        f.reply.value.int32Values.add(0); f.reply.prop = 123
        assertThrows(IllegalStateException::class.java) { f.bridge.read() }
    }

    @Test fun noSetterRunsDuringConnectValidationOrCloseAndWrongBridgeIsRejected() {
        val f = Fixture()
        assertThrows(IllegalStateException::class.java) { f.bridge.validate(Fx11ProjectionMode.THREE_SCREEN) }
        f.validate(); f.bridge.close()
        verify(f.vehicle, never()).set(any(VehiclePropValue::class.java))
        assertThrows(IllegalStateException::class.java) { f.bridge.write(3) }
    }
}
