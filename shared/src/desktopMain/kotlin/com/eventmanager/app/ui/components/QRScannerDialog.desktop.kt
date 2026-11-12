package com.eventmanager.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.UidReadResult
import com.eventmanager.app.platform.createCardReaderService
import com.eventmanager.app.platform.hardware.DesktopExternalNfcReader
import com.eventmanager.app.platform.hardware.DesktopQrScanResult
import com.eventmanager.app.platform.hardware.DesktopQrScanner
import com.eventmanager.app.platform.hardware.DesktopWebcamQrScanView
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class QrDialogMode { Choose, Webcam, ImageLoading }

@Composable
actual fun QRScannerDialog(
    platformContext: PlatformContext,
    onDismiss: () -> Unit,
    onMatchFound: (ScannerMatch) -> Unit,
    volunteers: List<Volunteer>,
    guests: List<Guest>
) {
    var mode by remember { mutableStateOf(QrDialogMode.Choose) }
    var status by remember { mutableStateOf<String?>(null) }
    var duplicateMatches by remember { mutableStateOf<List<NfcUidMatchOption>>(emptyList()) }
    var isExternalReaderBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val cardReader = remember(platformContext) { createCardReaderService(platformContext) }
    var hasExternalReader by remember { mutableStateOf(DesktopExternalNfcReader.isConnected(settingsManager)) }
    val qrScanner = remember { DesktopQrScanner() }

    LaunchedEffect(Unit) {
        while (isActive) {
            hasExternalReader = withContext(Dispatchers.IO) {
                DesktopExternalNfcReader.refreshStatus(settingsManager).let {
                    it.usbConnected || it.bleAvailable
                }
            }
            delay(1500)
        }
    }

    val multipleMatchesTitle = stringResource(Res.string.nfc_uid_multiple_matches_title)
    val readFailedMsg = stringResource(Res.string.nfc_uid_read_failed)
    val cancelLabel = stringResource(Res.string.cancel)

    val waitingCardMsg = stringResource(Res.string.usb_reader_waiting_card_short)

    fun handlePayload(raw: String) {
        val (match, duplicates) = resolveDesktopScannerPayload(raw, volunteers, guests)
        when {
            match != null -> onMatchFound(match)
            duplicates.isNotEmpty() -> {
                duplicateMatches = duplicates
                status = multipleMatchesTitle
                mode = QrDialogMode.Choose
            }
            else -> {
                scope.launch {
                    status = getString(Res.string.nfc_uid_not_found, raw.trim())
                }
                mode = QrDialogMode.Choose
            }
        }
    }

    LaunchedEffect(hasExternalReader, mode) {
        if (!hasExternalReader || mode != QrDialogMode.Choose) {
            isExternalReaderBusy = false
            return@LaunchedEffect
        }
        try {
            var lastConnectionRefreshAtMs = 0L
            while (isActive && mode == QrDialogMode.Choose && duplicateMatches.isEmpty()) {
                val nowMs = System.currentTimeMillis()
                val connected = withContext(Dispatchers.IO) {
                    if (nowMs - lastConnectionRefreshAtMs >= 2_500L) {
                        cardReader.refreshConnectionState()
                        lastConnectionRefreshAtMs = nowMs
                    }
                    cardReader.isReaderConnected()
                }
                if (!connected) {
                    delay(800)
                    continue
                }
                isExternalReaderBusy = true
                when (val result = cardReader.readUid()) {
                    is UidReadResult.Success -> handlePayload(result.uid)
                    is UidReadResult.Fatal -> {
                        status = result.error
                        delay(500)
                    }
                    is UidReadResult.Retryable -> delay(20)
                    UidReadResult.NoReader -> delay(800)
                }
            }
        } finally {
            isExternalReaderBusy = false
        }
    }

    fun handleScanResult(result: DesktopQrScanResult) {
        when (result) {
            is DesktopQrScanResult.Success -> handlePayload(result.payload)
            is DesktopQrScanResult.Error -> {
                status = result.message
                mode = QrDialogMode.Choose
            }
            DesktopQrScanResult.NotFound -> {
                status = readFailedMsg
                mode = QrDialogMode.Choose
            }
            DesktopQrScanResult.Cancelled -> mode = QrDialogMode.Choose
        }
    }

    if (duplicateMatches.isNotEmpty()) {
        Dialog(onDismissRequest = {
            duplicateMatches = emptyList()
            onDismiss()
        }) {
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.nfc_uid_multiple_matches_title), style = MaterialTheme.typography.titleLarge)
                    duplicateMatches.forEach { option ->
                        Button(
                            onClick = {
                                duplicateMatches = emptyList()
                                onMatchFound(option.match)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(option.title)
                                Text(option.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            duplicateMatches = emptyList()
                            onDismiss()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text(cancelLabel) }
                }
            }
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.widthIn(min = 360.dp, max = 520.dp)
        ) {
            Column(
                Modifier.padding(24.dp).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                QrDialogHeader(
                    title = stringResource(Res.string.desktop_qr_dialog_title),
                    onClose = onDismiss,
                    closeLabel = cancelLabel,
                )

                status?.let { QrStatusBanner(it) }

                when (mode) {
                    QrDialogMode.Choose -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            QrOptionCard(
                                icon = Icons.Default.Videocam,
                                title = stringResource(Res.string.desktop_qr_option_camera_title),
                                emphasized = true,
                                onClick = {
                                    status = null
                                    mode = QrDialogMode.Webcam
                                },
                            )
                            QrOptionCard(
                                icon = Icons.Default.Image,
                                title = stringResource(Res.string.desktop_qr_option_image_title),
                                emphasized = false,
                                onClick = {
                                    status = null
                                    mode = QrDialogMode.ImageLoading
                                    scope.launch {
                                        handleScanResult(qrScanner.scanFromImageFile())
                                    }
                                },
                            )
                        }

                        if (hasExternalReader) {
                            QrReaderHint(
                                text = if (isExternalReaderBusy) waitingCardMsg
                                else stringResource(Res.string.desktop_qr_reader_hint),
                                active = isExternalReaderBusy,
                            )
                        }
                        if (DesktopExternalNfcReader.isBleConfigured(settingsManager)) {
                            BleReaderScannerStatusFooter(
                                platformContext = platformContext,
                                isExternalReaderBusy = isExternalReaderBusy,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                activeColor = MaterialTheme.colorScheme.primary,
                                idleWarnColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    QrDialogMode.Webcam -> {
                        DesktopWebcamQrScanView(
                            onQrDetected = { handlePayload(it) },
                            onError = { msg ->
                                status = msg
                                mode = QrDialogMode.Choose
                            },
                            onCancel = { mode = QrDialogMode.Choose },
                            showControls = false,
                        )
                        OutlinedButton(
                            onClick = { mode = QrDialogMode.Choose },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.desktop_qr_other_method))
                        }
                    }

                    QrDialogMode.ImageLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                            )
                            Text(
                                stringResource(Res.string.desktop_qr_opening_file_picker),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrDialogHeader(
    title: String,
    onClose: () -> Unit,
    closeLabel: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = closeLabel)
        }
    }
}

@Composable
private fun QrOptionCard(
    icon: ImageVector,
    title: String,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val container = if (emphasized) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    val badge = if (emphasized) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondaryContainer
    val badgeIcon = if (emphasized) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(badge),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = badgeIcon, modifier = Modifier.size(22.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = content.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun QrStatusBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun QrReaderHint(text: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Nfc,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
