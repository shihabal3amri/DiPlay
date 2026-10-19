package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Puts the custom car-button icon back when the in-session menu is cancelled.
 *
 * The icon is a 1024-pixel PNG of up to a few hundred kilobytes, and reading or rewriting it on the
 * main thread froze CarPlay touch for most of a second on slow head units. So every method runs on
 * one background thread, in order: [capture] when the menu opens keeps only a fingerprint,
 * [retainOriginal] keeps the bytes just before the menu itself replaces or removes the icon, and
 * [restore] reads and writes nothing unless the menu did, and then only when the file no longer
 * matches the fingerprint.
 */
internal class CustomAirPlayIconRollback(context: Context) {
    private data class Fingerprint(val size: Long, val sha256: String)

    private val context = context.applicationContext
    private var captured = false
    private var original: Fingerprint? = null
    private var originalBytes: ByteArray? = null

    fun capture() {
        original = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(context)?.let(::fingerprint)
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        captured = true
    }

    fun retainOriginal() {
        val expected = original ?: return
        if (originalBytes != null) return
        val bytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not keep the current AirPlay icon for settings rollback", error)
            null
        } ?: return
        if (fingerprint(bytes) == expected) originalBytes = bytes
    }

    /** Returns whether the icon file had to be rewritten or removed. */
    fun restore(): Boolean {
        if (!captured) return false
        val expected = original
        return try {
            if (expected == null) {
                // There was no custom icon, so any icon now is the menu's.
                val added = AirPlayPersistence.loadCustomAirPlayIconFile(context) ?: return false
                AirPlayPersistence.clearCustomAirPlayIcon(context)
                !added.exists()
            } else {
                // Without kept bytes the menu never touched the icon, so there is nothing to compare or put back.
                val bytes = originalBytes ?: return false
                val current = AirPlayPersistence.loadCustomAirPlayIconFile(context)?.let(::fingerprint)
                if (current == expected) return false
                AirPlayPersistence.saveCustomAirPlayIcon(context, bytes)
                true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
            false
        }
    }

    private fun fingerprint(file: File): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return Fingerprint(size, digest.digest().toHex())
    }

    private fun fingerprint(bytes: ByteArray): Fingerprint =
        Fingerprint(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).toHex())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CustomIconRollback"
    }
}
