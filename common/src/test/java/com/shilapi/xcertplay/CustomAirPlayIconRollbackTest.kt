package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CustomAirPlayIconRollbackTest {
    private lateinit var context: Context
    private val original = ByteArray(600_000) { (it * 31).toByte() }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.clearCustomAirPlayIcon(context)
    }

    private fun iconFile(): File? = AirPlayPersistence.loadCustomAirPlayIconFile(context)

    private fun saveOriginal(): File {
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        return iconFile()!!.apply { setLastModified(1_000_000L) }
    }

    @Test fun anUnchangedIconIsNotRewritten() {
        val file = saveOriginal()
        val rollback = CustomAirPlayIconRollback(context).apply { capture() }
        assertFalse(rollback.restore())
        assertEquals(1_000_000L, file.lastModified())
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun anIconKeptForAnEditThatWasNotMadeIsNotRewritten() {
        val file = saveOriginal()
        val rollback = CustomAirPlayIconRollback(context).apply { capture(); retainOriginal() }
        assertFalse(rollback.restore())
        assertEquals(1_000_000L, file.lastModified())
    }

    @Test fun noIconStaysWithoutOne() {
        val rollback = CustomAirPlayIconRollback(context).apply { capture(); retainOriginal() }
        assertFalse(rollback.restore())
        assertNull(iconFile())
    }

    @Test fun aReplacedIconIsPutBack() {
        saveOriginal()
        val rollback = CustomAirPlayIconRollback(context).apply { capture(); retainOriginal() }
        // Same size, different bytes: only the hash tells them apart.
        AirPlayPersistence.saveCustomAirPlayIcon(context, original.copyOf().apply { this[size / 2] = (this[size / 2] + 1).toByte() })
        assertTrue(rollback.restore())
        assertArrayEquals(original, iconFile()!!.readBytes())
    }

    @Test fun aRemovedIconIsPutBack() {
        saveOriginal()
        val rollback = CustomAirPlayIconRollback(context).apply { capture(); retainOriginal() }
        AirPlayPersistence.clearCustomAirPlayIcon(context)
        assertTrue(rollback.restore())
        assertArrayEquals(original, iconFile()!!.readBytes())
    }

    @Test fun anIconAddedToNoneIsRemoved() {
        val rollback = CustomAirPlayIconRollback(context).apply { capture() }
        AirPlayPersistence.saveCustomAirPlayIcon(context, original)
        assertTrue(rollback.restore())
        assertNull(iconFile())
    }

    @Test fun nothingIsWrittenBeforeTheCaptureRan() {
        val file = saveOriginal()
        assertFalse(CustomAirPlayIconRollback(context).restore())
        assertEquals(1_000_000L, file.lastModified())
    }

    @Test fun thePreviewSampleKeepsBothSidesAtLeastThePreviewSize() {
        assertEquals(4, AirPlayIconSizing.previewSampleSize(1024, 1024, 144))
        assertEquals(1, AirPlayIconSizing.previewSampleSize(192, 192, 144))
        assertEquals(2, AirPlayIconSizing.previewSampleSize(2048, 300, 144))
        assertEquals(1, AirPlayIconSizing.previewSampleSize(0, 0, 144))
    }
}
