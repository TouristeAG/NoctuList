package com.eventmanager.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null
        val longest = max(srcW, srcH)
        var sample = 1
        while (longest / sample > maxEdge * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, opts)
            ?: return null
        val scaled = scaleToMaxEdge(decoded, maxEdge)
        if (scaled !== decoded) decoded.recycle()
        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== decoded) scaled.recycle() else if (!ok) scaled.recycle()
        if (!ok) return null
        val bytes = out.toByteArray()
        return bytes.takeIf { it.isNotEmpty() && it.size <= maxBytes }
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
