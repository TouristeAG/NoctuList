package com.eventmanager.app.platform.hardware

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.util.Random
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopQrDecoderTest {
    @Test
    fun decodesHighContrastQrLikeAPhoneScreen() {
        val payload = "nl-guest-ABC123xyz"
        val frame = renderOnCanvas(
            qr = generateQr(payload, size = 280),
            canvasWidth = 640,
            canvasHeight = 480,
            qrSize = 260,
            black = Color.BLACK,
            white = Color.WHITE,
            background = Color(20, 20, 24),
            blurRadius = 0,
        )
        assertEquals(payload, DesktopQrDecoder.decode(frame))
    }

    @Test
    fun decodesSmallLowContrastBlurredQrLikeAPrintedCard() {
        val payload = "nl-guest-printed-card-42"
        val frame = degradeLikeLaptopWebcam(
            renderOnCanvas(
                qr = generateQr(payload, size = 220),
                canvasWidth = 1280,
                canvasHeight = 720,
                qrSize = 96,
                black = Color(82, 80, 76),
                white = Color(188, 182, 172),
                background = Color(42, 46, 52),
                blurRadius = 1,
                rotationDegrees = 11.0,
            ),
        )

        assertNull(
            naiveSinglePassDecode(frame),
            "Regression: the old webcam path must keep failing this printed-card simulation",
        )
        assertEquals(payload, DesktopQrDecoder.decode(frame))
    }

    @Test
    fun decodesInvertedQrLikeAndroidMixedDecoder() {
        val payload = "nl-guest-inverted"
        val frame = renderOnCanvas(
            qr = generateQr(payload, size = 220),
            canvasWidth = 640,
            canvasHeight = 480,
            qrSize = 200,
            black = Color.WHITE,
            white = Color.BLACK,
            background = Color.BLACK,
            blurRadius = 0,
        )
        assertNull(naiveSinglePassDecode(frame))
        assertEquals(payload, DesktopQrDecoder.decode(frame))
    }

    @Test
    fun decodesWashedOutCardAtVgaResolution() {
        val payload = "volunteer:desk-scan"
        val frame = renderOnCanvas(
            qr = generateQr(payload, size = 180),
            canvasWidth = 640,
            canvasHeight = 480,
            qrSize = 92,
            black = Color(90, 90, 90),
            white = Color(186, 186, 180),
            background = Color(55, 58, 62),
            blurRadius = 1,
        )
        assertNotNull(DesktopQrDecoder.decode(frame))
        assertEquals(payload, DesktopQrDecoder.decode(frame))
    }

    private fun generateQr(content: String, size: Int): BufferedImage {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        return image
    }

    private fun renderOnCanvas(
        qr: BufferedImage,
        canvasWidth: Int,
        canvasHeight: Int,
        qrSize: Int,
        black: Color,
        white: Color,
        background: Color,
        blurRadius: Int,
        rotationDegrees: Double = 0.0,
    ): BufferedImage {
        val canvas = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        graphics.color = background
        graphics.fillRect(0, 0, canvasWidth, canvasHeight)
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val cardWidth = qrSize + 96
        val cardHeight = (cardWidth * 0.63f).toInt()
        val cardX = (canvasWidth - cardWidth) / 2
        val cardY = (canvasHeight - cardHeight) / 2
        if (rotationDegrees != 0.0) {
            graphics.transform = AffineTransform.getRotateInstance(
                Math.toRadians(rotationDegrees),
                canvasWidth / 2.0,
                canvasHeight / 2.0,
            )
        }
        graphics.color = white
        graphics.fillRoundRect(cardX, cardY, cardWidth, cardHeight, 18, 18)

        val faded = recolor(scale(qr, qrSize, qrSize), black, white)
        graphics.drawImage(faded, cardX + 36, cardY + (cardHeight - qrSize) / 2, null)
        graphics.dispose()
        return if (blurRadius > 0) boxBlur(canvas, blurRadius) else canvas
    }

    private fun degradeLikeLaptopWebcam(source: BufferedImage): BufferedImage {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getRGB(0, 0, width, height, pixels, 0, width)
        val rng = Random(42)
        val cx = width / 2.0
        val cy = height / 2.0
        val maxD = hypot(cx, cy)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]
                var r = (pixel ushr 16) and 0xFF
                var g = (pixel ushr 8) and 0xFF
                var b = pixel and 0xFF
                val shade = 1.0 - 0.22 * (hypot(x - cx, y - cy) / maxD)
                val noise = rng.nextInt(21) - 10
                r = ((r * shade).toInt() + noise).coerceIn(0, 255)
                g = ((g * shade).toInt() + noise).coerceIn(0, 255)
                b = ((b * shade).toInt() + noise).coerceIn(0, 255)
                pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val dest = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        dest.setRGB(0, 0, width, height, pixels, 0, width)
        return dest
    }

    private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return scaled
    }

    private fun recolor(source: BufferedImage, black: Color, white: Color): BufferedImage {
        val dest = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val luminance = (source.getRGB(x, y) shr 16) and 0xFF
                dest.setRGB(x, y, if (luminance < 128) black.rgb else white.rgb)
            }
        }
        return dest
    }

    private fun boxBlur(source: BufferedImage, radius: Int): BufferedImage {
        val size = (radius * 2 + 1).coerceAtLeast(3)
        val weight = 1f / size
        val kernel = FloatArray(size) { weight }
        val horizontal = ConvolveOp(Kernel(size, 1, kernel), ConvolveOp.EDGE_NO_OP, null)
        val vertical = ConvolveOp(Kernel(1, size, kernel), ConvolveOp.EDGE_NO_OP, null)
        val tmp = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val dest = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        horizontal.filter(source, tmp)
        vertical.filter(tmp, dest)
        return dest
    }

    private fun naiveSinglePassDecode(image: BufferedImage): String? {
        return try {
            val source = BufferedImageLuminanceSource(image)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        }
    }
}
