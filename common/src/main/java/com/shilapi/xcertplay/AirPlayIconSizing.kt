package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * The car-button icon travels inside the /info reply the iPhone reads while connecting. A custom
 * icon was saved as a 1024-pixel PNG, which can be megabytes for a photo, and the iPhone stopped
 * connecting after it was replaced (#567). Send it at the size of the packaged icon instead.
 */
internal object AirPlayIconSizing {
    const val MAX_PIXELS = 192

    // A 1024-pixel crop decodes at full size; only larger images are subsampled, to at least 8x the icon.
    private const val DECODE_HEADROOM = 8

    /** Square PNG bytes no larger than [MAX_PIXELS]; null when [encoded] is not a square image. */
    fun fit(encoded: ByteArray): Pair<Int, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val size = bounds.outWidth
        if (size <= 0 || size != bounds.outHeight) return null
        if (size <= MAX_PIXELS) return size to encoded
        // inSampleSize keeps one pixel in every block of a PNG, which leaves jagged edges. Use it only
        // to bound memory for huge images, then halve with filtering so each step averages 2x2 pixels.
        var sample = 1
        while (size / (sample * 2) >= MAX_PIXELS * DECODE_HEADROOM) sample *= 2
        var scaled = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        while (scaled.width / 2 >= MAX_PIXELS) scaled = replace(scaled, scaled.width / 2)
        scaled = replace(scaled, MAX_PIXELS)
        val bytes = ByteArrayOutputStream().use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
        }
        scaled.recycle()
        return bytes?.let { MAX_PIXELS to it }
    }

    /** The largest power-of-two subsample that keeps both sides of a [width]x[height] image at least [targetPixels]. */
    fun previewSampleSize(width: Int, height: Int, targetPixels: Int): Int {
        var sample = 1
        if (width <= 0 || height <= 0 || targetPixels <= 0) return sample
        while (minOf(width, height) / (sample * 2) >= targetPixels) sample *= 2
        return sample
    }

    private fun replace(bitmap: Bitmap, size: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
