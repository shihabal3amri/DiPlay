package com.shilapi.xcertplay

import android.bluetooth.BluetoothDevice
import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.orchestration.ConnectionDiagnosticLines
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.trace.Iap2FrameFormatter
import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.airplay.StreamEndDiagnostics
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.SocketException
import java.nio.file.Files
import javax.crypto.AEADBadTagException

class DiagnosticRedactorTest {
    @Test fun additionalTroubleshootingMetadataSurvivesSavedReportWithoutPayloads() {
        val lines = listOf(
            "wireless startup elapsedMs=10000 authenticated=true wifiConfigs=2 startRequests=1 tcpAccepted=0 sessionActive=false waitingFor=WiFi_discovery_or_AirPlay_TCP startRequestAgeMs=9000 firstTcpAfterStartMs=none",
            "receiveCounters windowMs=10000 udpScope=device ifaceRx=sampled ifaceRxPacketsDelta=400 ifaceRxBytesDelta=8000 ifaceRxDroppedDelta=2 ifaceRxErrorsDelta=0 ifaceRxMissedErrorsDelta=1 udp4=sampled udp4InDatagramsDelta=390 udp4InErrorsDelta=2 udp4RcvbufErrorsDelta=1 udp4InCsumErrorsDelta=1 udp6=unavailable failureClass=FileNotFoundException",
            "Audio: renderer failed api=30 audioType=default codec=OPUS stage=decoder-configure error=IllegalArgumentException causes=IllegalArgumentException at=android.media.MediaCodec.configure:100",
            "Audio: decoder stats audioType=default codec=OPUS inputQueuedTotal=20 inputDroppedTotal=0 shortOpusPacketsTotal=2 decoderUnavailablePacketsTotal=0 outputBuffersTotal=19 ended=true",
            "THEME_DIAGNOSTIC sample source=poll uiMode=0x13 nightMask=0x10 reported=light applied=light sessionActive=true pollsSinceSample=30 callbacksSinceSample=0",
            "Process exit index=0 ageMs=5000 reason=native_crash reasonCode=5 status=11 importance=100 pssKiB=2048 rssKiB=4096",
        )
        val folder = Files.createTempDirectory("diplay-troubleshooting-report").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file).use { log ->
                log.reset("started")
                for (line in lines) {
                    assertTrue("New fields must fit the export cap", line.length < 700)
                    assertEquals(line, DiagnosticRedactor.redact(line))
                    log.append(line)
                }
                log.append("Audio: payload=private-recording")
            }
            val report = file.readText()
            lines.forEach { assertTrue(report.contains(it)) }
            assertFalse(report.contains("private-recording"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun boundedMicrophoneStartFailureAndCaptureCountersSurviveRedaction() {
        val lines = listOf(
            "Microphone: start type=telephony source=VOICE_COMMUNICATION codec=OPUS rate=48000 channels=1 frameMs=20 routedDeviceType=15",
            "Microphone: failure type=speechrecognition source=VOICE_RECOGNITION codec=LPCM rate=16000 channels=1 frameMs=20 stage=READ error=none code=-3",
            "Microphone: stats type=telephony source=VOICE_COMMUNICATION codec=OPUS rate=48000 channels=1 frameMs=20 routedDeviceType=15 captureBytes=1920 reads=2 zeroReads=1 readErrors=0 readMaxMs=30 encodedFrames=1 emptyEncodedFrames=1 udpSent=2 sendErrors=1 sendGapMaxMs=30 ended=false",
        )
        for (line in lines) assertEquals(line, DiagnosticRedactor.redact(line))
        assertNull(DiagnosticRedactor.redact("Microphone: payload=private-audio"))
        assertFalse(DiagnosticRedactor.redact("Microphone: failure peer=192.168.49.1")!!.contains("192.168.49.1"))
    }

    @Test fun connectionTimingAndCachedServiceMetadataSurviveWithoutPersonalIdentifiers() {
        val lines = listOf(
            "CONNECTION_DIAGNOSTIC attempt=2 phase=CONTROL wired io final readCalls=105 writeCalls=50 readTimeouts=3 ends=0 failures=1 maxReadMs=909 maxWriteMs=1937",
            // The line CarPlayController writes, not a hand-made sample: one without "bondName=" hid #1151.
            "CONNECTION_DIAGNOSTIC attempt=2 run=1 phase=WIRELESS " + ConnectionDiagnosticLines.bluetoothSnapshot(
                point = "after-failure", adapterEnabled = true, bondState = BluetoothDevice.BOND_BONDED,
                cachedServicesReadable = true, cachedServiceCount = 5, cachedIap2Service = false),
            "CONNECTION_DIAGNOSTIC generation=2 restart teardownWaitCompleted=false elapsedMs=4000",
            "CONNECTION_DIAGNOSTIC attempt=2 phase=USB_DISCOVERY USB configuration ready=true configurationId=6 reenumerationAttempts=0 action=reuse-descriptors",
            "wired start: NCM connection attempts to=advertised port=7000 syn=12 connections=3 synAck=12 rst=0 unsent=0; to=otherLinkLocal port=7000 syn=1 connections=1 synAck=0 rst=1 unsent=0",
        )
        for (line in lines) assertEquals(line, DiagnosticRedactor.redact(line))
        assertNull(DiagnosticRedactor.redact("CONNECTION_DIAGNOSTIC attempt=2 payload=private-data"))
        assertFalse(DiagnosticRedactor.redact("CONNECTION_DIAGNOSTIC attempt=2 peer=192.168.49.1 id=0123456789abcdef0123456789abcdef")!!.contains("192.168.49.1"))
    }

    // #1151: "bondName=BONDED" matched the device-name rule, so the saved log dropped every snapshot.
    @Test fun bluetoothSnapshotsAndIphoneCountsReachTheSavedLog() {
        val prefix = "12:00:00.000  CONNECTION_DIAGNOSTIC attempt=1 run=1 phase=WIRELESS "
        val lines = listOf(
            ConnectionDiagnosticLines.bluetoothSnapshot("before-connect", true, BluetoothDevice.BOND_BONDED, true, 7, true),
            ConnectionDiagnosticLines.bluetoothSnapshot("after-failure", null, BluetoothDevice.BOND_NONE, false, null, null),
            ConnectionDiagnosticLines.automaticBluetoothIphone(bonded = 3, bondedIphones = 2, directlyConnected = 0, connected = 1),
            ConnectionDiagnosticLines.savedBluetoothIphone(bonded = 3, stillPaired = false),
        ).map { prefix + it }
        assertTrue(lines[0], lines[0].contains(" bond=BONDED "))
        assertTrue(lines[1], lines[1].endsWith("cachedServiceCount=unknown cachedIap2Service=unknown"))
        assertNull("the old field name must stay dropped, or the rule changed",
            DiagnosticRedactor.redact(lines[0].replace(" bond=", " bondName=")))
        val folder = Files.createTempDirectory("diplay-bluetooth-snapshot").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file).use { log ->
                log.reset("header")
                lines.forEach(log::append)
                assertTrue(log.awaitIdle(10_000))
            }
            assertEquals(listOf("header") + lines, file.readLines())
        } finally { folder.deleteRecursively() }
    }

    @Test fun savedDriveReportKeepsMediaPerformanceCounters() {
        val folder = Files.createTempDirectory("diplay-media-report").toFile()
        try {
            val audio = "audio stats audioType=media codec=AAC_LC rx=215 dropped=0 underruns=+3 queue=2 playing=true maxGapMs=420 sinceRxMs=10 maxWriteMs=22 decoderDroppedTotal=0 outputBuffersTotal=212 ended=true"
            val video = "Video: video stats rx=29.8fps shown=29.8fps maxGap=150ms kbps=4000 recoveries=0 touch2frame avg=85ms max=110ms n=3 touchSendMax=1ms"
            SessionLogFile(folder.resolve("diplay.log")).use {
                it.reset("started")
                it.append(audio)
                it.append(video)
            }
            val report = folder.resolve("diplay.log").readText()
            assertTrue(report.contains(audio))
            assertTrue(report.contains(video))
        } finally { folder.deleteRecursively() }
    }
    @Test fun payloadAndCredentialLinesNeverReachReports() {
        for (line in listOf("TRACE IAP2 tx key", "hotspot passphrase=secret", "token=secret", "certificate bytes=607", "rx body={phone: 'Jane'}", "ok\nsecret", "wifi ssid=Home", "wireless name=Jane Smith’s iPhone")) {
            assertNull(line, DiagnosticRedactor.redact(line))
        }
    }
    @Test fun stateTransitionsSurviveWithoutAddressesOrIdentifiers() {
        val line = DiagnosticRedactor.redact("connected peer=C0:A6:00:29:58:0A ip=192.168.31.71 id=0123456789abcdef0123456789abcdef ipv6=fe80::1234:5678:abcd:9%p2p0")!!
        assertTrue(line.contains("connected"))
        assertFalse(line.contains("C0:A6")); assertFalse(line.contains("192.168")); assertFalse(line.contains("012345")); assertFalse(line.contains("fe80"))
    }
    @Test fun logRotationIsBoundedAndRedactionHappensBeforeDisk() {
        val folder = Files.createTempDirectory("diplay-log-test").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("started")
            log.append("password=secret")
            repeat(1600) { log.append("connection state " + "x".repeat(690)) }
            log.append("CarPlay connected")
            assertTrue(log.awaitIdle(10_000))
            log.close()
            assertTrue(folder.resolve("diplay.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("previous.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("diplay.log").readText().contains("CarPlay connected"))
            assertFalse(folder.listFiles()!!.any { it.readText().contains("secret") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun failuresSurviveLaterSuccessfulSessionsAndOldestHistoryExpires() {
        val folder = Files.createTempDirectory("diplay-history-test").toFile()
        try {
            repeat(10) { session ->
                SessionLogFile(folder.resolve("diplay.log")).use {
                    it.reset("session=$session")
                    it.append(if (session == 3) "Wi-Fi P2P create rejected code=0" else "CarPlay connected")
                    it.append("password=secret")
                }
            }
            val history = SessionLogFile.REPORT_NAMES.map { folder.resolve(it).readText() }
            assertEquals(8, folder.listFiles()!!.size)
            assertTrue(history.first().contains("session=2"))
            assertTrue(history.last().contains("session=9"))
            assertTrue(history.any { it.contains("rejected code=0") })
            assertFalse(history.any { it.contains("secret") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun safeWifiMetadataSurvivesWithoutWeakeningCredentialFilters() {
        val lines = listOf(
            "Wi-Fi P2P preflight wifiEnabled=true locationEnabled=false permissionGranted=true stationMHz=5180",
            "Wi-Fi P2P create mode=FIXED_2_GHZ frequencyMHz=2437",
            "Wi-Fi P2P create rejected code=0 reason=generic error",
            "Wi-Fi P2P ready mode=FIXED_2_GHZ band=2.4 GHz channel=6 frequencyMHz=2437",
            "Wi-Fi P2P channel requestedMHz=2437 actualMHz=2412 matched=false",
            "wireless hotspot backend=Wi-Fi P2P iface=p2p0 host=192.168.49.1 band=5 GHz channel=36 frequency=5180MHz",
        )
        for (line in lines) assertNotNull(line, DiagnosticRedactor.redact(line))
        assertFalse(DiagnosticRedactor.redact(lines.last())!!.contains("192.168.49.1"))
    }

    @Test fun startupAndAirPlayMilestonesSurviveExportWithoutWeakeningPayloadFilters() {
        val lines = listOf(
            "wireless startup elapsedMs=10000 authenticated=true wifiConfigs=2 startRequests=1 tcpAccepted=0 sessionActive=false waitingFor=WiFi_discovery_or_AirPlay_TCP",
            "interfaceState=up multicast=true ipv4Usable=1 ipv6LinkLocal=1 ipv6Scoped=1",
            "p2pGroup=present owner=true sameGroup=true reportedP2pClients=0 association=unknown legacyClients=not_exposed",
            "bonjourAdded=0 bonjourResolved=0 bonjourAddressMismatch=0 connectProbes=0 connectProbe2xx=0 lastProbe=not_started",
            "control probe stage=REQUEST_SENT attempt=1 family=IPv6",
            "airplay TCP accepted family=IPv6",
            "airplay control request method=POST route=pair-verify contentBytes=128",
            "airplay control response status=200 contentBytes=32",
            "iap2 availability wired=unknown wireless=true themeAssets=unknown",
        )
        for (line in lines) assertEquals(line, line, DiagnosticRedactor.redact(line))
        assertNull(DiagnosticRedactor.redact("airplay rx POST /pair-verify body=secret"))
    }

    @Test fun hyphenatedIphoneUdidFromTransportIdentifierNeverReachesReports() {
        val line = "iap2 rx=0x4e0e device-transport-identifier bluetooth=C0:A6:00:29:58:0A usb=00008110-001E2C3A0123401E; resending 0x5703"
        val redacted = DiagnosticRedactor.redact(line)!!
        assertFalse(redacted, redacted.contains("001E2C3A0123401E"))
        assertFalse(redacted, redacted.contains("C0:A6"))
        assertTrue(redacted, redacted.contains("device-transport-identifier"))
        val folder = Files.createTempDirectory("diplay-udid-report").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log")).use { it.reset("started"); it.append(line) }
            val report = folder.resolve("diplay.log").readText()
            assertFalse(report, report.contains("001E2C3A0123401E"))
            assertTrue(report, report.contains("resending 0x5703"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun legacyAndUuidIdentifiersStayRedacted() {
        for (id in listOf("0123456789abcdef0123456789abcdef01234567", "123E4567-E89B-12D3-A456-426614174000")) {
            assertEquals("usb=[identifier];", DiagnosticRedactor.redact("usb=$id;"))
        }
        assertEquals("fps=60-120 rev=deadbeef-1", DiagnosticRedactor.redact("fps=60-120 rev=deadbeef-1"))
    }

    @Test fun locationFramesWithNmeaAreDroppedBeforeReports() {
        val nmea = "\$GPGGA,123519,3745.1234,N,12225.5678,W,1,08,0.9,545.4,M,46.9,M,,*47"
        val trace = Iap2FrameFormatter.format(Iap2TraceDirection.TX, "wireless-tunnel", Iap2ControlMessages.locationInformation(nmea))
        assertTrue(trace, trace.contains("NMEA"))
        assertNull(DiagnosticRedactor.redact(trace))
        val folder = Files.createTempDirectory("diplay-nmea-report").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log")).use { it.reset("started"); it.append(trace) }
            assertFalse(folder.resolve("diplay.log").readText().contains("3745.1234"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun personalDataInControllerAndHotspotLinesIsAlreadyMasked() {
        assertEquals("wireless RFCOMM connected address=[address]", DiagnosticRedactor.redact("wireless RFCOMM connected address=C0:A6:00:29:58:0A"))
        assertNull(DiagnosticRedactor.redact("Manual hotspot SSID does not match the active local AP configuration: 'CarWifi-1234'"))
        assertNull(DiagnosticRedactor.redact("WirelessHotspotInfo(backend=Wi-Fi P2P, ssid='DIRECT-xy-Car', passphrase=<redacted>, security=WPA2, channel=6)"))
    }

    @Test fun connectedDeviceListsAreRedactedToAReadableMarker() {
        assertEquals("Car Bluetooth audio: start A2DP connected=[redacted]",
            DiagnosticRedactor.redact("Car Bluetooth audio: start A2DP connected=[Jane's iPhone, 张三的 iPhone]"))
        assertEquals("Car Bluetooth audio: after30s HFP connected=[redacted]",
            DiagnosticRedactor.redact("Car Bluetooth audio: after30s HFP connected=[张三的 iPhone, John's iPad]"))
        assertEquals("connected=[redacted]",
            DiagnosticRedactor.redact("connected=[Jane's iPhone]"))
        assertEquals("connected=[redacted]",
            DiagnosticRedactor.redact("connected=[张三的 iPhone, John's iPad, \"Mom's iPhone\"]"))
    }

    @Test fun carBluetoothDeviceNamesNeverReachExportedReport() {
        val lines = listOf(
            "Car Bluetooth audio: start A2DP connected=[Jane's iPhone, 张三的 iPhone]",
            "Car Bluetooth audio: after30s HFP connected=[John's iPad, \"Mom's iPhone\", 李四的 iPhone]",
            "Car Bluetooth audio: start HFP connected=[王五的 iPhone]",
        )
        val folder = Files.createTempDirectory("diplay-bt-name-report").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file).use { log ->
                log.reset("started")
                lines.forEach { log.append(it) }
                assertTrue(log.awaitIdle(10_000))
            }
            val report = file.readText()
            for (name in listOf("Jane's iPhone", "张三的 iPhone", "John's iPad", "Mom's iPhone", "李四的 iPhone", "王五的 iPhone")) {
                assertFalse(report, report.contains(name))
            }
            assertTrue(report, report.contains("connected=[redacted]"))
            assertTrue(report, report.contains("Car Bluetooth audio: start A2DP"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun connectedNamesRuleDoesNotTouchLegalConnectionDiagnostics() {
        for (line in listOf("connected=true", "connected=false", "connected=1", "connected=[]", "CarPlay connected")) {
            assertEquals(line, DiagnosticRedactor.redact(line))
        }
        // Every non-empty list comes from logCarBluetoothAudio() and is redacted, including short
        // hex-looking and MAC-looking lists; only the empty "connected=[]" list stays untouched.
        assertEquals("connected=[redacted]", DiagnosticRedactor.redact("connected=[aa:bb:cc]"))
        assertEquals("connected=[redacted]", DiagnosticRedactor.redact("connected=[aa:bb:cc:dd:ee:ff]"))
        assertEquals("wireless RFCOMM connected address=[address]",
            DiagnosticRedactor.redact("wireless RFCOMM connected address=C0:A6:00:29:58:0A"))
    }

    @Test fun hexLetterNamesInConnectedListsAreRedacted() {
        for (line in listOf("connected=[Dad]", "connected=[Cafe]", "connected=[Bea, Abe]", "connected=[deadbeef, 1234]")) {
            assertEquals("connected=[redacted]", DiagnosticRedactor.redact(line))
        }
        assertEquals("Car Bluetooth audio: start A2DP connected=[redacted]",
            DiagnosticRedactor.redact("Car Bluetooth audio: start A2DP connected=[Bea, Abe, Cafe]"))
    }

    @Test fun bracketInsideANameDoesNotExposeTheRestOfTheList() {
        val redacted = DiagnosticRedactor.redact("Car Bluetooth audio: start A2DP connected=[A]B, Jane's iPhone]")!!
        assertEquals("Car Bluetooth audio: start A2DP connected=[redacted]", redacted)
        assertFalse(redacted.contains("Jane's iPhone"))
        assertFalse(redacted.contains("]B"))
    }

    @Test fun connectedNamesAreRedactedEvenOnLinesHittingTheExportCap() {
        val line = "Car Bluetooth audio: start A2DP connected=[Jane's iPhone, 张三的 iPhone] " + "z".repeat(650)
        assertTrue(line.length > 700)
        val redacted = DiagnosticRedactor.redact(line)!!
        assertTrue(redacted.length <= 700)
        assertFalse(redacted, redacted.contains("Jane's iPhone"))
        assertFalse(redacted, redacted.contains("张三的 iPhone"))
        assertTrue(redacted, redacted.contains("connected=[redacted]"))
    }

    // #1378: the lines that say why a screen stream or the event channel ended, and the NCM totals,
    // as the code writes them. A "body=" field or a field ending in "name" would drop a whole line.
    @Test fun streamEndReasonsSurviveTheReportWithoutContents() {
        val reset = SocketException("Connection reset")
        val lines = listOf(
            StreamEndDiagnostics.screenStreamEnded(110, StreamEndDiagnostics.screenPeerEof()),
            StreamEndDiagnostics.screenStreamEnded(110, StreamEndDiagnostics.screenPeerEofInside("body", 20, 64)),
            StreamEndDiagnostics.screenStreamEnded(110, StreamEndDiagnostics.screenBodySizeOutOfRange(-1, 8 shl 20)),
            StreamEndDiagnostics.screenStreamEnded(
                110,
                StreamEndDiagnostics.screenDecryptFailed(812, 52_344, AEADBadTagException("Tag mismatch 00112233445566778899aabbccddeeff")),
            ),
            StreamEndDiagnostics.screenStreamEnded(111, StreamEndDiagnostics.screenSocketError(reset)),
            StreamEndDiagnostics.screenStreamEnded(110, StreamEndDiagnostics.screenReceiveError(IllegalStateException("decoder gone"))),
            StreamEndDiagnostics.eventDecryptFailed(1_234, AEADBadTagException("Tag mismatch")),
            StreamEndDiagnostics.eventReadFailed(reset),
            StreamEndDiagnostics.eventCommandFailed("forceKeyFrame", SocketException("Broken pipe")),
            StreamEndDiagnostics.eventConnectionClosed("peer EOF"),
            "CONNECTION_DIAGNOSTIC attempt=2 run=1 phase=CONTROL " + NcmUsbBridge.resyncTotals(150, 31_451, 98_765_432),
        )
        for (line in lines) assertEquals(line, DiagnosticRedactor.redact(line))
        assertEquals(
            listOf(
                "screen stream ended type=110 reason=peer EOF",
                "screen stream ended type=110 reason=peer EOF in body after 20 of 64 bytes",
                "screen stream ended type=110 reason=body size out of range bodySize=4294967295 max=8388608",
                // The class only: an AEAD message never reaches the report.
                "screen stream ended type=110 reason=frame decrypt failed frame=812 bodySize=52344 error=AEADBadTagException",
                "screen stream ended type=111 reason=socket error SocketException: Connection reset",
                "screen stream ended type=110 reason=receive error IllegalStateException: decoder gone",
                "airplay event decrypt failed encrypted=1234 error=AEADBadTagException",
                "airplay event read failed error=SocketException: Connection reset",
                "airplay event command failed type=forceKeyFrame error=SocketException: Broken pipe",
                "airplay event connection closed reason=peer EOF",
                "CONNECTION_DIAGNOSTIC attempt=2 run=1 phase=CONTROL NCM bulk-in resync totals resyncs=150 skippedBytes=31451 inBytes=98765432",
            ),
            lines,
        )
    }

    @Test fun addressesInASocketErrorAreRedacted() {
        val error = SocketException("failed to connect to /fe80::1c2b:3d4e:5f60:7182%ncm0 (port 7000) from /192.168.49.1 (port 50000)")
        val redacted = DiagnosticRedactor.redact(StreamEndDiagnostics.screenStreamEnded(110, StreamEndDiagnostics.screenSocketError(error)))!!
        assertTrue(redacted, redacted.startsWith("screen stream ended type=110 reason=socket error SocketException: failed to connect to /[ip]"))
        assertFalse(redacted, redacted.contains("fe80"))
        assertFalse(redacted, redacted.contains("192.168.49.1"))
    }

    @Test fun aMultilineOrLongErrorStaysOneBoundedLineInTheReport() {
        // A line break would make the redactor drop the line, and with it the reason.
        val error = IOException("first\r\nsecond\t" + "x".repeat(1_000))
        val line = StreamEndDiagnostics.eventReadFailed(error)
        assertEquals(line, DiagnosticRedactor.redact(line))
        assertTrue(line, line.startsWith("airplay event read failed error=IOException: first second x"))
        assertEquals(StreamEndDiagnostics.MAX_MESSAGE_CHARS, line.substringAfter("IOException: ").length)
    }

    @Test fun anErrorMessageTheRedactorRejectsStillDropsTheLine() {
        assertNull(DiagnosticRedactor.redact(StreamEndDiagnostics.eventReadFailed(IOException("token=abc"))))
        assertNull(DiagnosticRedactor.redact(StreamEndDiagnostics.screenStreamEnded(110,
            StreamEndDiagnostics.screenSocketError(SocketException("peer hostname=Jane's iPhone")))))
    }
}
