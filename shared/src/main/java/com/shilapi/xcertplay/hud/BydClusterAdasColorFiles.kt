package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The car color as the cluster's block reads it: three one-row PNG files next to the heartbeat, whose widths carry
 * the hue, the saturation and the brightness. Only the image size is read there, as with the rest of the channel, so
 * the files hold no color data and need no permission. Missing files mean BYD's own color.
 *
 * Written by the shell-user helper (see [BydClusterAdasThemeMain]) into `/sdcard/.cluster/translate`, each through a
 * temporary name so the cluster never reads a half-written file.
 */
internal object BydClusterAdasColorFiles {
    /** Width = hue in degrees + 1. */
    const val HUE = "diplay-adas-ch.png"

    /** Width = saturation in percent + 1. */
    const val SATURATION = "diplay-adas-cs.png"

    /** Width = brightness in percent. */
    const val BRIGHTNESS = "diplay-adas-cv.png"

    val ALL = listOf(HUE, SATURATION, BRIGHTNESS)

    private const val MAX_WIDTH = 360
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    /** Writes [color] to [dir]; false when any file could not be written, in which case none is left half done. */
    fun write(dir: File, color: AdasCarColor): Boolean {
        val widths = mapOf(HUE to color.hue + 1, SATURATION to color.saturation + 1, BRIGHTNESS to color.brightness)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
        val temps = HashMap<String, File>()
        try {
            for ((name, width) in widths) {
                val temp = File(dir, ".$name.tmp")
                temp.writeBytes(png(width))
                temps[name] = temp
            }
            for ((name, temp) in temps) {
                val target = File(dir, name)
                if (!temp.renameTo(target)) {
                    target.delete()
                    if (!temp.renameTo(target)) throw IOException("rename $name")
                }
            }
            return true
        } catch (_: IOException) {
            temps.values.forEach { it.delete() }
            return false
        }
    }

    /** Removes the color, which gives BYD's own car back. */
    fun clear(dir: File) {
        ALL.forEach { File(dir, it).delete() }
    }

    /** The color in [dir], or null when it is not all there or not valid. */
    fun read(dir: File): AdasCarColor? {
        val hue = widthOf(File(dir, HUE)) ?: return null
        val saturation = widthOf(File(dir, SATURATION)) ?: return null
        val brightness = widthOf(File(dir, BRIGHTNESS)) ?: return null
        return runCatching { AdasCarColor(hue - 1, saturation - 1, brightness) }.getOrNull()
    }

    private fun widthOf(file: File): Int? {
        val header = try {
            file.inputStream().use { stream -> ByteArray(24).also { if (stream.read(it) != it.size) return null } }
        } catch (_: IOException) {
            return null
        }
        if (!header.copyOfRange(0, 8).contentEquals(SIGNATURE)) return null
        val width = ((header[16].toInt() and 0xff) shl 24) or ((header[17].toInt() and 0xff) shl 16) or
            ((header[18].toInt() and 0xff) shl 8) or (header[19].toInt() and 0xff)
        return width.takeIf { it in 1..MAX_WIDTH }
    }

    /** A valid 8-bit grayscale PNG of [width] x 1 black pixels. */
    internal fun png(width: Int): ByteArray {
        require(width in 1..MAX_WIDTH) { "width $width" }
        val header = ByteArray(13).also {
            writeInt(it, 0, width)
            writeInt(it, 4, 1)
            it[8] = 8 // bit depth
            // color type 0 (grayscale), no compression variants, no filter variants, no interlace: all zero
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", deflate(ByteArray(width + 1))) // the filter byte, then the pixels
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(128)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val length = ByteArray(4).also { writeInt(it, 0, data.size) }
        out.write(length)
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }.value
        out.write(ByteArray(4).also { writeInt(it, 0, crc.toInt()) })
    }

    private fun writeInt(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }
}
