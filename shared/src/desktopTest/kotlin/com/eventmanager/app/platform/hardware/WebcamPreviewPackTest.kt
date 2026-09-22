package com.eventmanager.app.platform.hardware

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals

class WebcamPreviewPackTest {
    @Test
    fun keeps720pAndHalves1080p() {
        val hd = BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR)
        val packedHd = packWebcamPreview(hd, maxEdge = 1280)
        assertEquals(1280, packedHd.width)
        assertEquals(720, packedHd.height)

        val fullHd = BufferedImage(1920, 1080, BufferedImage.TYPE_3BYTE_BGR)
        val packedFullHd = packWebcamPreview(fullHd, maxEdge = 1280)
        assertEquals(960, packedFullHd.width)
        assertEquals(540, packedFullHd.height)
    }

    @Test
    fun bgrPreviewKeepsRedAndBlueChannels() {
        val red = solid(BufferedImage.TYPE_3BYTE_BGR, Color.RED.rgb)
        assertEquals(listOf(0, 0, 255, 255), firstPixel(red))

        val blue = solid(BufferedImage.TYPE_3BYTE_BGR, Color.BLUE.rgb)
        assertEquals(listOf(255, 0, 0, 255), firstPixel(blue))
    }

    @Test
    fun intRgbPreviewKeepsRedAndBlueChannels() {
        val red = solid(BufferedImage.TYPE_INT_RGB, Color.RED.rgb)
        assertEquals(listOf(0, 0, 255, 255), firstPixel(red))

        val blue = solid(BufferedImage.TYPE_INT_RGB, Color.BLUE.rgb)
        assertEquals(listOf(255, 0, 0, 255), firstPixel(blue))
    }

    @Test
    fun boxDownsampleAveragesNeighboringPixels() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR)
        image.setRGB(0, 0, Color.BLACK.rgb)
        image.setRGB(1, 0, Color.WHITE.rgb)
        image.setRGB(0, 1, Color.WHITE.rgb)
        image.setRGB(1, 1, Color.BLACK.rgb)

        val packed = packWebcamPreview(image, maxEdge = 1)
        assertEquals(1, packed.width)
        assertEquals(1, packed.height)
        // Two black + two white pixels → 127 per channel, opaque.
        assertEquals(listOf(127, 127, 127, 255), packed.bgra.map { it.toInt() and 0xFF })
    }

    private fun solid(type: Int, rgb: Int): PackedPreview {
        val image = BufferedImage(4, 2, type)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, rgb)
            }
        }
        return packWebcamPreview(image, maxEdge = 1280)
    }

    private fun firstPixel(packed: PackedPreview): List<Int> =
        packed.bgra.take(4).map { it.toInt() and 0xFF }
}
