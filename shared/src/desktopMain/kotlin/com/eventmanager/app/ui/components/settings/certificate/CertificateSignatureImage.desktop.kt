package com.eventmanager.app.ui.components.settings.certificate

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

internal actual fun resizeSignatureToPng(bytes: ByteArray, maxWidth: Int): ByteArray? = try {
    val image = Image.makeFromEncoded(bytes)
    val scaled = if (image.width <= maxWidth) {
        image
    } else {
        val height = maxOf(1, image.height * maxWidth / image.width)
        val surface = Surface.makeRasterN32Premul(maxWidth, height)
        surface.canvas.drawImageRect(
            image,
            Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
            Rect.makeWH(maxWidth.toFloat(), height.toFloat()),
            SamplingMode.CATMULL_ROM,
            Paint(),
            true,
        )
        surface.makeImageSnapshot()
    }
    scaled.encodeToData(EncodedImageFormat.PNG)?.bytes
} catch (_: Exception) {
    null
}
