package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.shilapi.xcertplay.vehicle.PublicArtworkStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DimArtworkCacheTest {
    @Test fun dimReadsItsOwnCopyAndRepeatedHudAndFileReadsDoNotConsumeIt() {
        val hud = Bitmap.createBitmap(576, 576, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        var writes = 0
        val cache = DimArtworkCache { writes++; PublicArtworkStore.publish("read-again-test", it) }
        val output = cache.get(hud, true)
        val file = File(requireNotNull(output.uri).path!!)
        try {
            assertNotSame(hud, output.bitmap)
            assertEquals(192, output.bitmap!!.width)
            repeat(20) {
                assertEquals(output, cache.get(hud, true))
                // The same exported path can be decoded again, independently of HUD rendering.
                val dim = BitmapFactory.decodeFile(file.absolutePath)
                assertNotNull(dim); assertEquals(192, dim.width); dim.recycle()
                val frame = Bitmap.createBitmap(576, 576, Bitmap.Config.ARGB_8888)
                Canvas(frame).drawBitmap(hud, 0f, 0f, Paint())
                assertEquals(Color.RED, frame.getPixel(288, 288)); frame.recycle()
                assertFalse(hud.isRecycled)
            }
            assertEquals(1, writes)
            // Even a consumer disposing its private copy cannot invalidate the HUD's original.
            output.bitmap.recycle()
            assertFalse(hud.isRecycled)
            val replacement = cache.get(hud, true)
            assertNotSame(output.bitmap, replacement.bitmap)
            assertFalse(replacement.bitmap!!.isRecycled)
            replacement.bitmap.recycle()
        } finally { cache.clear(); hud.recycle(); file.parentFile!!.deleteRecursively() }
    }

    @Test fun bitmapChangesRefreshCopyButMetadataOnlyUpdatesKeepTheSameUri() {
        var writes = 0
        val cache = DimArtworkCache { Uri.parse("file:///dim/${++writes}.jpg") }
        val hud = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val first = cache.get(hud, false)
        assertNull(first.uri); assertNotSame(hud, first.bitmap)
        val permitted = cache.get(hud, true)
        assertSame(first.bitmap, permitted.bitmap); assertEquals(1, writes)
        repeat(300) { assertEquals(permitted, cache.get(hud, true)) }
        assertEquals(1, writes)
        hud.eraseColor(Color.BLUE)
        val next = cache.get(hud, true)
        assertNotEquals(permitted.uri, next.uri)
        assertEquals(Color.RED, first.bitmap!!.getPixel(0,0))
        assertEquals(Color.BLUE, next.bitmap!!.getPixel(0,0))
        assertEquals(DimArtworkCache.Output(), cache.get(null, true))
        assertFalse(hud.isRecycled); assertFalse(first.bitmap.isRecycled); assertFalse(next.bitmap.isRecycled)
        hud.recycle(); first.bitmap.recycle(); next.bitmap.recycle()
    }
}
