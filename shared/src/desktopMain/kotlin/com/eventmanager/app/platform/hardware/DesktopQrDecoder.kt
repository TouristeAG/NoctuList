package com.eventmanager.app.platform.hardware

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import kotlin.math.min

/**
 * Desktop ZXing path used by the webcam preview and the "open image" picker.
 *
 * Laptop webcams are typically fixed-focus and the previous single-pass decode
 * (full 640×480 frame, HybridBinarizer, no hints) only reliably reads emissive
 * phone/screen QRs. Printed cards are smaller, lower-contrast, slightly soft,
 * and often have print screening — the extra passes below close that gap.
 */
internal object DesktopQrDecoder {
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private val sharpenOp = ConvolveOp(
        Kernel(
            3,
            3,
            floatArrayOf(
                0f, -1f, 0f,
                -1f, 5f, -1f,
                0f, -1f, 0f,
            ),
        ),
        ConvolveOp.EDGE_NO_OP,
        null,
    )

    private val softenOp = ConvolveOp(
        Kernel(
            3,
            3,
            floatArrayOf(
                1f / 16f, 2f / 16f, 1f / 16f,
                2f / 16f, 4f / 16f, 2f / 16f,
                1f / 16f, 2f / 16f, 1f / 16f,
            ),
        ),
        ConvolveOp.EDGE_NO_OP,
        null,
    )

    fun decode(image: BufferedImage): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        tryDecode(image, reader, hybrid = true)?.let { return it }

        val working = scaleDownToMax(image, 1280)
        if (working !== image) {
            tryDecode(working, reader, hybrid = true)?.let { return it }
        }
        tryDecode(working, reader, hybrid = false)?.let { return it }

        for (fraction in floatArrayOf(0.68f, 0.46f)) {
            val crop = centerCrop(working, fraction)
            tryDecode(crop, reader, hybrid = true)?.let { return it }
            tryDecode(crop, reader, hybrid = false)?.let { return it }
        }

        val stretched = stretchContrast(working)
        tryDecode(stretched, reader, hybrid = true)?.let { return it }
        tryDecode(centerCrop(stretched, 0.68f), reader, hybrid = true)?.let { return it }

        filter(working, sharpenOp)?.let { sharp ->
            tryDecode(sharp, reader, hybrid = true)?.let { return it }
            tryDecode(centerCrop(sharp, 0.68f), reader, hybrid = true)?.let { return it }
        }
        filter(working, softenOp)?.let { soft ->
            tryDecode(soft, reader, hybrid = true)?.let { return it }
        }
        return null
    }

    private fun tryDecode(
        image: BufferedImage,
        reader: MultiFormatReader,
        hybrid: Boolean,
    ): String? {
        return try {
            val source = BufferedImageLuminanceSource(image)
            val binarizer = if (hybrid) HybridBinarizer(source) else GlobalHistogramBinarizer(source)
            reader.decodeWithState(BinaryBitmap(binarizer)).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun centerCrop(image: BufferedImage, fraction: Float): BufferedImage {
        val w = (image.width * fraction).toInt().coerceAtLeast(40)
            .coerceAtMost(image.width)
        val h = (image.height * fraction).toInt().coerceAtLeast(40)
            .coerceAtMost(image.height)
        val x = ((image.width - w) / 2).coerceAtLeast(0)
        val y = ((image.height - h) / 2).coerceAtLeast(0)
        val cropped = image.getSubimage(x, y, w, h)
        val minEdge = min(w, h)
        return if (minEdge < 400) scaleToMinEdge(cropped, 400) else cropped
    }

    private fun scaleDownToMax(image: BufferedImage, maxEdge: Int): BufferedImage {
        val max = maxOf(image.width, image.height)
        if (max <= maxEdge) return image
        val scale = maxEdge.toFloat() / max
        return scaleImage(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1))
    }

    private fun scaleToMinEdge(image: BufferedImage, minEdge: Int): BufferedImage {
        val current = min(image.width, image.height)
        if (current >= minEdge) return image
        val scale = minEdge.toFloat() / current
        return scaleImage(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1))
    }

    private fun scaleImage(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val dest = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = dest.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(image, 0, 0, width, height, null)
        graphics.dispose()
        return dest
    }

    private fun stretchContrast(image: BufferedImage): BufferedImage {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)

        val hist = IntArray(256)
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            hist[(r + g + g + b) ushr 2]++
        }
        val total = pixels.size
        val lowTarget = (total * 0.02).toInt().coerceAtLeast(1)
        val highTarget = (total * 0.98).toInt().coerceAtMost(total)
        var acc = 0
        var minL = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= lowTarget) {
                minL = i
                break
            }
        }
        acc = 0
        var maxL = 255
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= highTarget) {
                maxL = i
                break
            }
        }
        val range = (maxL - minL).coerceAtLeast(1)
        if (minL <= 12 && maxL >= 243) return image

        val lut = IntArray(256) { value ->
            ((value - minL) * 255 / range).coerceIn(0, 255)
        }
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = lut[(pixel ushr 16) and 0xFF]
            val g = lut[(pixel ushr 8) and 0xFF]
            val b = lut[pixel and 0xFF]
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val dest = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        dest.setRGB(0, 0, width, height, out, 0, width)
        return dest
    }

    private fun filter(image: BufferedImage, op: ConvolveOp): BufferedImage? {
        return runCatching {
            val rgb = ensureRgb(image)
            op.filter(rgb, BufferedImage(rgb.width, rgb.height, BufferedImage.TYPE_INT_RGB))
        }.getOrNull()
    }

    private fun ensureRgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_RGB) return image
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics: Graphics2D = rgb.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        return rgb
    }
}
