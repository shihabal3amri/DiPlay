package com.shilapi.xcertplay

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.util.UUID

/**
 * Report text for video in car (#1180). A media URL can carry a session token in its path or query,
 * so these lines keep only the scheme and the host of a URL, protocol keys, sizes and error codes.
 * Exception messages stay out as well: players put the failing URL in them.
 */
internal object VideoDiagnostics {
    private const val MAX_WORD = 64
    private const val MAX_KEYS = 12
    private val unsafeWordCharacters = Regex("[^A-Za-z0-9_.:-]")

    fun queueItem(app: Any?, url: String, mediaType: Any?): String =
        "queue item app=${word(app)} scheme=${scheme(url)} host=${host(url)} mediaType=${word(mediaType)}"

    fun scheme(url: String): String = word(runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull())

    /** The host alone; the redactor masks one that is an IP address. */
    fun host(url: String): String = word(runCatching { Uri.parse(url).host?.lowercase() }.getOrNull())

    /** A protocol word (a message type, a media type, an app id) cut to plain characters. */
    fun word(value: Any?): String {
        val text = value?.toString()?.replace(unsafeWordCharacters, "")?.take(MAX_WORD)
        return if (text.isNullOrEmpty()) "none" else text
    }

    fun keys(keys: Collection<*>): String {
        val words = keys.take(MAX_KEYS).map(::word)
        val more = if (keys.size > MAX_KEYS) ",+${keys.size - MAX_KEYS}" else ""
        return words.joinToString(",", "[", "$more]")
    }

    /** "player error <name> code=<n>", the HTTP status when there is one, and the cause types. */
    fun playerError(error: PlaybackException): String {
        val chain = generateSequence<Throwable>(error) { it.cause }.take(5).toList()
        val http = chain.firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
        return "player error ${error.errorCodeName} code=${error.errorCode}" +
            (http?.let { " http=$it" } ?: "") +
            " causes=" + chain.drop(1).joinToString("<-") { it.javaClass.simpleName }.ifEmpty { "none" }
    }

    fun loadingVideo(url: String, network: String): String = "loading video scheme=${scheme(url)} $network"

    fun iphoneLoaded(scheme: String?, status: Int?, bytes: Int?, redirect: Boolean): String =
        "iPhone loaded ${word(scheme)} URL status=$status bytes=$bytes redirect=$redirect"

    /** Which encryption an HLS stream uses; key URIs reduced to their scheme, DRM systems to names. */
    fun hlsEncryption(
        keyScheme: String?,
        sampleAesSchemeType: String?,
        drm: List<UUID>?,
        sessionKeys: List<String?>,
        segments: Int,
    ): String =
        "HLS encryption aes128KeyScheme=${keyScheme?.let(::word)} sampleAesSchemeType=${sampleAesSchemeType?.let(::word)} " +
            "drm=${drm?.joinToString(",", "[", "]") { drmName(it) }} " +
            "sessionKeys=${sessionKeys.joinToString(",", "[", "]") { word(it) }} segments=$segments"

    /** A UUID would be masked as an identifier, so the known systems go by name. */
    fun drmName(uuid: UUID): String = when (uuid) {
        C.WIDEVINE_UUID -> "widevine"
        C.PLAYREADY_UUID -> "playready"
        C.CLEARKEY_UUID -> "clearkey"
        C.COMMON_PSSH_UUID -> "common-pssh"
        FAIRPLAY_UUID -> "fairplay"
        else -> "other"
    }

    private val FAIRPLAY_UUID: UUID = UUID.fromString("94ce86fb-07ff-4f43-adb8-93d2fa968ca2")
}
