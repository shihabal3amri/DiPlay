package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.*
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Fx11UltraProvisioning {
    fun create(profile: UltraVehicleProfile, sourceFactory: () -> UltraVehicleSource,
        diagnostic: (String) -> Unit = {}): AirPlayUltraProvisioning {
        // The registry supplies /info metadata; runtime factories below own per-stream state.
        val registration = CafPluginRegistration(profile.pluginId, "1.0", CafPluginRegistry.DEFAULT_CLIENT_TYPES,
            object : CafPluginHandler {}, pluginName = profile.pluginName)
        val factory = object : RcsDataStreamHandlerFactory {
            override fun supports(clientType: RcsClientType) = clientType in CafPluginRegistry.DEFAULT_CLIENT_TYPES ||
                clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
            override fun create(stream: RcsDataStream): RcsDataStreamHandler? = when (stream.clientType) {
                RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL -> uiSync(diagnostic)
                in CafPluginRegistry.DEFAULT_CLIENT_TYPES -> vehicle(profile, sourceFactory(), diagnostic)
                else -> null
            }
        }
        return AirPlayUltraProvisioning(pluginRegistrations = listOf(registration), uiSyncInfo = AirPlayUiSyncInfo.empty(),
            rcsFactories = (CafPluginRegistry.DEFAULT_CLIENT_TYPES + RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL).associateWith { factory },
            runtimeFeatures = setOf(AirPlayFeature.ALT_SCREEN, AirPlayFeature.UI_CONTEXT))
    }

    private fun uiSync(diagnostic: (String) -> Unit) = object : RcsDataStreamHandler {
        private val state = UltraUiSync()
        private var diagnosticCount = 0
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            require(message.body.size <= 512 * 1024) { "UI Sync payload too large" }
            val decoded = CarPlayUiSyncMessageFactory.decode(message.body)
            state.receive(decoded)?.let { stream.send(it.toRcsMessage()) }
            val status = "Ultra UI RX ${decoded.type.wireName} ssn=${decoded.sessionSequenceNumber} psn=${decoded.packetSequenceNumber} " +
                "command=${state.lastCommand}; receipt only, Local UI not implemented"
            diagnostic(status); stream.traceProtocol(status)
            if (diagnosticCount++ < 16) stream.reportDiagnostic("event=ui-sync type=${decoded.type.wireName} " +
                "command=${com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommand.entries.firstOrNull { it.wireName == state.lastCommand }?.wireName ?: "unknown"} " +
                "localUiImplemented=false")
        }
    }

    private fun vehicle(profile: UltraVehicleProfile, source: UltraVehicleSource, diagnostic: (String) -> Unit) = object : RcsDataStreamHandler {
        private val state = VehicleCafState(profile, source)
        private val lock = Any()
        private var closed = false
        private val reportedCommands = mutableSetOf<String>()
        private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fx11-caf-notify").apply { isDaemon = true } }
        override fun onStreamOpened(stream: RcsDataStream) {
            synchronized(lock) {
                if (closed) return
                source.start()
                diagnostic("Ultra CAF open ${stream.clientType.name}; mapped characteristics=${profile.bindings.size}")
                stream.reportDiagnostic("event=caf-ready bindings=${profile.bindings.size} negotiationOnly=${profile.bindings.isEmpty()}")
                timer.scheduleWithFixedDelay({
                    try { synchronized(lock) {
                        if (!closed) state.updates()?.let { stream.send(it.asRcsMessage()) }
                    } } catch (e: Exception) { stream.close() }
                }, 100, 100, TimeUnit.MILLISECONDS)
            }
        }
        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            require(message.body.size <= 512 * 1024) { "CAF payload too large" }
            val reader = CarAccessoryMessages.reader(message.body)
            synchronized(lock) {
                if (closed) return
                val response = state.handle(reader)
                stream.send(response.asRcsMessage())
                if (reportedCommands.add(reader.command.wireName)) stream.reportDiagnostic(
                    "event=caf-response command=${reader.command.wireName} bindings=${profile.bindings.size}")
                stream.traceProtocol("FX11 CAF RX ${reader.command.wireName} plugin=${reader.pluginId} transaction=${reader.transactionId}; " +
                    "bindings=${profile.bindings.size} validSignals=${source.snapshot().keys}")
            }
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            synchronized(lock) { closed = true; timer.shutdownNow(); state.clear(); source.close() }
            diagnostic("Ultra CAF closed ${stream.clientType.name}")
        }
    }
}
