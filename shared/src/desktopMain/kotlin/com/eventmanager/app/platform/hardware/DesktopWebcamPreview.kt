package com.eventmanager.app.platform.hardware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.github.sarxos.webcam.Webcam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.awt.image.ComponentSampleModel
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.awt.image.SinglePixelPackedSampleModel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
fun DesktopWebcamQrScanView(
    onQrDetected: (String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val openingMsg = stringResource(Res.string.desktop_qr_opening_camera)
    val pointMsg = stringResource(Res.string.desktop_qr_point_at_code)
    val cancelLabel = stringResource(Res.string.cancel)
    val noWebcamMsg = desktopWebcamUnavailableMessage()
    val noFramesMsg = stringResource(Res.string.desktop_qr_no_frames)
    val cameraDeniedMsg = desktopCameraDeniedMessage()

    LaunchedEffect(Unit) {
        status = openingMsg
        var webcam: Webcam? = null
        var avfGrabber: MacAvfFrameGrabber? = null
        try {
            withContext(Dispatchers.IO) {
                DesktopWebcamSupport.ensureInitialized()
                val cameraAccess = MacCameraAuthorization.ensureAccess()
                when (cameraAccess) {
                    MacCameraAccessResult.Denied -> {
                        withContext(Dispatchers.Main.immediate) { onError(cameraDeniedMsg) }
                        return@withContext
                    }
                    MacCameraAccessResult.Authorized, MacCameraAccessResult.Skipped -> Unit
                }

                val cam = runCatching { Webcam.getWebcams().firstOrNull() }.getOrNull()
                val nativeOpened = cam != null && runCatching {
                    openWebcamForScan(cam)
                    cam.isOpen
                }.getOrDefault(false)
                if (nativeOpened) {
                    webcam = cam
                } else {
                    runCatching { cam?.takeIf { it.isOpen }?.close() }
                }

                val frameSource: () -> BufferedImage? = if (cam != null && nativeOpened) {
                    { cam.image }
                } else if (cameraAccess == MacCameraAccessResult.Authorized) {
                    val grabber = MacAvfFrameGrabber()
                    val started = grabber.start(1280, 720)
                    if (!started) {
                        grabber.close()
                        withContext(Dispatchers.Main.immediate) { onError(noWebcamMsg) }
                        return@withContext
                    }
                    avfGrabber = grabber
                    { grabber.grab() }
                } else {
                    withContext(Dispatchers.Main.immediate) { onError(noWebcamMsg) }
                    return@withContext
                }

                withContext(Dispatchers.Main.immediate) { status = pointMsg }

                scanWebcamFrames(
                    frameSource = frameSource,
                    publishPreview = { bitmap ->
                        withContext(Dispatchers.Main.immediate) { preview = bitmap }
                    },
                    publishQr = { payload ->
                        withContext(Dispatchers.Main.immediate) { onQrDetected(payload) }
                    },
                    publishNoFrames = {
                        withContext(Dispatchers.Main.immediate) { onError(noFramesMsg) }
                    },
                )
            }
        } catch (e: CancellationException) {
            // Closing the scanner / leaving the screen — normal, do not report as error.
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                onError(e.message?.takeIf { it.isNotBlank() } ?: "Webcam scan failed.")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { webcam?.takeIf { it.isOpen }?.close() }
                runCatching { avfGrabber?.close() }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 360.dp)
                .aspectRatio(4f / 3f),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator()
            }
        }
        Text(
            text = status ?: openingMsg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onCancel) {
            Text(cancelLabel)
        }
    }
}

@Composable
private fun desktopWebcamUnavailableMessage(): String {
    val os = System.getProperty("os.name").orEmpty()
    return when {
        os.contains("linux", ignoreCase = true) ->
            stringResource(Res.string.desktop_qr_no_webcam_linux)
        os.startsWith("Mac", ignoreCase = true) ->
            stringResource(Res.string.desktop_qr_no_webcam_mac)
        else -> stringResource(Res.string.desktop_qr_no_webcam_generic)
    }
}

@Composable
private fun desktopCameraDeniedMessage(): String {
    val os = System.getProperty("os.name").orEmpty()
    return if (os.startsWith("Mac", ignoreCase = true)) {
        stringResource(Res.string.desktop_qr_camera_denied_mac)
    } else {
        stringResource(Res.string.desktop_qr_camera_denied_generic)
    }
}

private fun openWebcamForScan(cam: Webcam) {
    try {
        DesktopWebcamSupport.applyPreferredScanResolution(cam)
        if (!cam.isOpen) cam.open()
    } catch (_: Exception) {
        runCatching { if (cam.isOpen) cam.close() }
        cam.viewSize = Dimension(640, 480)
        if (!cam.isOpen) cam.open()
    }
}

/**
 * Preview and QR decode used to share one loop: every HD frame ran the full
 * multi-pass decoder before the next image could be shown. A miss (no code in
 * frame, the common case while aiming) pays for every pass, so the preview
 * dropped to a few frames per second. A hit still returns early, which is why
 * detection itself stayed snappy.
 *
 * The capture loop now only grabs and publishes the preview. A second coroutine
 * keeps decoding the latest full-resolution frame with the same
 * [DesktopQrDecoder] pipeline, and never blocks the video.
 */
private suspend fun scanWebcamFrames(
    frameSource: () -> BufferedImage?,
    publishPreview: suspend (ImageBitmap) -> Unit,
    publishQr: suspend (String) -> Unit,
    publishNoFrames: suspend () -> Unit,
) {
    coroutineScope {
        val decodeSlot = AtomicReference<BufferedImage?>(null)
        val decodeBusy = AtomicBoolean(false)
        val detected = CompletableDeferred<String>()

        val decodeJob = launch(Dispatchers.Default) {
            while (isActive && !detected.isCompleted) {
                val frame = decodeSlot.getAndSet(null)
                if (frame == null) {
                    delay(8)
                    continue
                }
                decodeBusy.set(true)
                try {
                    val text = DesktopQrDecoder.decode(frame)
                    ensureActive()
                    if (text != null) {
                        detected.complete(text)
                        break
                    }
                } finally {
                    decodeBusy.set(false)
                }
            }
        }

        var awaitingFrameSinceNs = 0L
        try {
            while (isActive && !detected.isCompleted) {
                ensureActive()
                val loopStartNs = System.nanoTime()
                val image = frameSource()
                if (image == null) {
                    if (awaitingFrameSinceNs == 0L) awaitingFrameSinceNs = loopStartNs
                    if (loopStartNs - awaitingFrameSinceNs >= NO_FRAME_TIMEOUT_NS) {
                        publishNoFrames()
                        break
                    }
                    delay(8)
                    continue
                }
                awaitingFrameSinceNs = 0L

                // Copy pixels before the next grab or any suspend. Camera drivers
                // may reuse the frame buffer on the following capture.
                val previewBitmap = image.toPreviewImageBitmap(PREVIEW_MAX_EDGE_PX)
                offerDecodeFrame(image, decodeBusy, decodeSlot)
                publishPreview(previewBitmap)

                val spentMs = (System.nanoTime() - loopStartNs) / 1_000_000
                if (spentMs < PREVIEW_MIN_FRAME_MS) {
                    delay(PREVIEW_MIN_FRAME_MS - spentMs)
                }
            }
        } finally {
            decodeJob.cancel()
        }
        if (isActive && detected.isCompleted) {
            publishQr(detected.await())
        }
    }
}

private fun offerDecodeFrame(
    image: BufferedImage,
    decodeBusy: AtomicBoolean,
    decodeSlot: AtomicReference<BufferedImage?>,
) {
    // Copy only while the decoder is idle. The full-resolution frame stays
    // stable for ZXing; the capture loop never waits on it.
    if (decodeBusy.get() || decodeSlot.get() != null) return
    val copy = copyFrameForDecode(image)
    if (decodeBusy.get()) return
    decodeSlot.compareAndSet(null, copy)
}

private fun copyFrameForDecode(image: BufferedImage): BufferedImage {
    val type = image.type
    if (type == BufferedImage.TYPE_3BYTE_BGR ||
        type == BufferedImage.TYPE_INT_RGB ||
        type == BufferedImage.TYPE_INT_ARGB
    ) {
        val copy = BufferedImage(image.width, image.height, type)
        image.copyData(copy.raster)
        return copy
    }
    val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    val graphics = copy.createGraphics()
    graphics.drawImage(image, 0, 0, null)
    graphics.dispose()
    return copy
}

/**
 * [BufferedImage.toComposeImageBitmap] calls `getRGB` once per pixel (~20ms at
 * 720p, ~40ms at 1080p). Webcam frames are already BGR or packed ints, so the
 * preview copies those bytes straight into a Skia bitmap. Frames larger than
 * [maxEdge] are box-downsampled; the QR decoder still receives the original.
 */
private fun BufferedImage.toPreviewImageBitmap(maxEdge: Int): ImageBitmap {
    val packed = packWebcamPreview(this, maxEdge)
    val bitmap = Bitmap()
    val info = ImageInfo.makeS32(packed.width, packed.height, ColorAlphaType.UNPREMUL)
    if (!bitmap.allocPixels(info) || !bitmap.installPixels(info, packed.bgra, info.minRowBytes)) {
        return toComposeImageBitmap()
    }
    return bitmap.asComposeImageBitmap()
}

internal class PackedPreview(
    val width: Int,
    val height: Int,
    val bgra: ByteArray,
)

internal fun packWebcamPreview(image: BufferedImage, maxEdge: Int): PackedPreview {
    val sample = previewSampleStep(image.width, image.height, maxEdge)
    val outW = (image.width / sample).coerceAtLeast(1)
    val outH = (image.height / sample).coerceAtLeast(1)
    val bgra = ByteArray(outW * outH * 4)
    val packed = when (image.type) {
        BufferedImage.TYPE_3BYTE_BGR -> packBgr3(image, bgra, sample, outW, outH)
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_INT_BGR -> packPackedInt(image, bgra, sample, outW, outH)
        else -> false
    }
    if (!packed) {
        packFromBulkArgb(image, bgra, sample, outW, outH)
    }
    return PackedPreview(outW, outH, bgra)
}

private fun previewSampleStep(width: Int, height: Int, maxEdge: Int): Int {
    val longest = maxOf(width, height)
    if (longest <= maxEdge || maxEdge <= 0) return 1
    return maxOf(1, (longest + maxEdge - 1) / maxEdge)
}

private fun packBgr3(
    image: BufferedImage,
    dst: ByteArray,
    sample: Int,
    outW: Int,
    outH: Int,
): Boolean {
    val model = image.raster.sampleModel as? ComponentSampleModel ?: return false
    val bands = model.bandOffsets
    if (bands.size < 3) return false
    val buffer = image.raster.dataBuffer as? DataBufferByte ?: return false
    val src = buffer.data
    val base = buffer.offset
    val scan = model.scanlineStride
    val pixelStride = model.pixelStride
    val rOff = bands[0]
    val gOff = bands[1]
    val bOff = bands[2]
    if (sample == 2 && image.width >= 2 && image.height >= 2) {
        var k = 0
        var y = 0
        while (y < outH) {
            val row0 = base + (y * 2) * scan
            val row1 = row0 + scan
            var x = 0
            while (x < outW) {
                val i0 = row0 + (x * 2) * pixelStride
                val i1 = i0 + pixelStride
                val j0 = row1 + (x * 2) * pixelStride
                val j1 = j0 + pixelStride
                dst[k++] = avgChannel(src, i0 + bOff, i1 + bOff, j0 + bOff, j1 + bOff)
                dst[k++] = avgChannel(src, i0 + gOff, i1 + gOff, j0 + gOff, j1 + gOff)
                dst[k++] = avgChannel(src, i0 + rOff, i1 + rOff, j0 + rOff, j1 + rOff)
                dst[k++] = OPAQUE
                x++
            }
            y++
        }
        return true
    }
    var k = 0
    var y = 0
    while (y < outH) {
        val row = base + (y * sample) * scan
        var x = 0
        while (x < outW) {
            val i = row + (x * sample) * pixelStride
            dst[k++] = src[i + bOff]
            dst[k++] = src[i + gOff]
            dst[k++] = src[i + rOff]
            dst[k++] = OPAQUE
            x++
        }
        y++
    }
    return true
}

private fun packPackedInt(
    image: BufferedImage,
    dst: ByteArray,
    sample: Int,
    outW: Int,
    outH: Int,
): Boolean {
    val model = image.raster.sampleModel as? SinglePixelPackedSampleModel ?: return false
    val buffer = image.raster.dataBuffer as? DataBufferInt ?: return false
    val src = buffer.data
    val base = buffer.offset
    val scan = model.scanlineStride
    val bgr = image.type == BufferedImage.TYPE_INT_BGR
    if (sample == 2 && image.width >= 2 && image.height >= 2) {
        var k = 0
        var y = 0
        while (y < outH) {
            val row0 = base + (y * 2) * scan
            val row1 = row0 + scan
            var x = 0
            while (x < outW) {
                val p00 = src[row0 + x * 2]
                val p10 = src[row0 + x * 2 + 1]
                val p01 = src[row1 + x * 2]
                val p11 = src[row1 + x * 2 + 1]
                writeAveragedInt(dst, k, p00, p10, p01, p11, bgr)
                k += 4
                x++
            }
            y++
        }
        return true
    }
    var k = 0
    var y = 0
    while (y < outH) {
        val row = base + (y * sample) * scan
        var x = 0
        while (x < outW) {
            writeIntPixel(dst, k, src[row + x * sample], bgr)
            k += 4
            x++
        }
        y++
    }
    return true
}

private fun packFromBulkArgb(
    image: BufferedImage,
    dst: ByteArray,
    sample: Int,
    outW: Int,
    outH: Int,
) {
    val argb = IntArray(image.width * image.height)
    image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
    val stride = image.width
    if (sample == 2 && image.width >= 2 && image.height >= 2) {
        var k = 0
        var y = 0
        while (y < outH) {
            val row0 = (y * 2) * stride
            val row1 = row0 + stride
            var x = 0
            while (x < outW) {
                writeAveragedInt(
                    dst,
                    k,
                    argb[row0 + x * 2],
                    argb[row0 + x * 2 + 1],
                    argb[row1 + x * 2],
                    argb[row1 + x * 2 + 1],
                    bgr = false,
                )
                k += 4
                x++
            }
            y++
        }
        return
    }
    var k = 0
    var y = 0
    while (y < outH) {
        val row = (y * sample) * stride
        var x = 0
        while (x < outW) {
            writeIntPixel(dst, k, argb[row + x * sample], bgr = false)
            k += 4
            x++
        }
        y++
    }
}

private fun writeIntPixel(dst: ByteArray, offset: Int, pixel: Int, bgr: Boolean) {
    val r: Int
    val g: Int
    val b: Int
    if (bgr) {
        r = pixel and 0xFF
        g = (pixel ushr 8) and 0xFF
        b = (pixel ushr 16) and 0xFF
    } else {
        b = pixel and 0xFF
        g = (pixel ushr 8) and 0xFF
        r = (pixel ushr 16) and 0xFF
    }
    dst[offset] = b.toByte()
    dst[offset + 1] = g.toByte()
    dst[offset + 2] = r.toByte()
    dst[offset + 3] = OPAQUE
}

private fun writeAveragedInt(
    dst: ByteArray,
    offset: Int,
    p00: Int,
    p10: Int,
    p01: Int,
    p11: Int,
    bgr: Boolean,
) {
    if (bgr) {
        dst[offset] = channelAverage(p00, p10, p01, p11, 16)
        dst[offset + 1] = channelAverage(p00, p10, p01, p11, 8)
        dst[offset + 2] = channelAverage(p00, p10, p01, p11, 0)
    } else {
        dst[offset] = channelAverage(p00, p10, p01, p11, 0)
        dst[offset + 1] = channelAverage(p00, p10, p01, p11, 8)
        dst[offset + 2] = channelAverage(p00, p10, p01, p11, 16)
    }
    dst[offset + 3] = OPAQUE
}

private fun channelAverage(p00: Int, p10: Int, p01: Int, p11: Int, shift: Int): Byte {
    val sum = ((p00 ushr shift) and 0xFF) +
        ((p10 ushr shift) and 0xFF) +
        ((p01 ushr shift) and 0xFF) +
        ((p11 ushr shift) and 0xFF)
    return (sum ushr 2).toByte()
}

private fun avgChannel(src: ByteArray, i0: Int, i1: Int, i2: Int, i3: Int): Byte {
    val sum = (src[i0].toInt() and 0xFF) +
        (src[i1].toInt() and 0xFF) +
        (src[i2].toInt() and 0xFF) +
        (src[i3].toInt() and 0xFF)
    return (sum ushr 2).toByte()
}

private const val OPAQUE: Byte = 0xFF.toByte()

private const val PREVIEW_MAX_EDGE_PX = 1280
private const val PREVIEW_MIN_FRAME_MS = 33L
private const val NO_FRAME_TIMEOUT_NS = 5_000_000_000L
