package com.shilapi.xcertplay

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoResourceFile
import java.io.IOException

/**
 * Loads what the car can load itself (http, https, data) directly and asks the iPhone for
 * anything else, as Apple's receiver does (unhandledURL): apps can serve custom-scheme playlists and
 * AES-128 keys through their resource loader on the iPhone. An opt-in experiment also delegates
 * HTTP(S) streaming resources to the iPhone, including HLS child resources without byte ranges. FairPlay keys (skd://) are not requested:
 * they need a licensed FairPlay receiver.
 */
@OptIn(UnstableApi::class)
internal class IphoneResolvingDataSource(
    private val upstream: DataSource,
    private val useIphoneNetwork: Boolean = false,
    private val diagnostic: (String) -> Unit = {},
    private val resolveOnIphone: (String) -> CarPlayVideo.LoadedUrl? = CarPlayVideo::resolveOnIphone,
) : DataSource {
    private var current: DataSource? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var resourceFile: VideoResourceFile? = null
    private var reportedUri: Uri? = null
    private val listeners = ArrayList<TransferListener>()
    private var lastRequest = -1L

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) {
            listeners += transferListener
            upstream.addTransferListener(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        check(current == null) { "already open" }
        responseHeaders = emptyMap()
        lastRequest = -1L
        return try { open(dataSpec, redirects = 0) } catch (error: Exception) {
            recordDiagnostic("event=iphone_open_failed request=$lastRequest causes=${VideoLoadDiagnostics.causes(error)}")
            throw error
        }
    }

    private fun open(dataSpec: DataSpec, redirects: Int): Long {
        val url = dataSpec.uri.toString()
        val scheme = dataSpec.uri.scheme?.lowercase()
        if (!VideoInCar.isPlayableUrl(url, iphoneLoadsAppSchemes = true)) {
            throw IOException("Unsupported video URL scheme")
        }
        if (scheme == "data" || (!useIphoneNetwork && scheme in DIRECT_SCHEMES)) {
            return openSource(upstream, dataSpec)
        }
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null) {
            throw IOException("iPhone video loading supports GET only")
        }
        if (useIphoneNetwork && (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong() ||
                dataSpec.httpRequestHeaders.keys.any { it.equals("Range", ignoreCase = true) })) {
            throw IOException("iPhone video relay does not support byte ranges")
        }
        val resolved = resolveMeasured(dataSpec) ?: throw IOException("iPhone did not load the $scheme URL")
        if (VideoLoadDiagnostics.TRACE_REQUESTS) {
            // Inspect after transfer-end so diagnostic file reads do not affect the bandwidth estimate.
            try {
                val size = resolved.data?.size?.toLong() ?: resolved.file?.length ?: 0L
                val summary = VideoLoadDiagnostics.inspect(resolved.data, resolved.file?.file, size)
                recordDiagnostic("event=iphone_resource request=$lastRequest status=${resolved.status ?: -1} bytes=$size redirect=${resolved.location != null} $summary")
            } catch (error: Exception) {
                recordDiagnostic("event=iphone_resource_inspection_failed request=$lastRequest causes=${VideoLoadDiagnostics.causes(error)}")
            }
        }
        Log.i(TAG, "iPhone loaded $scheme URL status=${resolved.status} bytes=${resolved.data?.size?.toLong() ?: resolved.file?.length} redirect=${resolved.location != null}")
        resolved.location?.let { location ->
            resolved.file?.close()
            if (redirects >= MAX_REDIRECTS) throw IOException("Too many iPhone URL redirects")
            // Reapply the policy: a resource loader's Location must not select an Android file,
            // content provider or APK asset. Custom-scheme redirects still go through the iPhone.
            return open(dataSpec.withUri(UriUtil.resolveToUri(url, location)), redirects + 1)
        }
        // An app's resource loader answers with data only: status 0 means no HTTP status, not a failure.
        if (resolved.status != null && resolved.status != 0 && resolved.status !in 200..299) {
            resolved.file?.close()
            if (resolved.status == 507) throw IphoneVideoResourceRejectedException("storage_pressure")
            throw IOException("iPhone status ${resolved.status}")
        }
        // Legacy app-scheme loads can still apply offsets locally to the complete response.
        if (resolved.status == 206) { resolved.file?.close(); throw IOException("Unexpected partial iPhone response") }
        responseHeaders = resolved.headers
        resolved.file?.let { file ->
            if (file.length > VideoInCar.MAX_RESOURCE_BYTES) { file.close(); throw IOException("iPhone video resource exceeds size limit") }
            resourceFile = file
            reportedUri = dataSpec.uri
            return openSource(FileDataSource(), dataSpec.withUri(Uri.fromFile(file.file)))
        }
        val bytes = resolved.data ?: throw IOException("iPhone sent no data")
        if (bytes.size > VideoInCar.MAX_RESOURCE_BYTES) throw IOException("iPhone video resource exceeds size limit")
        responseHeaders = resolved.headers
        return openSource(ByteArrayDataSource(bytes), dataSpec)
    }

    private fun resolveMeasured(dataSpec: DataSpec): CarPlayVideo.LoadedUrl? {
        // Measure the iPhone fetch and control-channel delivery, not subsequent memory/file reads.
        val observers = listeners.toList()
        observers.forEach { it.onTransferInitializing(this, dataSpec, true) }
        var started: Long? = null
        fun start() {
            if (started != null) return
            started = SystemClock.elapsedRealtime()
            observers.forEach { it.onTransferStart(this, dataSpec, true) }
        }
        var bytes = 0L
        var status: Int? = null
        try {
            val result = CarPlayVideo.withUrlLoadStart(::start, onRequest = { lastRequest = it }) {
                resolveOnIphone(dataSpec.uri.toString())
            } ?: return null
            start() // Injected resolvers may complete synchronously without an admission hook.
            status = result.status
            val size = result.data?.size?.toLong() ?: result.file?.length ?: 0L
            if (result.location == null && (status == null || status == 0 || status in 200..299) &&
                status != 206 && size in 1..VideoInCar.MAX_RESOURCE_BYTES.toLong()) {
                bytes = size
                observers.forEach { it.onBytesTransferred(this, dataSpec, true, size.toInt()) }
            }
            return result
        } finally {
            if (started != null) observers.forEach { it.onTransferEnd(this, dataSpec, true) }
            if (VideoLoadDiagnostics.TRACE_REQUESTS) recordDiagnostic("event=iphone_fetch request=$lastRequest scheme=${dataSpec.uri.scheme} status=${status ?: -1} bytes=$bytes elapsedMs=${started?.let { SystemClock.elapsedRealtime() - it } ?: 0}")
        }
    }

    private fun recordDiagnostic(message: String) {
        try { diagnostic(message) } catch (_: Exception) { }
    }

    private fun openSource(source: DataSource, dataSpec: DataSpec): Long {
        current = source // Own it before open(), which can leave a selected delegate after failing.
        try {
            return source.open(dataSpec)
        } catch (error: Exception) {
            try {
                close()
            } catch (cleanup: Exception) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IOException("not open")

    override fun getUri(): Uri? = reportedUri ?: current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (responseHeaders.isNotEmpty()) responseHeaders else current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
            responseHeaders = emptyMap()
            resourceFile?.close()
            resourceFile = null
            reportedUri = null
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val useIphoneNetwork: Boolean = false,
        private val diagnostic: (String) -> Unit = {},
        private val resolver: (String) -> CarPlayVideo.LoadedUrl? = CarPlayVideo::resolveOnIphone) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            IphoneResolvingDataSource(upstream.createDataSource(), useIphoneNetwork, diagnostic, resolver)
    }

    private companion object {
        const val TAG = "DiPlay-Video"
        const val MAX_REDIRECTS = 5
        val DIRECT_SCHEMES = setOf("http", "https", "data")
    }
}
