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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.github.sarxos.webcam.Webcam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension

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

    LaunchedEffect(Unit) {
        status = openingMsg
        var webcam: Webcam? = null
        try {
            withContext(Dispatchers.IO) {
                DesktopWebcamSupport.ensureInitialized()
                webcam = Webcam.getWebcams().firstOrNull()
                val cam = webcam
                if (cam == null) {
                    withContext(Dispatchers.Main.immediate) {
                        onError(noWebcamMsg)
                    }
                    return@withContext
                }
                openWebcamForScan(cam)
                withContext(Dispatchers.Main.immediate) { status = pointMsg }

                var framesWithoutImage = 0
                var previewTick = 0
                while (isActive) {
                    ensureActive()
                    val image = cam.image
                    if (image == null) {
                        framesWithoutImage++
                        if (framesWithoutImage > 100) {
                            withContext(Dispatchers.Main.immediate) {
                                onError(noFramesMsg)
                            }
                            break
                        }
                        delay(50)
                        continue
                    }
                    framesWithoutImage = 0
                    val decoded = DesktopQrDecoder.decode(image)
                    if (decoded != null) {
                        withContext(Dispatchers.Main.immediate) { onQrDetected(decoded) }
                        break
                    }
                    if (previewTick++ % 2 == 0) {
                        val frame = image.toComposeImageBitmap()
                        withContext(Dispatchers.Main.immediate) { preview = frame }
                    }
                    delay(30)
                }
            }
        } catch (e: CancellationException) {
            // Closing the scanner / leaving the screen — normal, do not report as error.
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.Main.immediate) {
                onError(e.message?.takeIf { it.isNotBlank() } ?: "Webcam scan failed.")
            }
        } finally {
            // Always release the camera even when the LaunchedEffect is cancelled.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    webcam?.takeIf { it.isOpen }?.close()
                }
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
