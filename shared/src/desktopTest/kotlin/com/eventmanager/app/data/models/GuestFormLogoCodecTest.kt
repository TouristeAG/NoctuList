package com.eventmanager.app.data.models

import com.eventmanager.app.data.sync.InstitutionLogoStore
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A large logo with a transparent background must keep it once shrunk for the public pages. */
class GuestFormLogoCodecTest {
    @Test
    fun oversizeTransparentLogoStaysTransparent() {
        val size = 1600
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val random = Random(1)
        // Noisy opaque centre (so the PNG is big), fully transparent border.
        for (y in 400 until 1200) for (x in 400 until 1200) image.setRGB(x, y, (0xFF shl 24) or random.nextInt(0xFFFFFF))
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        val raw = "data:image/png;base64,${InstitutionLogoStore.encode(out.toByteArray())}"
        assertTrue(raw.length > GuestFormLogoCodec.MAX_DATA_URI_CHARS, "test logo must be oversize")

        val fitted = GuestFormLogoCodec.fitForFirestore(raw)
        assertTrue(fitted.startsWith("data:image/png;base64,"), fitted.take(40))
        assertTrue(fitted.length <= GuestFormLogoCodec.MAX_DATA_URI_CHARS)
        val decoded = ImageIO.read(ByteArrayInputStream(GuestFormLogoCodec.decodeBytes(fitted)!!))
        assertEquals(0, decoded.getRGB(0, 0) ushr 24, "corner must stay transparent")
    }
}
