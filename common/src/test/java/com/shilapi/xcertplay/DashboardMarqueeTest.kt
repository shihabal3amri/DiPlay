package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class DashboardMarqueeTest {
    @Test fun beginningAndEndAreReadableBeforeLoopingAndProgressTicksDoNotRestart() {
        val marquee = DashboardMarquee()
        fun at(now: Long) = marquee.offset("title", "long title", 56f, now)
        assertEquals(0f, at(0), 0f)
        assertEquals(0f, at(1499), 0f)
        assertEquals(28f, at(2500), .01f)
        assertEquals(56f, at(3500), 0f)
        assertEquals(56f, at(4999), 0f)
        assertEquals(0f, at(5000), 0f)
    }

    @Test fun NewTrackWidthChangeAndShortTextResetTheRun() {
        val marquee = DashboardMarquee()
        marquee.offset("title", "a", 56f, 0)
        assertTrue(marquee.offset("title", "a", 56f, 3000) > 0f)
        assertEquals(0f, marquee.offset("title", "b", 56f, 3000), 0f)
        assertEquals(0f, marquee.offset("title", "b", 70f, 5000), 0f)
        assertEquals(0f, marquee.offset("title", "b", -1f, 8000), 0f)
        assertEquals(0f, marquee.offset("title", "b", 70f, 9000), 0f)
        marquee.clear()
        assertEquals(0f, marquee.offset("title", "b", 70f, 13000), 0f)
    }
    @Test fun holdsSleepUntilTransitionAndMovingTextKeepsItsFrameRate() {
        val marquee=DashboardMarquee()
        marquee.offset("title","long title",56f,0)
        assertEquals(1500L,marquee.nextDelay("title",0))
        assertEquals(1L,marquee.nextDelay("title",1499))
        assertEquals(33L,marquee.nextDelay("title",2000))
        assertEquals(1500L,marquee.nextDelay("title",3500))
        assertEquals(1L,marquee.nextDelay("title",4999))
        assertEquals(1500L,marquee.nextDelay("title",5000))
    }

}
