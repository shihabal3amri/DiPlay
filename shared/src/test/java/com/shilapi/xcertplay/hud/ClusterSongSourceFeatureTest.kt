package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class ClusterSongSourceFeatureTest {
    class Device {
        data class State(val device: Int, val feature: Int, val value: Int)
        data class Info(val device: Int, val feature: Int, val bytes: ByteArray)
        val states = mutableListOf<State>()
        val info = mutableListOf<Info>()
        var sourceResult = 0
        fun setMediaState(device: Int, feature: Int, value: Int): Int {
            states.add(State(device, feature, value))
            return if (feature == 0x43E0000A) 0 else sourceResult
        }
        fun setMediaInfo(device: Int, feature: Int, bytes: ByteArray): Int {
            info.add(Info(device, feature, bytes)); return 0
        }
    }
    class Standard { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = 0x43E00030 } }
    class Toyota { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = 0x38F00030 } }
    class Missing
    class OldWrong { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = 0x33F00030 } }
    class Zero { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = 0 } }
    class Negative { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = -1 } }
    class Unknown { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = 0x43E00031 } }
    class WrongType { companion object { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: String = "0x43E00030" } }
    class InstanceField { @JvmField val INSTRUMENT_MUSIC_SOURCE_SET: Int = 0x43E00030 }
    private val text = "歌名 — 歌手"
    private fun args() = arrayOf("11", "1", java.util.Base64.getEncoder().encodeToString(text.toByteArray()))
    private fun assertStateAndText(device: Device) {
        assertTrue(device.states.contains(Device.State(1007, 0x43E0000A, 1)))
        assertEquals(1007, device.info.single().device)
        assertEquals(0x43FB1008, device.info.single().feature)
        assertArrayEquals(text.toByteArray(Charsets.UTF_16LE), device.info.single().bytes)
    }
    @Test fun sdkBranchFieldValueSelectsStandardAndToyotaWithoutChangingOtherWrites() {
        for ((type, expected) in listOf(Standard::class.java to 0x43E00030, Toyota::class.java to 0x38F00030)) {
            val device = Device()
            val output = ClusterSongDeviceWriter(device) { type }.write(args())
            assertTrue(output.lineSequence().any { it == "source=0" })
            assertEquals(Device.State(1007, expected, 11), device.states.first())
            assertStateAndText(device)
        }
    }
    @Test fun missingFieldReportsSourceErrorAndNeverWritesUnknownId() {
        val device = Device()
        val output = ClusterSongDeviceWriter(device) { Missing::class.java }.write(args())
        assertTrue(output.lineSequence().any { it.startsWith("source=ERR ") })
        assertEquals(listOf(Device.State(1007, 0x43E0000A, 1)), device.states)
        assertStateAndText(device)
    }
    @Test fun invalidValuesAndFieldShapesFailClosedAtActualWriteBoundary() {
        for (type in listOf(OldWrong::class.java, Zero::class.java, Negative::class.java,
                Unknown::class.java, WrongType::class.java, InstanceField::class.java)) {
            val device = Device()
            val output = ClusterSongDeviceWriter(device) { type }.write(args())
            assertTrue(type.name, output.lineSequence().any { it.startsWith("source=ERR ") })
            assertEquals(type.name, listOf(Device.State(1007, 0x43E0000A, 1)), device.states)
            assertStateAndText(device)
        }
    }
    @Test fun reflectionFailureIsReportedPerSourceAndCannotSuppressExistingStateAndText() {
        val device = Device()
        val output = ClusterSongDeviceWriter(device) { throw ClassNotFoundException("SDK unavailable") }.write(args())
        assertTrue(output.contains("source=ERR java.lang.ClassNotFoundException"))
        assertEquals(listOf(Device.State(1007, 0x43E0000A, 1)), device.states)
        assertStateAndText(device)
    }
    @Test fun skippedSourceDoesNotResolveOrWriteAnSdkFeature() {
        val device = Device()
        val output = ClusterSongDeviceWriter(device) { error("source must not be resolved") }
            .write(arrayOf("-", "1", args()[2]))
        assertTrue(ClusterSongWriteResult.acceptedFields(output, setOf("state", "text")))
        assertEquals(listOf(Device.State(1007, 0x43E0000A, 1)), device.states)
    }
}
