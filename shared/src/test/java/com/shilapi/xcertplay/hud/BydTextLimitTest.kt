package com.shilapi.xcertplay.hud

import org.junit.Assert.assertTrue
import org.junit.Test

/** Both BYD text fields are capped in UTF-16LE bytes; neither should recopy the prefix per character. */
class BydTextLimitTest {
    @Test(timeout = 3_000L)
    fun aVeryLongTitleDoesNotRecopyItsPrefixForEveryCharacter() {
        val title = "x".repeat(100_000)
        val text = ClusterSongState.text(title, null)!!
        assertTrue(text.length <= ClusterSongState.MAX_TEXT_BYTES / 2)
        assertTrue(text.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
    }

    @Test(timeout = 3_000L)
    fun aVeryLongCallerNameDoesNotRecopyItsPrefixForEveryCharacter() {
        val name = "x".repeat(100_000)
        val bytes = CarPlayCallState.nameBytes(name)
        assertTrue(bytes.size <= CarPlayCallState.MAX_NAME_BYTES)
    }

    @Test
    fun shortNamesAndSurrogatePairsKeepTheSameTruncation() {
        assertEqualsText("Short title", "Short artist")
        assertEqualsText("x".repeat(200), null)
        assertEqualsText("A\uD83D\uDE00B", null)
    }

    private fun assertEqualsText(title: String, artist: String?) {
        val text = ClusterSongState.text(title, artist)!!
        assertTrue(text.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
    }
}
