package com.shilapi.xcertplay.airplay

import java.security.MessageDigest

/** Protocol metadata only: identifiers are hashed; URLs, titles and arbitrary string values never leave here. */
internal object VideoControlEvidence {
    private val operations = setOf("insertPlayQueueItem", "removePlayQueueItem", "setRate", "seek", "stop",
        "playbackInfo", "property", "setProperty", "playbackState", "error", "unhandledURL")
    private val safeKey = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
    private val secretKey = Regex("(?i)pass|token|certificate|private|authorization|cookie|secret|name$")

    fun operation(message: Map<String, Any?>): String =
        (message["type"] as? String)?.takeIf { it in operations } ?: "other"

    fun state(message: Map<String, Any?>): String =
        (message["name"] as? String)?.takeIf { it in setOf("playing", "paused", "stopped", "loading") } ?: "none"

    fun fingerprint(value: Any?): String {
        val bytes = when (value) {
            null -> return "none"
            is String -> byteArrayOf(1) + value.toByteArray(Charsets.UTF_8)
            is ByteArray -> byteArrayOf(2) + value
            is Number -> byteArrayOf(3) + value.toString().toByteArray(Charsets.US_ASCII)
            else -> return "other"
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun keys(value: Any?): String = (value as? Map<*, *>)?.keys.orEmpty()
        .filterIsInstance<String>().filter { safeKey.matches(it) && !secretKey.containsMatchIn(it) }
        .sorted().take(16).joinToString(",", "[", "]")

    private fun flags(value: Any?): String = when (value) {
        null -> "none"
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.filter {
            it.key is String && safeKey.matches(it.key as String) && !secretKey.containsMatchIn(it.key as String) &&
                (it.value is Boolean || it.value is Number)
        }.take(16).joinToString(",", "{", "}") { "${it.key}:${it.value}" }
        is List<*> -> value.take(16).filter { it is Boolean || it is Number }.joinToString(",", "[", "]")
        else -> "opaque:${fingerprint(value)}"
    }

    fun received(stream: Long, message: Map<String, Any?>): String {
        val item = message["item"] as? Map<*, *>
        val kind = (message["kind"] as? String)?.takeIf { it == "request" || it == "response" } ?: "none"
        return "video control rx stream=$stream op=${operation(message)} kind=$kind " +
            "request=${fingerprint(message["messageID"])} fields=${keys(message)} " +
            "item=${fingerprint(item?.get("uuid"))} player=${fingerprint(item?.get("playerLoggingID"))} " +
            "playerItem=${fingerprint(item?.get("playerItemLoggingID"))} " +
            "playbackRules=${flags(item?.get("playbackRestrictions"))} " +
            "referenceRules=${flags(item?.get("referenceRestrictions"))} " +
            "audio=${fingerprint(item?.get("audioMode"))} metadataFields=${keys(message["metadata"])} " +
            "metadataFlags=${flags(message["metadata"])}"
    }

    fun setup(stream: Map<String, Any?>, response: Map<String, Any?>): String =
        "video control setup stream=${(response["streamID"] as? Number)?.toLong()} " +
            "client=${fingerprint(stream["clientUUID"])} channel=${fingerprint(stream["channelID"])} " +
            "dedicated=${flags(stream["wantsDedicatedSocket"])} asIs=${flags(stream["sendMessageAsIs"])}"
}

/** Correlates video event writes and RTSP responses without waiting, retrying or changing playback. */
internal class VideoEventExchanges(private val log: (String) -> Unit) {
    private data class Entry(val stream: Long, val operation: String, val state: String)
    private val pending = LinkedHashMap<Int, Entry>()

    fun started(cseq: Int, stream: Long, message: Map<String, Any?>) {
        val entry = Entry(stream, VideoControlEvidence.operation(message), VideoControlEvidence.state(message))
        synchronized(pending) {
            pending[cseq] = entry
            while (pending.size > 64) pending.remove(pending.keys.first())
        }
        log("video event write_begin cseq=$cseq stream=$stream op=${entry.operation} state=${entry.state}")
    }

    fun written(cseq: Int) {
        val entry = synchronized(pending) { pending[cseq] } ?: return
        log("video event write_complete cseq=$cseq stream=${entry.stream}")
    }

    fun response(cseq: Int, status: Int) {
        val entry = synchronized(pending) { pending.remove(cseq) } ?: return
        log("video event response cseq=$cseq stream=${entry.stream} op=${entry.operation} state=${entry.state} status=$status")
    }

    fun failed(cseq: Int) {
        val entry = synchronized(pending) { pending.remove(cseq) } ?: return
        log("video event write_failed cseq=$cseq stream=${entry.stream}")
    }

    fun clear() {
        val count = synchronized(pending) { pending.size.also { pending.clear() } }
        if (count > 0) log("video event session_closed unanswered=$count")
    }
}
