package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.createCardReaderService
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.data.nfc.NfcUid
import com.eventmanager.app.ui.platform.NfcUidListenerEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun NfcUidInfoRow(uid: String, isPhone: Boolean, modifier: Modifier = Modifier) {
    if (uid.isBlank()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Nfc, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(
                text = stringResource(Res.string.nfc_uid_label),
                style = if (isPhone) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = uid,
                style = if (isPhone) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun NfcUidCaptureContent(
    platformContext: PlatformContext,
    manualUid: String,
    onManualUidChange: (String) -> Unit,
    onConfirmUid: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
    statusMessage: String? = null,
    onStatusMessageChange: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var scannedUid by remember { mutableStateOf<String?>(null) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    val readFailedMsg = stringResource(Res.string.nfc_uid_read_failed)
    val cardReader = remember(platformContext) { createCardReaderService(platformContext) }
    var externalReaderConnected by remember { mutableStateOf(cardReader.isReaderConnected()) }

    LaunchedEffect(platformContext) {
        while (true) {
            externalReaderConnected = withContext(Dispatchers.IO) {
                cardReader.refreshConnectionState()
                cardReader.isReaderConnected()
            }
            delay(800)
        }
    }

    LaunchedEffect(scannedUid) {
        scannedUid?.let { uid -> onManualUidChange(uid.uppercase()) }
    }

    NfcUidListenerEffect(
        platformContext = platformContext,
        enabled = true,
        onUidRead = { uid ->
            scannedUid = uid
            onStatusMessageChange?.invoke(null)
        },
        onScanStatus = { scanStatus = it },
    )

    val scanHint = when {
        scannedUid != null -> stringResource(Res.string.nfc_card_detected)
        !scanStatus.isNullOrBlank() -> scanStatus!!
        externalReaderConnected -> stringResource(Res.string.usb_reader_waiting_card)
        else -> stringResource(Res.string.scan_nfc_card_title)
    }
    val scanSubHint = when {
        scannedUid != null -> null
        externalReaderConnected -> null
        platformContext.isDesktop -> null
        else -> stringResource(Res.string.place_nfc_card_on_phone)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(Res.string.add_nfc_card_uid_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (externalReaderConnected || scannedUid != null || !scanStatus.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (scannedUid == null && externalReaderConnected) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = scanHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (scannedUid != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    fontWeight = if (scannedUid != null) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        scanSubHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(Res.string.enter_nfc_uid_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = manualUid,
            onValueChange = { onManualUidChange(it.uppercase()) },
            label = { Text(stringResource(Res.string.nfc_uid_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        statusMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            onCancel?.let { cancel ->
                TextButton(onClick = cancel) {
                    Text(stringResource(Res.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = {
                    val uid = NfcUid.normalize(manualUid)
                    if (uid.isBlank()) {
                        onStatusMessageChange?.invoke(readFailedMsg)
                    } else {
                        onConfirmUid(uid)
                    }
                },
            ) {
                Text(stringResource(Res.string.confirm))
            }
        }
    }
}

@Composable
fun AddNfcUidDialog(
    platformContext: PlatformContext,
    onDismiss: () -> Unit,
    onConfirmUid: (String) -> Unit
) {
    var manualUid by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = phoneFractionDialogProperties(),
    ) {
        DialogFractionSizer(profile = FractionalDialogProfile.Card) { maxDialogWidth, maxDialogHeight ->
            Card(
                modifier = Modifier
                    .widthIn(max = maxDialogWidth)
                    .heightIn(max = maxDialogHeight)
                    .padding(16.dp),
            ) {
                NfcUidCaptureContent(
                    platformContext = platformContext,
                    manualUid = manualUid,
                    onManualUidChange = { manualUid = it },
                    onConfirmUid = onConfirmUid,
                    onCancel = onDismiss,
                    statusMessage = statusMessage,
                    onStatusMessageChange = { statusMessage = it },
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
