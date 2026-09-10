package com.eventmanager.app.utils

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max

actual object ProfilePhotoCodec {
    actual fun compressToJpeg(sourceBytes: ByteArray): ByteArray? =
        compress(
            sourceBytes = sourceBytes,
            maxEdge = PROFILE_PHOTO_MAX_EDGE_PX,
            quality = PROFILE_PHOTO_JPEG_QUALITY,
            maxBytes = PROFILE_PHOTO_MAX_JPEG_BYTES,
        )

    actual fun compressToThumbnailJpeg(sourceBytes: ByteArray): ByteArray? =
        compress(
            sourceBytes = sourceBytes,
            maxEdge = PROFILE_PHOTO_THUMB_EDGE_PX,
            quality = PROFILE_PHOTO_THUMB_JPEG_QUALITY,
            maxBytes = PROFILE_PHOTO_THUMB_MAX_BYTES,
        )

    actual fun compressToGuestFormLogoJpeg(sourceBytes: ByteArray): ByteArray? =
        compress(
            sourceBytes = sourceBytes,
            maxEdge = GUEST_FORM_LOGO_MAX_EDGE_PX,
            quality = GUEST_FORM_LOGO_JPEG_QUALITY,
            maxBytes = GUEST_FORM_LOGO_MAX_JPEG_BYTES,
        )

    private fun compress(
        sourceBytes: ByteArray,
        maxEdge: Int,
        quality: Int,
        maxBytes: Int,
    ): ByteArray? {
        if (sourceBytes.isEmpty()) return null
        val decoded = runCatching {
            ImageIO.read(ByteArrayInputStream(sourceBytes))
        }.getOrNull() ?: return null
        val scaled = scaleToMaxEdge(decoded, maxEdge)
        val out = ByteArrayOutputStream()
        val writers = ImageIO.getImageWritersByFormatName("jpeg")
        if (!writers.hasNext()) return null
        val writer = writers.next()
        return try {
            val ios = ImageIO.createImageOutputStream(out)
            writer.output = ios
            val param = writer.defaultWriteParam
            if (param.canWriteCompressed()) {
                param.compressionMode = ImageWriteParam.MODE_EXPLICIT
                param.compressionQuality = quality / 100f
            }
            val rgb = if (scaled.type == BufferedImage.TYPE_INT_RGB) {
                scaled
            } else {
                val converted = BufferedImage(scaled.width, scaled.height, BufferedImage.TYPE_INT_RGB)
                val g = converted.createGraphics()
                g.drawImage(scaled, 0, 0, null)
                g.dispose()
                converted
            }
            writer.write(null, IIOImage(rgb, null, null), param)
            ios.close()
            val bytes = out.toByteArray()
            bytes.takeIf { it.isNotEmpty() && it.size <= maxBytes }
        } catch (_: Exception) {
            null
        } finally {
            writer.dispose()
        }
    }

    private fun scaleToMaxEdge(image: BufferedImage, maxEdge: Int): BufferedImage {
        val longest = max(image.width, image.height)
        if (longest <= maxEdge) return image
        val scale = maxEdge.toFloat() / longest.toFloat()
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, w, h, null)
        g.dispose()
        return scaled
    }
}
