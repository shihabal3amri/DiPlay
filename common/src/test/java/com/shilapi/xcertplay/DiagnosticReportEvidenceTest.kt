package com.shilapi.xcertplay

import android.net.Uri
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.shilapi.xcertplay.hud.BluetoothPauseEvidence
import com.shilapi.xcertplay.hud.BydCallPopupGuard
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The evidence issues #1021, #1110, #1180, #1219 and #1237 were missing from saved reports: each new
 * line must reach the report as written, and none may carry a URL, a device name or an address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class DiagnosticReportEvidenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        ReflectionHelpers.setField(CarPlayVideo, "diagnostic", { _: String -> })
        CarPlayVideo.onVideoSessionEnded()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertKept(line: String) = assertEquals(line, DiagnosticRedactor.redact(line))

    @Test fun theHeaderNamesCallPopupPipAndDashboardSongSettings() {
        AirPlayPersistence.saveCallEchoCancellation(context, true)
        BydOutputSettings.setPhoneAudioRoute(context, false)
        AirPlayPersistence.saveCallSpeakerphone(context, true)
        AirPlayPersistence.saveHideBydCallPopup(context, true)
        AirPlayPersistence.saveBtSuspendDuringCarplay(context, true)
        AirPlayPersistence.saveBtSuspendDelaySeconds(context, 15)
        AirPlayPersistence.saveAdaptPipResolution(context, true)
        BydOutputSettings.setClusterSong(context, true)
        BydOutputSettings.setClusterSongGate(context, false)
        BydOutputSettings.setClusterSongOnChange(context, true)

        val lines = DiagnosticSettingsReport.lines(context)
        assertEquals(
            "Call settings: call_echo_cancellation=true phone_audio_route=false call_speakerphone=true " +
                "hide_byd_call_popup=true callPopupStatus=${BydCallPopupGuard.status} " +
                "bt_suspend_during_carplay=true bt_suspend_delay_s=15",
            lines[0],
        )
        assertEquals("Multi-window resolution: adapt_pip_resolution=true", lines[1])
        assertTrue(lines[2], lines[2].startsWith(
            "Dashboard song: clusterSong=true clusterSongGate=false clusterSongOnChange=true gate=off adbAccess="))
        assertTrue(lines[2], lines[2].contains(" lastWrite="))
        lines.forEach(::assertKept)
    }

    @Test fun aFailedBluetoothPauseKeepsItsEvidenceInTheReport() {
        val lines = BluetoothPauseEvidence.lines(true, "svc: unknown command 'bluetooth'",
            "Bluetooth Status\n  enabled: true\n  state: ON\n  address: C0:A6:00:29:58:0A\n  name: Jane's Car")
        assertEquals(listOf(
            "car Bluetooth pause evidence: svc bluetooth disable printed 'svc: unknown command bluetooth'",
            "car Bluetooth pause evidence: bluetooth_manager enabled=true state=ON",
        ), lines)
        lines.forEach(::assertKept)
        // A reply that names an address keeps the line; the redactor masks the address.
        val masked = DiagnosticRedactor.redact(BluetoothPauseEvidence.commandLine(true, "error C0:A6:00:29:58:0A busy"))
        assertEquals("car Bluetooth pause evidence: svc bluetooth disable printed 'error [address] busy'", masked)
    }

    @Test fun callAudioAndPopupLinesSurviveTheSavedLog() {
        // The exact text MicrophoneUplink and BydCallPopupGuard write; their own tests pin it there.
        val lines = listOf(
            "Microphone: type=telephony platform effect=AEC enabled=false",
            "Microphone: type=telephony platform effect=NS could not be disabled status=-5",
            "Microphone: type=telephony echo canceller enabled=true tail=250ms",
            "Microphone: type=telephony echo canceller skipped rate=48000 channels=1",
            "Microphone: type=telephony effects left to the car's phone audio state",
            "Microphone: type=telephony echo canceller stopped; platform AEC restored",
            "BYD call popup: hide requested",
            "BYD call popup: status=HIDDEN previous=OFF",
            "BYD call popup: status=UNAVAILABLE_OR_RESTORE_UNCONFIRMED previous=HIDDEN reason=HELPER_TIMEOUT",
        )
        val folder = Files.createTempDirectory("diplay-call-evidence").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file).use { log ->
                log.reset("header")
                lines.forEach { log.append("12:00:00.000  $it") }
                assertTrue(log.awaitIdle(10_000))
            }
            assertEquals(listOf("header") + lines.map { "12:00:00.000  $it" }, file.readLines())
        } finally { folder.deleteRecursively() }
    }

    @Test fun aQueuedVideoReachesTheReportWithItsHostButNotItsUrl() {
        val lines = CopyOnWriteArrayList<String>()
        ReflectionHelpers.setField(CarPlayVideo, "diagnostic", { line: String -> lines += line })
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&signature=SECRET&ip=203.0.113.9"
        CarPlayVideo.onVideoMessage(7L, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "Content-Location" to url, "mediaType" to "streaming", "clientBundleID" to "com.example.Video", "uuid" to "u1")))
        CarPlayVideo.onVideoMessage(7L, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "Content-Location" to "file:///sdcard/private.mp4", "mediaType" to "file")))
        CarPlayVideo.onVideoMessage(7L, mapOf("type" to "unhandledURL", "kind" to "response",
            "response" to mapOf("FCUP_Response_RequestID" to 3, "FCUP_Response_StatusCode" to 200)))
        CarPlayVideo.onVideoMessage(7L, mapOf("type" to "stop"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(
            "Video: queue item app=com.example.Video scheme=https host=rr3---sn-abc.googlevideo.com mediaType=streaming",
            "Video: queue item this player cannot play mediaType=file",
            "Video: iPhone unhandledURL answer request=3 keys=[type,kind,response] " +
                "responseKeys=[FCUP_Response_RequestID,FCUP_Response_StatusCode]",
        ), lines)
        for (line in lines) {
            assertKept(line)
            assertFalse(line, listOf("videoplayback", "SECRET", "signature", "203.0.113.9", "sdcard").any(line::contains))
        }
    }

    @Test fun videoErrorsEncryptionAndLoadsKeepCodesAndNamesOnly() {
        val dataSpec = DataSpec(Uri.parse("https://cdn.example.com/v.m3u8?token=abc"))
        val http = HttpDataSource.InvalidResponseCodeException(403, "Forbidden https://cdn.example.com/v.m3u8?token=abc",
            null, emptyMap(), dataSpec, ByteArray(0))
        val error = PlaybackException("Source error https://cdn.example.com/v.m3u8?token=abc", http,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        val lines = listOf(
            VideoDiagnostics.playerError(error),
            VideoDiagnostics.loadingVideo("https://cdn.example.com/v.m3u8?token=abc",
                "network=wifi internet=true validated=false"),
            VideoDiagnostics.iphoneLoaded("myapp", 200, 1024, redirect = false),
            VideoDiagnostics.hlsEncryption("skd", "cbcs",
                listOf(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"), UUID.randomUUID()),
                listOf("cbcs", null), 120),
        ).map { "Video: $it" }
        assertEquals(listOf(
            "Video: player error ERROR_CODE_IO_BAD_HTTP_STATUS code=2004 http=403 causes=InvalidResponseCodeException",
            "Video: loading video scheme=https network=wifi internet=true validated=false",
            "Video: iPhone loaded myapp URL status=200 bytes=1024 redirect=false",
            "Video: HLS encryption aes128KeyScheme=skd sampleAesSchemeType=cbcs drm=[widevine,other] " +
                "sessionKeys=[cbcs,none] segments=120",
        ), lines)
        for (line in lines) {
            assertKept(line)
            assertFalse(line, line.contains("token") || line.contains("cdn.example.com"))
        }
        assertEquals("none", VideoDiagnostics.word(null))
        assertEquals("[a,b,c,d,e,f,g,h,i,j,k,l,+2]", VideoDiagnostics.keys(("abcdefghijklmn").map { it.toString() }))
        assertFalse(VideoDiagnostics.word("x y\nz=\"secret\"").contains(' '))
        assertTrue(runCatching { VideoDiagnostics.playerError(PlaybackException(null, IOException(), 1000)) }.isSuccess)
    }
}
