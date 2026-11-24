package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class VideoControlEvidenceTest {
    @Test fun repliesAreCorrelatedByCseqAndNotByTheLatestStream() {
        val lines = mutableListOf<String>()
        val exchange = VideoEventExchanges(lines::add)
        exchange.started(7, 3, mapOf("type" to "playbackState", "name" to "paused"))
        exchange.started(9, 8, mapOf("type" to "playbackState", "name" to "playing"))
        exchange.written(7)
        exchange.written(9)
        exchange.response(9, 200)
        exchange.response(7, 455)
        assertTrue(lines.any { it == "video event response cseq=7 stream=3 op=playbackState state=paused status=455" })
        assertTrue(lines.any { it == "video event response cseq=9 stream=8 op=playbackState state=playing status=200" })
    }

    @Test fun untrackedResponsesAndClearedSessionsCannotReuseAnEarlierEntry() {
        val lines = mutableListOf<String>()
        val exchange = VideoEventExchanges(lines::add)
        exchange.response(1, 200)
        assertTrue(lines.isEmpty())
        exchange.started(1, 3, mapOf("type" to "playbackState"))
        exchange.clear()
        val count = lines.size
        exchange.response(1, 200)
        assertEquals(count, lines.size)
    }

    @Test fun unansweredEventsAreBoundedAndFailuresRemoveTheirEntry() {
        val lines = mutableListOf<String>()
        val exchange = VideoEventExchanges(lines::add)
        for (seq in 1..100) exchange.started(seq, 3, mapOf("type" to "playbackState"))
        val count = lines.size
        exchange.response(1, 200)
        assertEquals(count, lines.size)
        exchange.failed(100)
        val afterFailure = lines.size
        exchange.response(100, 200)
        assertEquals(afterFailure, lines.size)
        exchange.clear()
        assertTrue(lines.last().contains("unanswered=63"))
    }

    @Test fun evidenceNeverPrintsUrlsIdentifiersTitlesOrCredentials() {
        val message = mapOf("type" to "insertPlayQueueItem", "kind" to "request", "messageID" to "private-request",
            "item" to mapOf("uuid" to "private-item", "Content-Location" to "https://private.invalid/movie?token=secret",
                "playerLoggingID" to "private-player", "playerItemLoggingID" to byteArrayOf(1, 2, 3),
                "playbackRestrictions" to mapOf("canSeek" to true, "authToken" to "secret", "password" to 1234),
                "referenceRestrictions" to 7L, "audioMode" to "private-audio"),
            "metadata" to mapOf("title" to "private-title", "duration" to 10L, "secret" to "private-secret"))
        val line = VideoControlEvidence.received(3, message)
        for (value in listOf("private-", "secret", "authToken", "password", "1234", "https://")) assertFalse(line.contains(value))
        assertTrue(line.contains("canSeek:true"))
        assertTrue(line.contains("referenceRules=7"))
        assertTrue(line.contains("duration:10"))
        assertFalse(line.contains("name="))
    }

    @Test fun fingerprintsRemainComparableAcrossStreamsAndUnsupportedStringsAreOpaque() {
        val item = mapOf("uuid" to "item", "playerLoggingID" to "player", "playerItemLoggingID" to "entry")
        val first = VideoControlEvidence.received(3, mapOf("type" to "insertPlayQueueItem", "item" to item))
        val second = VideoControlEvidence.received(8, mapOf("type" to "insertPlayQueueItem", "item" to item))
        assertEquals(first.substringAfter("fields="), second.substringAfter("fields="))
        assertEquals("other", VideoControlEvidence.operation(mapOf("type" to "https://private.invalid")))
        assertEquals("none", VideoControlEvidence.state(mapOf("name" to "private-value")))
    }

    @Test fun setupMetadataUsesFingerprintsAndProtocolFlagsOnly() {
        val line = VideoControlEvidence.setup(mapOf("clientUUID" to "private-client", "channelID" to "private-channel",
            "wantsDedicatedSocket" to false, "sendMessageAsIs" to true), mapOf("streamID" to 9L))
        assertTrue(line.contains("stream=9"))
        assertTrue(line.contains("dedicated=false asIs=true"))
        assertFalse(line.contains("private-"))
    }
}
