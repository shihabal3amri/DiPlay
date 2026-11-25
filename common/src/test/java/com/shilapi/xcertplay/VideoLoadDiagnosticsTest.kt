package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class VideoLoadDiagnosticsTest {
    @Test fun exceptionMessagesAndUnknownReasonsNeverReachDiagnostics() {
        val secret = "https://private.example/video?token=SECRET Authorization=PASSWORD"
        val error = IOException(secret, IphoneVideoResourceRejectedException(secret))
        val result = VideoLoadDiagnostics.causes(error)
        assertEquals("IOException<-IphoneVideoResourceRejectedException", result)
        assertFalse(result.contains("SECRET"))
        assertTrue(VideoLoadDiagnostics.causes(IphoneVideoResourceRejectedException("request_admission"))
            .contains("[request_admission]"))
        assertTrue(VideoLoadDiagnostics.causes(IphoneVideoResourceRejectedException("request_admission", 12))
            .contains("[request_admission;request=12]"))
    }

    @Test fun playlistCountsContainNoUriOrAttributeValues() {
        val bytes = ("#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1000000\nhttps://private.example/SECRET\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=8000000\nhttps://private.example/SECRET2\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"SECRET-AUDIO\",URI=\"SECRET\"\n" +
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"SECRET-SUB\",URI=\"SECRET\"\n").toByteArray()
        val result = VideoLoadDiagnostics.inspect(bytes, null, bytes.size.toLong())
        assertTrue(result.contains("variants=2 audio=1 subtitles=1"))
        assertTrue(result.contains("truncated=false"))
        assertFalse(result.contains("SECRET"))
        assertFalse(result.contains("private.example"))
    }

    @Test fun inspectionCapsTheScanAndDoesNotRetainAnUnterminatedLine() {
        val bytes = ("#EXTM3U\n" + "x".repeat(1024 * 1024 + 4096)).toByteArray()
        val result = VideoLoadDiagnostics.inspect(bytes, null, bytes.size.toLong())
        assertTrue(result.contains("scannedBytes=1048576 truncated=true"))
        assertTrue(result.length < 200)
    }

    @Test fun inspectingAResourceFileLeavesTheResourceIntact() {
        val file = Files.createTempFile("video-diagnostics", ".bin").toFile()
        val bytes = "#EXTM3U\n#EXTINF:4,\nSECRET.ts\n".toByteArray()
        try {
            file.writeBytes(bytes)
            val result = VideoLoadDiagnostics.inspect(null, file, file.length())
            assertTrue(result.contains("segments=1"))
            assertArrayEquals(bytes, file.readBytes())
        } finally { file.delete() }
    }
}
