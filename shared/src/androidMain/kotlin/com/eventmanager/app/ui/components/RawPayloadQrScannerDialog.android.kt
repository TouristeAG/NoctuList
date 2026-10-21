package com.eventmanager.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.firebase_join_scan
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun RawPayloadQrScannerDialog(
    platformContext: PlatformContext,
    onDismiss: () -> Unit,
    onPayload: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastScanText by remember { mutableStateOf<String?>(null) }
    var lastScanAtMs by remember { mutableStateOf(0L) }
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val hasBackFlash = remember(context) { context.backCameraHasFlash() }

    ReapplyScannerTorch(
        barcodeView = barcodeView,
        torchOn = torchOn,
        cameraReady = barcodeView != null,
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) errorMessage = "Camera permission required"
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { barcodeView?.setTorchOff() }
            runCatching { barcodeView?.pause() }
            barcodeView = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.firebase_join_scan)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (hasPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                DecoratedBarcodeView(ctx).also { view ->
                                    barcodeView = view
                                    view.decodeContinuous(object : BarcodeCallback {
                                        override fun barcodeResult(result: BarcodeResult?) {
                                            val raw = result?.text?.trim().orEmpty()
                                            if (raw.isBlank()) return
                                            val now = SystemClock.elapsedRealtime()
                                            if (raw == lastScanText && now - lastScanAtMs < 1200L) return
                                            lastScanText = raw
                                            lastScanAtMs = now
                                            onPayload(raw)
                                            onDismiss()
                                        }

                                        override fun possibleResultPoints(
                                            resultPoints: MutableList<com.google.zxing.ResultPoint>?,
                                        ) = Unit
                                    })
                                    view.resume()
                                }
                            },
                            modifier = Modifier.matchParentSize(),
                        )
                        if (hasBackFlash) {
                            QrScannerTorchButton(
                                torchOn = torchOn,
                                onToggle = { torchOn = !torchOn },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 10.dp, bottom = 10.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        errorMessage ?: "Waiting for camera…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                errorMessage?.takeIf { hasPermission }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
