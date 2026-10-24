package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.net.Uri
import com.shilapi.xcertplay.vehicle.PublicArtworkStore

/** Main-thread DIM output owns a small copy and a file; it never exports/recycles the HUD bitmap. */
internal class DimArtworkCache(
    private val publish: (Bitmap) -> Uri? = { PublicArtworkStore.publish("current", it) },
) {
    data class Output(val bitmap: Bitmap? = null, val uri: Uri? = null)
    private var source: Bitmap? = null
    private var generation = 0
    private var icon: Bitmap? = null
    private var uri: Uri? = null
    private var fileAttempted = false

    fun get(artwork: Bitmap?, allowPublicFile: Boolean): Output {
        val valid = artwork?.takeUnless { it.isRecycled }
        if (valid == null) { clear(); return Output() }
        if (valid !== source || valid.generationId != generation || icon?.isRecycled != false) {
            clear()
            source = valid
            generation = valid.generationId
            val edge = maxOf(valid.width, valid.height)
            icon = if (edge > 192) Bitmap.createScaledBitmap(valid,
                (valid.width * 192 / edge).coerceAtLeast(1), (valid.height * 192 / edge).coerceAtLeast(1), true)
            else valid.copy(Bitmap.Config.ARGB_8888, false)
        }
        // Lyrics/progress metadata must not churn files and delete a URI the OEM is still reading.
        if (allowPublicFile && !fileAttempted) {
            fileAttempted = true
            uri = icon?.let(publish)
        }
        return Output(icon, uri.takeIf { allowPublicFile })
    }

    fun clear() {
        // Published metadata may still hold icon. Let GC release it after all readers finish.
        source = null; generation = 0; icon = null; uri = null; fileAttempted = false
    }
}
