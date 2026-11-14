package com.shilapi.xcertplay.hud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The messages the cluster's resource channel takes on the AutoContainer binder's `sendInfo2(8, bytes)` and answers
 * through the registered callback's `receivedInfo2(8, bytes)`. Each is one small table in the FlatBuffers layout: a
 * command, a sub command, five integers and five strings. The channel is the one the head unit uses to tell the
 * cluster which language resource (theme override) to load; tools that change the cluster's theme use it the same way.
 */
internal object BydClusterThemeMessage {
    /** The `type` argument of the channel on both directions. */
    const val TYPE = 8
    const val QUERY = 1
    const val QUERY_ACTIVE = 4
    const val SELECT = 5
    const val QUERY_REPLY = 1001
    const val SELECT_REPLY = 1005

    private const val TABLE = 36
    private const val STRINGS_AT = 68
    private const val FIRST_STRING = 92
    private const val FIELDS = 12
    private const val INTS = 5
    private const val ACTIVE_PATH_AT = 2
    private const val MAX_TEXT = 65_536

    class Reply(val cmd: Int, val sub: Int, val ints: List<Int>, val strings: List<String>) {
        /**
         * The file the cluster has selected. On a Han the first string is always the name of the resource family
         * (`Di3F_DynastyOceanTranslate_V0001`) and the third is the selection: the full path of a file from the
         * translate folder, or `default` for the car's own.
         */
        val activePath: String get() = strings.getOrElse(ACTIVE_PATH_AT) { "" }
    }

    /** Asks which language resource is active: the reply holds its kind in the first integer and its path in [Reply.activePath]. */
    fun queryActive(): ByteArray = packet(QUERY, QUERY_ACTIVE, 0, "")

    /** Asks the cluster to use the resource file [name] from the translate folder. */
    fun select(name: String): ByteArray = packet(SELECT, 0, 0, name)

    /** Asks the cluster to go back to the car's own resource. */
    fun selectDefault(): ByteArray = packet(SELECT, 0, 1, "")

    fun packet(cmd: Int, sub: Int, value: Int, text: String): ByteArray = packet(cmd, sub, value, listOf(text))

    /** [texts] fill the five strings in order; the rest stay empty. */
    fun packet(cmd: Int, sub: Int, value: Int, texts: List<String>): ByteArray {
        require(texts.size <= INTS)
        val strings = List(INTS) { texts.getOrElse(it) { "" } }.map { it.toByteArray(Charsets.UTF_8) }
        var size = FIRST_STRING
        for (string in strings) size += padded(string)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, TABLE)
        buffer.putShort(4, 30) // the vtable: its length,
        buffer.putShort(6, 56) // the table's length,
        for (field in 0 until FIELDS) buffer.putShort(8 + 2 * field, (4 + 4 * field).toShort()) // and where each field is
        buffer.putInt(TABLE, 32) // the table points back at its vtable
        buffer.putInt(40, cmd)
        buffer.putInt(44, sub)
        buffer.putInt(48, value)
        var at = FIRST_STRING
        for ((index, string) in strings.withIndex()) {
            val field = STRINGS_AT + 4 * index
            buffer.putInt(field, at - field)
            buffer.putInt(at, string.size)
            buffer.position(at + 4)
            buffer.put(string)
            buffer.put(0)
            at += padded(string)
        }
        return buffer.array()
    }

    /** Reads a reply; null when the bytes are not a table of this shape. */
    fun parse(bytes: ByteArray): Reply? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val table = buffer.getInt(0)
            val vtable = table - buffer.getInt(table)
            val vtableSize = buffer.getShort(vtable).toInt() and 0xffff

            fun field(index: Int): Int {
                val slot = 4 + 2 * index
                return if (slot < vtableSize) buffer.getShort(vtable + slot).toInt() and 0xffff else 0
            }

            fun int(index: Int): Int = field(index).let { if (it == 0) 0 else buffer.getInt(table + it) }

            fun string(index: Int): String {
                val offset = field(index)
                if (offset == 0) return ""
                val reference = table + offset
                val at = reference + buffer.getInt(reference)
                val length = buffer.getInt(at)
                if (length < 0 || length > MAX_TEXT || at + 4 + length > bytes.size) return ""
                return String(bytes, at + 4, length, Charsets.UTF_8)
            }

            Reply(int(0), int(1), List(INTS) { int(2 + it) }, List(INTS) { string(7 + it) })
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    /** A string takes its length, its bytes and a zero, rounded up to four. */
    private fun padded(string: ByteArray) = (string.size + 8) and 3.inv()
}
