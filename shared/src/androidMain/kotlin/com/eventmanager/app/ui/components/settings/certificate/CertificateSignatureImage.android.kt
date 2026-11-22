package com.eventmanager.app.ui.components.settings.certificate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal actual fun resizeSignatureToPng(bytes: ByteArray, maxWidth: Int): ByteArray? = try {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap == null) {
        null
    } else {
        val scaled = if (bitmap.width <= maxWidth) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, maxWidth, maxOf(1, bitmap.height * maxWidth / bitmap.width), true)
        }
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }
} catch (_: Exception) {
    null
}
