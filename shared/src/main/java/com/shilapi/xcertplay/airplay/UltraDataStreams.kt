package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.math.BigInteger
import java.net.InetAddress

/** Session-scoped Ultra streams, independent of DiPlay's existing iAP and video-in-car transports. */
internal class UltraDataStreams(private val session: AirPlaySession, private val config: AirPlayConfig) : Closeable {
    private data class Entry(val stream: RcsDataStream, val handler: RcsDataStreamHandler, var pending: Boolean = true)
    private val streams = linkedMapOf<Long, Entry>()
    private var enabled = emptySet<String>()
    private var nextId = 100L
    private var closed = false

    @Synchronized fun negotiated(features: List<String>) { enabled = features.toSet() }
    fun ownsRequest(request: Map<String, Any?>): Boolean =
        RcsClientTypes.findByUuid(request["clientTypeUUID"] as? String ?: "")?.let {
            it != RcsClientTypes.IAP_CHANNEL
        } == true

    @Synchronized fun open(request: Map<String, Any?>): Map<String, Any?>? {
        fun rejected(reason: String): Map<String, Any?>? {
            if (config.ultra != null) session.logDebug("Ultra channel rejected reason=$reason")
            return null
        }
        if (closed || streams.size >= 8) return rejected("closed-or-capacity")
        val type = RcsClientTypes.findByUuid(request["clientTypeUUID"] as? String ?: "") ?: return null
        val runtime = config.ultra?.runtime ?: return rejected("runtime-unavailable")
        if (type.feature?.wireName !in enabled || !runtime.supports(type)) return rejected("feature-or-handler-unavailable")
        val seed = unsignedSeed(request["seed"]) ?: return rejected("invalid-seed")
        val shared = session.sharedSecret ?: return rejected("pair-verification-incomplete")
        fun key(label: String) = AirPlayCrypto.hkdfSha512(shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32)
        val stream = RcsDataStream.open(type, key("DataStream-Output-Encryption-Key"),
            key("DataStream-Input-Encryption-Key"), session.localAddress ?: InetAddress.getByName("0.0.0.0"), session::logTrace)
        stream.onDiagnostic = session::logDebug
        try {
            val handler = runtime.create(stream) ?: run { stream.close(); return rejected("handler-unavailable") }
            val port = stream.listen()
            // A distinct ID is essential: TEARDOWN of one CAF channel must not close iAP or its peer.
            val id = nextId++
            streams[id] = Entry(stream, handler)
            session.logDebug("Ultra channel opened client=${type.name} streamID=$id port=$port")
            return linkedMapOf<String, Any?>("type" to 130, "streamID" to id, "dataPort" to port).apply {
                request["streamConnectionID"]?.let { put("streamConnectionID", it) }
            }
        } catch (e: Exception) { stream.close(); throw e }
    }

    fun responseSent() {
        val pending = synchronized(this) { streams.values.filter { it.pending }.onEach { it.pending = false } }
        pending.forEach { entry -> runCatching { entry.stream.attach(entry.handler) }
            .onFailure { entry.stream.close(); session.logDebug("Ultra channel attachment failed failureClass=${it.javaClass.simpleName}") } }
    }

    fun teardown(body: Any?) {
        val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty()
        val removed = synchronized(this) {
            if (entries.isEmpty()) streams.values.toList().also { streams.clear() }
            else {
                val allData = entries.any { it is Map<*, *> && (it["type"] as? Number)?.toInt() == 130 && it["streamID"] == null }
                if (allData) streams.values.toList().also { streams.clear() }
                else entries.mapNotNull {
                    val map = it as? Map<*, *> ?: return@mapNotNull null
                    if ((map["type"] as? Number)?.toInt() != 130) return@mapNotNull null
                    val id = (map["streamID"] as? Number)?.toLong() ?: return@mapNotNull null
                    streams.remove(id)
                }
            }
        }
        removed.forEach { it.stream.close() }
    }

    override fun close() {
        synchronized(this) { closed = true; enabled = emptySet() }
        teardown(null)
    }

    companion object {
        fun unsignedSeed(value: Any?): String? = when (value) {
            is Long -> java.lang.Long.toUnsignedString(value)
            is Int -> value.toLong().takeIf { it >= 0 }?.toString()
            is BigInteger -> value.takeIf { it.signum() >= 0 && it.bitLength() <= 64 }?.toString()
            else -> null
        }
    }
}
