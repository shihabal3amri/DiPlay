package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class ClusterSongAcknowledgementsTest {
    @Test fun independentAcknowledgementsAvoidRetryingFailedSourceForEveryLyric() {
        val cache = ClusterSongAcknowledgements()
        val first = cache.command(11, 1, "eA==", 0)
        cache.accept("source=1\nstate=0\ntext=0", first, 1)
        assertEquals(setOf("text"), cache.command(11, 1, "eQ==", 2).fields)
        val retry = cache.command(11, 1, "eg==", 31_000_000_000)
        assertEquals(setOf("source", "text"), retry.fields)
        cache.accept("source=0\ntext=0", retry, 31_000_000_001)
        assertFalse(cache.needsRefresh(32_000_000_000))
        assertEquals(setOf("state", "text"), cache.command(11, 2, "eg==", 33_000_000_000).fields)
        cache.reset()
        assertEquals(setOf("source", "state", "text"), cache.command(11, 1, "eg==", 34_000_000_000).fields)
    }
    @Test fun observedUpstreamVendorResultStillConfirmsIndividualControls() {
        val cache = ClusterSongAcknowledgements()
        val first = cache.command(11, 1, "eA==", 0)
        cache.accept("source=-2147482648\nstate=-2147482648\ntext=0", first, 1)
        assertTrue(cache.sourceConfirmed)
        assertEquals(setOf("text"), cache.command(11, 1, "eQ==", 2).fields)
    }
    @Test fun protocolRejectsArbitraryCommandsInvalidSourceAndOversizedText() {
        val token = "a".repeat(32)
        assertNotNull(ClusterSongWorkerProtocol.parse("$token write 7 1 eA==", token))
        for (line in listOf("wrong ping", "$token execute settings", "$token write 16 1 eA==",
                "$token write 11 4 eA==", "$token write 11 1 !!!")) {
            assertNull(ClusterSongWorkerProtocol.parse(line, token))
        }
        val oversized = java.util.Base64.getEncoder().encodeToString("x".repeat(128).toByteArray())
        assertNull(ClusterSongWorkerProtocol.parse("$token write 11 1 $oversized", token))
    }
}
