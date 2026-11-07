package com.shilapi.xcertplay.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expected bytes were produced by the original, deployed packet builder and read back by its parser (the helper
 * that already selects a cluster theme on a BYD Han), so these tests hold the port to the real protocol.
 */
class BydClusterThemeMessageTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val selectName = "240000001e003800040008000c001000140018001c002000240028002c00300000000000200000000500000000000000" +
        "000000000000000000000000000000000000000018000000580000005c0000006000000064000000000000003e000000" +
        "446933465f44796e617374794f6365616e5472616e736c6174655f56303030315f5472616e736c6174655f4469506c61" +
        "793161316232633364342e72636300000000000000000000000000000000000000000000000000000000000000000000"

    private val selectDefault = "240000001e003800040008000c001000140018001c002000240028002c00300000000000200000000500000000000000" +
        "0100000000000000000000000000000000000000180000001c0000002000000024000000280000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000"

    private val queryActive = "240000001e003800040008000c001000140018001c002000240028002c00300000000000200000000100000004000000" +
        "0000000000000000000000000000000000000000180000001c0000002000000024000000280000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000"

    private val queryReply = "240000001e003800040008000c001000140018001c002000240028002c0030000000000020000000e903000004000000" +
        "0200000000000000000000000000000000000000180000003c0000004000000044000000480000000000000021000000" +
        "2f73797374656d2f6c696236342f636f6d6d6f6e5472616e736c6174652e726363000000000000000000000000000000" +
        "0000000000000000000000000000000000000000"

    private val unicodeReply = "240000001e003800040008000c001000140018001c002000240028002c0030000000000020000000ed03000000000000" +
        "07000000000000000000000000000000000000001800000024000000280000002c00000030000000000000000a000000" +
        "c3a9e697a5e69cac2f7800000000000000000000000000000000000000000000000000000000000000000000"

    @Test fun selectingAPackIsTheCommandFiveWithTheFileName() {
        val name = "Di3F_DynastyOceanTranslate_V0001_Translate_DiPlay1a1b2c3d4.rcc"
        assertArrayEquals(bytes(selectName), BydClusterThemeMessage.select(name))
        assertArrayEquals(bytes(selectName), BydClusterThemeMessage.packet(5, 0, 0, name))
    }

    @Test fun goingBackToTheCarsOwnResourceIsTheSameCommandWithTheFlagSet() {
        assertArrayEquals(bytes(selectDefault), BydClusterThemeMessage.selectDefault())
    }

    @Test fun askingForTheActiveResourceIsTheQueryFourSubCommand() {
        assertArrayEquals(bytes(queryActive), BydClusterThemeMessage.queryActive())
    }

    @Test fun thePacketBuilderMatchesTheOriginalOnOtherValues() {
        assertArrayEquals(bytes(queryReply), BydClusterThemeMessage.packet(1001, 4, 2, "/system/lib64/commonTranslate.rcc"))
        assertArrayEquals(bytes(unicodeReply), BydClusterThemeMessage.packet(1005, 0, 7, "\u00e9\u65e5\u672c/x"))
    }

    @Test fun aQueryReplyGivesTheActiveKindAndPath() {
        val reply = BydClusterThemeMessage.parse(bytes(queryReply))!!
        assertEquals(BydClusterThemeMessage.QUERY_REPLY, reply.cmd)
        assertEquals(BydClusterThemeMessage.QUERY_ACTIVE, reply.sub)
        assertEquals(2, reply.ints[0])
        assertEquals("/system/lib64/commonTranslate.rcc", reply.strings[0])
        assertEquals(listOf("", "", "", ""), reply.strings.drop(1))
    }

    @Test fun textThatIsNotAscii() {
        val reply = BydClusterThemeMessage.parse(bytes(unicodeReply))!!
        assertEquals(BydClusterThemeMessage.SELECT_REPLY, reply.cmd)
        assertEquals(7, reply.ints[0])
        assertEquals("\u00e9\u65e5\u672c/x", reply.strings[0])
    }

    @Test fun anEmptyTextIsAnEmptyString() {
        val reply = BydClusterThemeMessage.parse(bytes(selectDefault))!!
        assertEquals(5, reply.cmd)
        assertEquals(1, reply.ints[0])
        assertEquals(List(5) { "" }, reply.strings)
    }

    @Test fun whatIsNotATableIsRefusedOrReadAsEmptyNeverAsAnError() {
        assertNull(BydClusterThemeMessage.parse(ByteArray(0)))
        assertNull(BydClusterThemeMessage.parse(ByteArray(7)))
        assertNull(BydClusterThemeMessage.parse(ByteArray(40) { 0x7f }))
        // The table's offset points outside the bytes.
        assertNull(BydClusterThemeMessage.parse(bytes(queryReply).also { it[0] = 0x7f }))
        // A table cut short is refused, not half read.
        assertNull(BydClusterThemeMessage.parse(bytes(queryReply).copyOf(150)))
    }

    @Test fun anOversizeStringLengthIsIgnored() {
        val reply = bytes(queryReply)
        // The first string's length, 33, rewritten to a huge value.
        val at = 92
        reply[at + 3] = 0x7f
        assertEquals("", BydClusterThemeMessage.parse(reply)!!.strings[0])
    }
}
