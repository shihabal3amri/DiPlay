package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream

/** Keeps the supplied transparent wordmark intact; the car button requires a square PNG. */
internal object CarButtonBranding {
    fun defaultIcon(resources: Resources): Bitmap {
        val logo = requireNotNull(BitmapFactory.decodeResource(resources, R.raw.geely_car_button_logo))
        val size = AirPlayIconSizing.MAX_PIXELS
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val available = size * 5f / 6f
        val scale = available / maxOf(logo.width, logo.height)
        val width = logo.width * scale
        val height = logo.height * scale
        val left = (size - width) / 2f
        val top = (size - height) / 2f
        canvas.drawBitmap(logo, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        logo.recycle()
        return bitmap
    }

    fun defaultIconPng(resources: Resources): ByteArray {
        val bitmap = defaultIcon(resources)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
