package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.ContentRect
import com.shilapi.xcertplay.airplay.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayTouchMapperTest {
    // 1920x990 canvas letterboxed into a 1920x942 surround-view window.
    private val content = ContentRect.fit(PixelSize(1920, 990), 1920, 942)

    @Test
    fun contentCornersMapToTheCanvasCorners() {
        assertEquals(0.0, CarPlayTouchMapper.normalize(content.left, content.left, content.width), 1e-6)
        assertEquals(1.0, CarPlayTouchMapper.normalize(content.right, content.left, content.width), 1e-6)
        assertEquals(0.0, CarPlayTouchMapper.normalize(0f, content.top, content.height), 1e-6)
        assertEquals(1.0, CarPlayTouchMapper.normalize(942f, content.top, content.height), 1e-6)
    }

    @Test
    fun centreStaysCentred() {
        assertEquals(0.5, CarPlayTouchMapper.normalize(960f, content.left, content.width), 1e-6)
    }

    @Test
    fun dragsIntoABarClampToTheEdge() {
        assertEquals(0.0, CarPlayTouchMapper.normalize(1f, content.left, content.width), 1e-6)
        assertEquals(1.0, CarPlayTouchMapper.normalize(1919f, content.left, content.width), 1e-6)
    }
}
