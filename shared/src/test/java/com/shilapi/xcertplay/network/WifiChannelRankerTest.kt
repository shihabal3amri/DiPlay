package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiChannelRankerTest {
    @Test
    fun `empty scan ranks all three channels with no access points and recommends nothing`() {
        val ranked = WifiChannelRanker.rank(emptyList())
        assertEquals(listOf(1, 6, 11), ranked.map { it.channel })
        assertEquals(listOf(0, 0, 0), ranked.map { it.accessPointCount })
        assertEquals(listOf(null, null, null), ranked.map { it.strongestRssiDbm })
        assertNull(WifiChannelRanker.recommend(emptyList()))
    }

    @Test
    fun `a channel with no access points beats a crowded one`() {
        val observations = listOf(
            WifiChannelRanker.Observation(6, -50),
            WifiChannelRanker.Observation(6, -70),
        )
        assertEquals(listOf(1, 11, 6), WifiChannelRanker.rank(observations).map { it.channel })
        assertEquals(1, WifiChannelRanker.recommend(observations))
    }

    @Test
    fun `ties break towards the channel whose loudest access point is weakest`() {
        val observations = listOf(
            WifiChannelRanker.Observation(1, -80),
            WifiChannelRanker.Observation(6, -40),
            WifiChannelRanker.Observation(11, -85),
        )
        assertEquals(listOf(11, 1, 6), WifiChannelRanker.rank(observations).map { it.channel })
        assertEquals(11, WifiChannelRanker.recommend(observations))
    }

    @Test
    fun `fewer access points win even when the single one is louder`() {
        val observations = listOf(
            WifiChannelRanker.Observation(1, -85),
            WifiChannelRanker.Observation(1, -88),
            WifiChannelRanker.Observation(1, -90),
            WifiChannelRanker.Observation(6, -40),
            WifiChannelRanker.Observation(11, -70),
            WifiChannelRanker.Observation(11, -75),
        )
        assertEquals(6, WifiChannelRanker.recommend(observations))
    }

    @Test
    fun `five gigahertz and overlapping channels are ignored`() {
        val observations = listOf(
            WifiChannelRanker.Observation(36, -30),
            WifiChannelRanker.Observation(3, -30),
            WifiChannelRanker.Observation(13, -30),
            WifiChannelRanker.Observation(6, -60),
        )
        val ranked = WifiChannelRanker.rank(observations)
        assertEquals(listOf(1, 11, 6), ranked.map { it.channel })
        assertEquals(listOf(0, 0, 1), ranked.map { it.accessPointCount })
        assertEquals(1, WifiChannelRanker.recommend(observations))
    }

    @Test
    fun `strongest rssi is the loudest access point on the channel`() {
        val observations = listOf(
            WifiChannelRanker.Observation(11, -75),
            WifiChannelRanker.Observation(11, -55),
            WifiChannelRanker.Observation(11, -82),
        )
        val score = WifiChannelRanker.rank(observations).single { it.channel == 11 }
        assertEquals(3, score.accessPointCount)
        assertEquals(-55, score.strongestRssiDbm)
    }

    @Test
    fun `frequency maps to its channel centre`() {
        assertEquals(1, WifiChannelRanker.channelForFrequencyMhz(2412))
        assertEquals(6, WifiChannelRanker.channelForFrequencyMhz(2437))
        assertEquals(11, WifiChannelRanker.channelForFrequencyMhz(2462))
        assertEquals(14, WifiChannelRanker.channelForFrequencyMhz(2484))
        assertEquals(36, WifiChannelRanker.channelForFrequencyMhz(5180))
        assertEquals(149, WifiChannelRanker.channelForFrequencyMhz(5745))
        assertNull(WifiChannelRanker.channelForFrequencyMhz(2415))
        assertNull(WifiChannelRanker.channelForFrequencyMhz(2400))
        assertNull(WifiChannelRanker.channelForFrequencyMhz(0))
    }
}
