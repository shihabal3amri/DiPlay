package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.ContentRect

/** Converts Android MotionEvents into normalized CarPlay touch contacts. */
object CarPlayTouchMapper {
    private const val MAX_CONTACTS = 2

    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<AirPlayContact> =
        contacts(event, ContentRect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    /** Maps touches relative to [content], the letterboxed video area inside the view. */
    fun contacts(event: MotionEvent, content: ContentRect): List<AirPlayContact> {
        val action = event.actionMasked
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val count = minOf(MAX_CONTACTS, event.pointerCount)
        val contacts = ArrayList<AirPlayContact>(count)
        for (index in 0 until count) {
            contacts.add(
                AirPlayContact(
                    id = index,
                    x = normalize(event.getX(index), content.left, content.width),
                    y = normalize(event.getY(index), content.top, content.height),
                    down = !allUp && index != liftedIndex,
                ),
            )
        }
        return contacts
    }

    /** Position inside the content span as 0..1, clamped so drags past a bar stay on the edge. */
    fun normalize(position: Float, start: Float, span: Float): Double =
        ((position - start).toDouble() / span.coerceAtLeast(1f)).coerceIn(0.0, 1.0)
}
