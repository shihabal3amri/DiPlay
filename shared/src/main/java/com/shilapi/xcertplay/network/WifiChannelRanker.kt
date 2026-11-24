package com.shilapi.xcertplay.network

/**
 * Ranks the 2.4 GHz non-overlapping channels 1, 6 and 11 from Wi-Fi scan observations.
 *
 * Scoring is deliberately simple and explainable on the settings screen: fewer access
 * points on a channel wins, and ties break towards the channel whose loudest access
 * point is weakest. Only exact-channel counts are used: spillover from neighbouring
 * channels is not assumed, because the receiver cannot measure it from SSIDs alone.
 * 5 GHz channels (36–165) and the "Auto · band" choices are not scored here: this
 * ranking only covers the 2.4 GHz non-overlapping channels, and a 5 GHz or Auto
 * choice is a separate setting, not a quieter 2.4 GHz channel.
 */
object WifiChannelRanker {
    /** The channels a 2.4 GHz Wi-Fi Direct group can use without overlapping a neighbour. */
    val CHANNELS = listOf(1, 6, 11)

    /** One access point seen on a channel. [rssiDbm] is negative; closer to 0 is louder. */
    data class Observation(val channel: Int, val rssiDbm: Int)

    data class ChannelScore(
        val channel: Int,
        val accessPointCount: Int,
        /** RSSI of the loudest access point on this channel, or null when none was seen. */
        val strongestRssiDbm: Int?,
    )

    /**
     * Scores for channels 1, 6 and 11, quietest first. Channels with no observations
     * still appear, with zero access points, so the screen can show all three.
     */
    fun rank(observations: List<Observation>): List<ChannelScore> {
        val byChannel = observations.filter { it.channel in CHANNELS }.groupBy { it.channel }
        return CHANNELS.map { channel ->
            val seen = byChannel[channel].orEmpty()
            ChannelScore(channel, seen.size, seen.maxOfOrNull { it.rssiDbm })
        }.sortedWith(
            compareBy<ChannelScore> { it.accessPointCount }
                // A channel with no access points sorts first; otherwise the channel
                // whose loudest access point is weakest (most negative RSSI) wins.
                .thenBy { it.strongestRssiDbm ?: Int.MIN_VALUE },
        )
    }

    /**
     * The quietest channel, or null when nothing was observed on 1, 6 or 11 at all.
     * A channel with no observed access points is a valid recommendation: it is the
     * quietest channel the scan could see.
     */
    fun recommend(observations: List<Observation>): Int? {
        if (observations.none { it.channel in CHANNELS }) return null
        return rank(observations).first().channel
    }

    /** The Wi-Fi channel for a scan-result frequency, or null when it is not a channel centre. */
    fun channelForFrequencyMhz(frequencyMhz: Int): Int? = when {
        frequencyMhz in 2412..2472 && (frequencyMhz - 2407) % 5 == 0 -> (frequencyMhz - 2407) / 5
        frequencyMhz == 2484 -> 14
        frequencyMhz in 5160..5885 && (frequencyMhz - 5000) % 5 == 0 -> (frequencyMhz - 5000) / 5
        else -> null
    }
}
