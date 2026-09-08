package com.eventmanager.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.google.zxing.DecodeHintType
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.nfc.NfcUid
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
import com.eventmanager.app.hardware.Acr122uUsbNfcReader
import com.eventmanager.app.hardware.Acr1255uj1BleNfcReader
import com.eventmanager.app.hardware.ExternalAcsUidReader
import com.eventmanager.app.hardware.ExternalReaderPermissions
import com.eventmanager.app.hardware.rememberUsbHardwareGeneration
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

data class QRCodeData(
    val type: String,
    val version: Int,
    val id: String,
    val sheetsId: String?,
    val name: String,
    val abbr: String?
)

private fun ScannerMatch.hasAdminPrivileges(): Boolean = when (this) {
    is ScannerMatch.VolunteerMatch -> volunteer.isAdmin
    is ScannerMatch.GuestMatch -> guest.isAdmin
}

/**
 * Resolves a plain NanoID QR against volunteers ([Volunteer.id]) and guests ([Guest.nanoId]).
 * When both match (same string in both namespaces), prefers the profile with admin privileges
 * so the first scan is not denied in favor of a non-admin volunteer.
 */
private fun resolveNanoidScannerMatch(
    volunteers: List<Volunteer>,
    permanentGuests: List<Guest>,
    rawId: String
): ScannerMatch? {
    val trimmed = rawId.trim()
    if (trimmed.isEmpty()) return null
    val volMatches = volunteers.filter { it.id == trimmed }
    val guestMatches = permanentGuests.filter { it.nanoId == trimmed }
    val all = volMatches.map { ScannerMatch.VolunteerMatch(it) } + guestMatches.map { ScannerMatch.GuestMatch(it) }
    if (all.isEmpty()) return null
    return all.firstOrNull { it.hasAdminPrivileges() } ?: all.first()
}

/** Covers USB host-permission flow (~8s timeout in reader) with slack so the user can respond before we request camera. */
private const val USB_HOST_PERMISSION_BEFORE_CAMERA_WAIT_MS = 15_000L

/**
 * Check if this is an NVIDIA Shield tablet
 */
fun isNvidiaShieldTablet(): Boolean {
    val manufacturer = android.os.Build.MANUFACTURER.lowercase()
    val model = android.os.Build.MODEL.lowercase()
    val device = android.os.Build.DEVICE.lowercase()
    
    return manufacturer.contains("nvidia") && 
           (model.contains("shield") || device.contains("shield"))
}

/**
 * Get device-specific camera initialization delay
 * NVIDIA Shield tablets may need more time to initialize
 */
fun getCameraInitializationDelay(): Long {
    return if (isNvidiaShieldTablet()) {
        1000L // 1 second for NVIDIA Shield
    } else {
        500L  // 500ms for other devices
    }
}

/**
 * Check if camera hardware is available using modern CameraManager API
 * This is much more reliable than the old Camera.open() approach
 */
fun isCameraAvailable(context: Context): Boolean {
    return try {
        println("🔍 Testing camera availability using CameraManager...")
        
        // First check if camera hardware exists
        val packageManager = context.packageManager
        val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        println("📱 Camera hardware feature available: $hasCamera")
        
        if (!hasCamera) {
            println("❌ No camera hardware detected")
            return false
        }
        
        // Use modern CameraManager API (Android 5.0+) for reliable checking
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager != null) {
                val cameraIds = cameraManager.cameraIdList
                println("📸 Found ${cameraIds.size} camera(s)")
                
                if (cameraIds.isNotEmpty()) {
                    // Check if at least one camera is available
                    for (cameraId in cameraIds) {
                        try {
                            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                            println("✅ Camera $cameraId available and accessible")
                            return true
                        } catch (e: Exception) {
                            println("⚠️ Camera $cameraId not accessible: ${e.message}")
                        }
                    }
                }
            } else {
                println("⚠️ CameraManager not available, falling back to legacy check")
                return hasCamera
            }
        } else {
            // Fallback for older devices - just rely on PackageManager check
            println("⚠️ Using PackageManager check (legacy Android version)")
            return hasCamera
        }
        
        false
    } catch (e: Exception) {
        println("⚠️ Camera availability check failed: ${e.message}")
        // On error, assume camera might be available - let the view handle it
        true
    }
}

/**
 * Try alternative camera initialization for older devices
 * Deprecated: This is no longer needed with modern CameraManager approach
 */
fun tryAlternativeCameraInitialization(context: Context): Boolean {
    // With modern CameraManager, this is now just a redundant check
    return isCameraAvailable(context)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidQrScannerDialog(
    onDismiss: () -> Unit,
    onMatchFound: (ScannerMatch) -> Unit,
    volunteers: List<Volunteer>,
    guests: List<Guest>
) {
    val context = LocalContext.current
    val bleReaderFxScope = rememberCoroutineScope()
    val activity = remember(context) { context.findActivity() }
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var hasPermission by remember { mutableStateOf(false) }
    var cameraAvailable by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showManualInput by remember { mutableStateOf(false) }
    var lastNfcUid by remember { mutableStateOf<String?>(null) }
    var isExternalReaderBusy by remember { mutableStateOf(false) }
    var bluetoothConnectResultReturned by remember { mutableStateOf(false) }
    var externalReaderBtRecoverAttempts by remember { mutableIntStateOf(0) }
    var lastUsbDispatchedUid by remember { mutableStateOf<String?>(null) }
    var lastUsbDispatchElapsedMs by remember { mutableStateOf(0L) }
    var duplicateUid by remember { mutableStateOf<String?>(null) }
    var duplicateUidMatches by remember { mutableStateOf<List<NfcUidMatchOption>>(emptyList()) }
    val usbHardwareGeneration = rememberUsbHardwareGeneration()
    val hasExternalReaderConnected = remember(usbHardwareGeneration, context) {
        ExternalAcsUidReader.isConnected(context)
    }
    val suppressPhoneNfcReaderMode = remember(usbHardwareGeneration, context) {
        ExternalAcsUidReader.shouldSuppressPhoneNfcReaderMode(context)
    }
    val permanentGuests = remember(guests) { guests.filter { !it.isVolunteerBenefit && !it.isTemporaryGuest } }

    val resolveUidMatch: (String) -> Unit = { rawUid ->
        val uid = rawUid.normalizeUid()
        if (uid.isBlank()) {
            errorMessage = context.getString(R.string.nfc_uid_read_failed)
        } else {
            lastNfcUid = uid
            val volunteerMatches = volunteers.filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
            val guestMatches = permanentGuests.filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }

            val allMatches = buildList {
                volunteerMatches.forEach { volunteer ->
                    add(
                        NfcUidMatchOption(
                            match = ScannerMatch.VolunteerMatch(volunteer),
                            title = volunteer.name,
                            subtitle = "${volunteer.lastNameAbbreviation} • ${volunteer.id}",
                            typeLabel = context.getString(R.string.volunteer)
                        )
                    )
                }
                guestMatches.forEach { guest ->
                    add(
                        NfcUidMatchOption(
                            match = ScannerMatch.GuestMatch(guest),
                            title = guest.name,
                            subtitle = if (guest.email.isNotBlank()) guest.email else guest.phoneNumber,
                            typeLabel = context.getString(R.string.permanent_guest_label)
                        )
                    )
                }
            }

            when {
                allMatches.isEmpty() -> {
                    errorMessage = context.getString(R.string.nfc_uid_not_found, uid)
                    bleReaderFxScope.launch {
                        ExternalAcsUidReader.feedbackBleAccessOutcome(context, granted = false)
                    }
                }
                allMatches.size == 1 -> {
                    bleReaderFxScope.launch {
                        ExternalAcsUidReader.feedbackBleAccessOutcome(context, granted = true)
                    }
                    onMatchFound(allMatches.first().match)
                    onDismiss()
                }
                else -> {
                    duplicateUid = uid
                    duplicateUidMatches = allMatches
                }
            }
        }
    }

    val bluetoothConnectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        bluetoothConnectResultReturned = true
    }

    // Check camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (!isGranted) {
            errorMessage = context.getString(R.string.camera_permission_required)
        } else {
            // Check camera availability after permission is granted
            cameraAvailable = isCameraAvailable(context)
            if (!cameraAvailable) {
                println("🔍 Standard camera check failed, trying alternative method...")
                cameraAvailable = tryAlternativeCameraInitialization(context)
                if (!cameraAvailable) {
                    errorMessage = context.getString(R.string.camera_not_available)
                }
            }
        }
    }

    // Same ordering as AddNfcUidDialog: if a USB reader needs host permission, let [readUid] drive that
    // dialog first; do not request camera in parallel (competing system dialogs cancel USB permission).
    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            cameraAvailable = isCameraAvailable(context)
            if (!cameraAvailable) {
                println("🔍 Standard camera check failed, trying alternative method...")
                cameraAvailable = tryAlternativeCameraInitialization(context)
                if (!cameraAvailable) {
                    errorMessage = context.getString(R.string.camera_not_available)
                }
            }
        } else {
            if (Acr122uUsbNfcReader.isConnected(context) &&
                !Acr122uUsbNfcReader.hasUsbPermissionForConnectedReader(context)
            ) {
                val deadline = SystemClock.elapsedRealtime() + USB_HOST_PERMISSION_BEFORE_CAMERA_WAIT_MS
                while (
                    Acr122uUsbNfcReader.isConnected(context) &&
                    !Acr122uUsbNfcReader.hasUsbPermissionForConnectedReader(context) &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    delay(200)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                bluetoothConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // External ACS reader (USB or Bluetooth); same polling model as AddNfcUidDialog.
    // Do not key on usbHardwareGeneration: attach/detach during permission can cancel this effect.
    LaunchedEffect(hasExternalReaderConnected) {
        if (!hasExternalReaderConnected) {
            lastUsbDispatchedUid = null
            lastUsbDispatchElapsedMs = 0L
            isExternalReaderBusy = false
            return@LaunchedEffect
        }
        try {
            readerLoop@ while (ExternalAcsUidReader.isConnected(context)) {
                ensureActive()
                isExternalReaderBusy = true
                when (val outcome = ExternalAcsUidReader.readUid(context)) {
                    is ExternalAcsUidReader.ReadOutcome.Success -> {
                        if (duplicateUid == null) {
                            val norm = outcome.uid.normalizeUid()
                            val now = SystemClock.elapsedRealtime()
                            val sameUidReplayGapMs = 850L
                            if (norm != lastUsbDispatchedUid ||
                                now - lastUsbDispatchElapsedMs >= sameUidReplayGapMs
                            ) {
                                lastUsbDispatchedUid = norm
                                lastUsbDispatchElapsedMs = now
                                resolveUidMatch(outcome.uid)
                            }
                        }
                        delay(280)
                    }
                    is ExternalAcsUidReader.ReadOutcome.Fatal -> {
                        if (outcome.error == ExternalReaderPermissions.BLUETOOTH_CONNECT_DENIED &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ) {
                            val act = activity
                            val permanentlyBlocked = act != null &&
                                bluetoothConnectResultReturned &&
                                !ActivityCompat.shouldShowRequestPermissionRationale(
                                    act,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) &&
                                !ExternalReaderPermissions.hasBluetoothConnect(context)
                            if (permanentlyBlocked || externalReaderBtRecoverAttempts >= 5) {
                                errorMessage = context.getString(R.string.external_reader_bt_blocked_hint)
                                break@readerLoop
                            }
                            externalReaderBtRecoverAttempts++
                            bluetoothConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            errorMessage = context.getString(R.string.nfc_uid_read_failed)
                            delay(700)
                            continue@readerLoop
                        }
                        errorMessage = outcome.error ?: context.getString(R.string.nfc_uid_read_failed)
                        break@readerLoop
                    }
                    is ExternalAcsUidReader.ReadOutcome.Retryable -> {
                        lastUsbDispatchedUid = null
                        delay(280)
                    }
                    ExternalAcsUidReader.ReadOutcome.NoReader -> break@readerLoop
                }
            }
        } finally {
            isExternalReaderBusy = false
        }
    }

    // Phone NFC: only enable reader mode when NFC is on — same as AddNfcUidDialog (not while disabled).
    DisposableEffect(activity, nfcAdapter, volunteers, permanentGuests, suppressPhoneNfcReaderMode) {
        if (activity == null || nfcAdapter == null) {
            onDispose { }
        } else if (!nfcAdapter.isEnabled) {
            if (!hasExternalReaderConnected) {
                errorMessage = context.getString(R.string.nfc_disabled_enable)
            }
            onDispose { }
        } else if (suppressPhoneNfcReaderMode) {
            onDispose { }
        } else {
            val callback = NfcAdapter.ReaderCallback { tag ->
                val uid = tag.id?.toHexUid().orEmpty()
                mainHandler.post {
                    resolveUidMatch(uid)
                }
            }

            try {
                nfcAdapter.enableReaderMode(
                    activity,
                    callback,
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null
                )
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.nfc_reader_error, e.message ?: "")
            }

            onDispose {
                try {
                    nfcAdapter.disableReaderMode(activity)
                } catch (_: Exception) {
                    // Ignore disable errors on teardown.
                }
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.scan_qr_or_nfc_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (hasPermission && cameraAvailable) {
                    QRScannerView(
                        onQRCodeScanned = { qrData ->
                            try {
                                when (qrData.type.lowercase()) {
                                    "nanoid" -> {
                                        // New plain-text NanoID format — look up in both lists; prefer admin if both match
                                        val match = resolveNanoidScannerMatch(volunteers, permanentGuests, qrData.id)
                                        if (match != null) {
                                            onMatchFound(match)
                                            onDismiss()
                                        } else {
                                            errorMessage = context.getString(R.string.invalid_qr_or_nfc_data)
                                        }
                                    }

                                    "volunteer" -> {
                                        val volunteer = volunteers.find { volunteer ->
                                            volunteer.id == qrData.id
                                        } ?: volunteers.find { it.name.equals(qrData.name, ignoreCase = true) }

                                        if (volunteer != null) {
                                            onMatchFound(ScannerMatch.VolunteerMatch(volunteer))
                                            onDismiss()
                                        } else {
                                            errorMessage = context.getString(R.string.volunteer_not_found, qrData.name, qrData.id)
                                        }
                                    }

                                    "guest" -> {
                                        val guest = permanentGuests.find { it.nanoId == qrData.id && qrData.id.isNotBlank() }
                                            ?: permanentGuests.find {
                                                it.name.equals(qrData.name, ignoreCase = true)
                                            } ?: permanentGuests.find {
                                                it.name.contains(qrData.name, ignoreCase = true) ||
                                                    qrData.name.contains(it.name, ignoreCase = true)
                                            }

                                        if (guest != null) {
                                            onMatchFound(ScannerMatch.GuestMatch(guest))
                                            onDismiss()
                                        } else {
                                            errorMessage = context.getString(R.string.guest_not_found, qrData.name)
                                        }
                                    }

                                    else -> {
                                        errorMessage = context.getString(R.string.invalid_qr_or_nfc_data)
                                    }
                                }
                            } catch (e: Exception) {
                                errorMessage = context.getString(R.string.error_processing_qr_code, e.message ?: "")
                            }
                        },
                        onError = { message ->
                            errorMessage = message
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                // Permission denied state (polished)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = context.getString(R.string.camera_permission_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = context.getString(R.string.camera_permission_qr_nfc_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = when {
                                    hasExternalReaderConnected ->
                                        context.getString(R.string.scan_qr_or_nfc_subtitle_usb_only)
                                    nfcAdapter == null ->
                                        context.getString(R.string.nfc_unavailable_no_usb_hint)
                                    !nfcAdapter.isEnabled ->
                                        context.getString(R.string.nfc_disabled_enable)
                                    else ->
                                        context.getString(R.string.scan_qr_or_nfc_subtitle_enabled)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text(context.getString(R.string.grant_permission))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { showManualInput = true }) {
                                Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(context.getString(R.string.enter_id_manually))
                            }
                        }
                    }
                }
                }

                // NFC / USB strip: visible even without camera (same idea as AddNfcUidDialog).
                val showNfcStrip =
                    hasExternalReaderConnected ||
                        (nfcAdapter != null && nfcAdapter.isEnabled)
                if (showNfcStrip) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val nfcStripActive =
                        hasExternalReaderConnected ||
                            (nfcAdapter != null && nfcAdapter.isEnabled)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = if (nfcStripActive) {
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            )
                        } else {
                            null
                        },
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Nfc,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp),
                                tint = if (nfcStripActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = context.getString(R.string.scan_nfc_card_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (nfcStripActive) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = when {
                                        nfcAdapter != null && nfcAdapter.isEnabled && hasExternalReaderConnected ->
                                            context.getString(R.string.scan_qr_or_nfc_subtitle_phone_and_usb)
                                        nfcAdapter != null && nfcAdapter.isEnabled ->
                                            context.getString(R.string.scan_qr_or_nfc_subtitle_enabled)
                                        hasExternalReaderConnected ->
                                            context.getString(R.string.scan_qr_or_nfc_subtitle_usb_only)
                                        nfcAdapter == null ->
                                            context.getString(R.string.nfc_unavailable_no_usb_hint)
                                        else ->
                                            context.getString(R.string.scan_qr_subtitle_nfc_disabled)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                lastNfcUid?.let { uid ->
                                    Text(
                                        text = context.getString(R.string.last_nfc_uid_scanned, uid),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (nfcStripActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                if (Acr122uUsbNfcReader.isConnected(context)) {
                                    Text(
                                        text = if (isExternalReaderBusy) {
                                            context.getString(R.string.usb_reader_waiting_card_short)
                                        } else {
                                            context.getString(R.string.scan_with_usb_reader)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (Acr1255uj1BleNfcReader.isReaderAvailable(context)) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val platformCtx = remember(context) { com.eventmanager.app.platform.createPlatformContext(context) }
                                    BleReaderScannerStatusFooter(
                                        platformContext = platformCtx,
                                        isExternalReaderBusy = isExternalReaderBusy,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        activeColor = MaterialTheme.colorScheme.primary,
                                        idleWarnColor = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                } else if (nfcAdapter == null && !hasExternalReaderConnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = context.getString(R.string.nfc_unavailable_no_usb_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Error message
                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )

    if (duplicateUidMatches.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                duplicateUidMatches = emptyList()
                duplicateUid = null
            },
            title = {
                Text(
                    text = context.getString(R.string.nfc_uid_multiple_matches_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = context.getString(
                            R.string.nfc_uid_multiple_matches_message,
                            duplicateUid ?: "",
                            duplicateUidMatches.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(duplicateUidMatches) { option ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onMatchFound(option.match)
                                    duplicateUidMatches = emptyList()
                                    duplicateUid = null
                                    onDismiss()
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = option.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = option.typeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (option.subtitle.isNotBlank()) {
                                        Text(
                                            text = option.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        duplicateUidMatches = emptyList()
                        duplicateUid = null
                    }
                ) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
    
    // Manual input dialog
    if (showManualInput) {
        ManualVolunteerInputDialog(
            onDismiss = { showManualInput = false },
            onVolunteerFound = { volunteer ->
                onMatchFound(ScannerMatch.VolunteerMatch(volunteer))
                onDismiss()
            },
            volunteers = volunteers
        )
    }
}

@Composable
fun QRScannerView(
    onQRCodeScanned: (QRCodeData) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraInitialized by remember { mutableStateOf(false) }
    var lastScanText by remember { mutableStateOf<String?>(null) }
    var lastScanAtMs by remember { mutableStateOf(0L) }
    val overlayColor = Color.Black.copy(alpha = 0.5f)
    
    // Add a delay for camera initialization on older devices
    LaunchedEffect(Unit) {
        val delay = getCameraInitializationDelay()
        println("⏱️ Camera initialization delay: ${delay}ms for ${if (isNvidiaShieldTablet()) "NVIDIA Shield" else "other device"}")
        kotlinx.coroutines.delay(delay) // Give camera time to initialize
        cameraInitialized = true
    }
    
    val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            val rawText = result.text ?: return
            val now = SystemClock.elapsedRealtime()
            // Ignore immediate duplicate decode events from continuous mode.
            if (rawText == lastScanText && now - lastScanAtMs < 1200L) return
            lastScanText = rawText
            lastScanAtMs = now
            try {
                val qrData = parseQRCodeData(rawText)
                onQRCodeScanned(qrData)
            } catch (e: Exception) {
                onError(context.getString(R.string.invalid_qr_code_format, e.message ?: ""))
            }
        }
        
        override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {
            // Optional: Handle possible result points for UI feedback
        }
    }
    
    // Background camera preview with styled overlay
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
    ) {
        val scanBoxSize = minOf(240.dp, maxWidth * 0.75f, maxHeight * 0.75f)
        val verticalMaskHeight = (maxHeight - scanBoxSize) / 2
        if (cameraInitialized) {
            AndroidView(
            factory = { ctx ->
                try {
                    println("🔍 Initializing DecoratedBarcodeView...")
                    if (isNvidiaShieldTablet()) {
                        println("🛡️ Detected NVIDIA Shield tablet - using enhanced initialization")
                    }
                    
                    val createdBarcodeView = DecoratedBarcodeView(ctx)
                    barcodeView = createdBarcodeView
                    println("✅ DecoratedBarcodeView created")
                    
                    // Set up the barcode view: MixedDecoder (scan type 2) alternates normal and inverted
                    // luminance so QR codes work on dark backgrounds (e.g. white modules on black).
                    val formats = listOf(com.google.zxing.BarcodeFormat.QR_CODE)
                    val decodeHints = mapOf<DecodeHintType, Any>(
                        DecodeHintType.TRY_HARDER to true
                    )
                    val decoderFactory = DefaultDecoderFactory(formats, decodeHints, null, 2)
                    createdBarcodeView.decoderFactory = decoderFactory
                    println("✅ Decoder factory set")
                    
                    // For NVIDIA Shield, try a more conservative approach with retries
                    if (isNvidiaShieldTablet()) {
                        println("🛡️ Applying NVIDIA Shield-specific camera settings...")
                        
                        // Try to resume the camera with error handling and retries
                        var retryCount = 0
                        val maxRetries = 3
                        var lastException: Exception? = null
                        
                        while (retryCount < maxRetries) {
                            try {
                                createdBarcodeView.resume()
                                println("✅ NVIDIA Shield camera resumed successfully (attempt ${retryCount + 1})")
                                
                                // Start continuous decoding
                                createdBarcodeView.decodeContinuous(callback)
                                println("✅ NVIDIA Shield continuous decoding started")
                                break // Success, exit retry loop
                            } catch (e: Exception) {
                                lastException = e
                                retryCount++
                                println("⚠️ NVIDIA Shield camera resume attempt $retryCount failed: ${e.message}")
                                
                                if (retryCount < maxRetries) {
                                    // Wait before retrying
                                    Thread.sleep(300)
                                    try {
                                        createdBarcodeView.pause()
                                        Thread.sleep(200)
                                    } catch (e2: Exception) {
                                        println("⚠️ Error pausing before retry: ${e2.message}")
                                    }
                                }
                            }
                        }
                        
                        // If all retries failed, log but don't crash
                        if (retryCount >= maxRetries && lastException != null) {
                            println("❌ NVIDIA Shield camera failed after $maxRetries attempts: ${lastException.message}")
                            cameraError = "Camera service temporarily unavailable. Try again in a moment."
                        }
                    } else {
                        // Standard initialization for other devices with error handling
                        try {
                            createdBarcodeView.resume()
                            println("✅ Camera resumed successfully")
                            createdBarcodeView.decodeContinuous(callback)
                            println("✅ Continuous decoding started")
                        } catch (e: Exception) {
                            println("⚠️ Camera resume failed: ${e.message}")
                            cameraError = "Camera failed to initialize: ${e.message}"
                        }
                    }
                    
                    createdBarcodeView
                } catch (e: Exception) {
                    println("❌ Error initializing camera: ${e.message}")
                    println("❌ Exception type: ${e.javaClass.simpleName}")
                    e.printStackTrace()
                    cameraError = "Failed to initialize camera: ${e.message}"
                    onError("Failed to initialize camera: ${e.message}")
                    // Return a placeholder view instead of null
                    DecoratedBarcodeView(ctx)
                }
            },
            modifier = Modifier.matchParentSize(),
            update = { _ -> }
        )
        } else {
            // Show loading indicator while camera initializes
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Initializing camera...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Dimmed overlays around scan window
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(verticalMaskHeight)
                .align(Alignment.TopCenter)
                .background(overlayColor)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(verticalMaskHeight)
                .align(Alignment.BottomCenter)
                .background(overlayColor)
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .height(scanBoxSize)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(overlayColor)
            )
            Box(
                modifier = Modifier
                    .width(scanBoxSize)
                    .fillMaxHeight()
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(overlayColor)
            )
        }

        // Scan window border and corners
        Box(
            modifier = Modifier
                .size(scanBoxSize)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(12.dp))
                .border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp))
        )

        // Corner accents
        val cornerLen = 20.dp
        val cornerThickness = 3.dp
        // Top-Left
        Box(modifier = Modifier.size(scanBoxSize).align(Alignment.Center)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(cornerLen, cornerThickness)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(cornerThickness, cornerLen)
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Top-Right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(cornerLen, cornerThickness)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(cornerThickness, cornerLen)
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Bottom-Left
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(cornerLen, cornerThickness)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(cornerThickness, cornerLen)
                    .background(MaterialTheme.colorScheme.primary)
            )
            // Bottom-Right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(cornerLen, cornerThickness)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(cornerThickness, cornerLen)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        // Animated scanning line
        val transition = rememberInfiniteTransition(label = "scanLine")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "scanLineProgress"
        )
        Box(
            modifier = Modifier
                .size(scanBoxSize)
                .align(Alignment.Center)
        ) {
            val lineOffset = scanBoxSize * progress
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .offset(y = lineOffset.coerceAtMost(scanBoxSize - 2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }

    // Show error message if camera failed to initialize
    cameraError?.let { error ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Camera Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    
    // Handle lifecycle events using DisposableEffect
    DisposableEffect(Unit) {
        onDispose {
            // Ensure camera/autofocus thread is released when dialog closes.
            try {
                barcodeView?.pause()
            } catch (_: Exception) {
                // Ignore teardown errors from camera stack.
            } finally {
                barcodeView = null
            }
        }
    }
}

fun parseQRCodeData(qrText: String): QRCodeData {
    val trimmed = qrText.trim()
    println("🔍 Parsing QR code text: '$trimmed'")
    return try {
        val gson = Gson()
        val jsonMap = gson.fromJson(trimmed, Map::class.java) as Map<String, Any>
        println("🔍 Parsed JSON map: $jsonMap")
        val qrData = QRCodeData(
            type = jsonMap["type"] as? String ?: "",
            version = (jsonMap["version"] as? Double)?.toInt() ?: 1,
            id = when (val idValue = jsonMap["id"]) {
                is String -> idValue
                is Number -> idValue.toString()
                else -> ""
            },
            sheetsId = jsonMap["sheetsId"] as? String,
            name = jsonMap["name"] as? String ?: "",
            abbr = jsonMap["abbr"] as? String
        )
        println("🔍 Parsed legacy JSON QR data: $qrData")
        qrData
    } catch (e: Exception) {
        // Not JSON — treat as plain NanoID (new Lightspeed-compatible format)
        println("🔍 Not JSON, treating as plain NanoID: '$trimmed'")
        QRCodeData(
            type = "nanoid",
            version = 1,
            id = trimmed,
            sheetsId = null,
            name = "",
            abbr = null
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualVolunteerInputDialog(
    onDismiss: () -> Unit,
    onVolunteerFound: (Volunteer) -> Unit,
    volunteers: List<Volunteer>
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.manual_volunteer_input),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = context.getString(R.string.enter_volunteer_id_manually),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { 
                        inputText = it
                        errorMessage = null
                    },
                    label = { Text(context.getString(R.string.volunteer_id)) },
                    placeholder = { Text("e.g., 12345") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (volunteers.isNotEmpty()) {
                    Text(
                        text = context.getString(R.string.available_volunteers),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = volunteers.take(10),
                            key = { volunteer -> volunteer.id }
                        ) { volunteer ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    inputText = volunteer.id
                                }
                            ) {
                                Text(
                                    text = "${volunteer.id} - ${volunteer.name}",
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val volunteerId = inputText.trim()
                        if (volunteerId.isBlank()) {
                            errorMessage = context.getString(R.string.please_enter_valid_volunteer_id)
                            return@Button
                        }
                        
                        // Find volunteer by NanoID (String) or by name fallback
                        val volunteer = volunteers.find { it.id == volunteerId }
                            ?: volunteers.find { it.name.equals(volunteerId, ignoreCase = true) }
                        
                        if (volunteer != null) {
                            onVolunteerFound(volunteer)
                            onDismiss()
                        } else {
                            errorMessage = context.getString(R.string.volunteer_id_not_found, volunteerId)
                        }
                    } catch (e: Exception) {
                        errorMessage = context.getString(R.string.invalid_input, e.message ?: "")
                    }
                },
                enabled = inputText.isNotBlank()
            ) {
                Text(context.getString(R.string.find_volunteer))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

private fun ByteArray.toHexUid(): String = joinToString(separator = "") { byte ->
    "%02X".format(byte)
}

private fun String.normalizeUid(): String = NfcUid.normalize(this)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestQRScannerDialog(
    onDismiss: () -> Unit,
    onGuestFound: (Guest) -> Unit,
    guests: List<Guest>
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    var cameraAvailable by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showManualInput by remember { mutableStateOf(false) }
    
    // Check camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (!isGranted) {
            errorMessage = context.getString(R.string.camera_permission_required)
        } else {
            // Check camera availability after permission is granted
            cameraAvailable = isCameraAvailable(context)
            if (!cameraAvailable) {
                println("🔍 Standard camera check failed, trying alternative method...")
                cameraAvailable = tryAlternativeCameraInitialization(context)
                if (!cameraAvailable) {
                    errorMessage = context.getString(R.string.camera_not_available)
                }
            }
        }
    }
    
    // Check permission and camera availability on first load
    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        if (hasPermission) {
            cameraAvailable = isCameraAvailable(context)
            if (!cameraAvailable) {
                println("🔍 Standard camera check failed, trying alternative method...")
                cameraAvailable = tryAlternativeCameraInitialization(context)
                if (!cameraAvailable) {
                    errorMessage = context.getString(R.string.camera_not_available)
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.scan_guest_qr_code),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (hasPermission && cameraAvailable) {
                    QRScannerView(
                        onQRCodeScanned = { qrData ->
                            try {
                                println("🔍 Guest QR Code scanned - Type: '${qrData.type}', ID: '${qrData.id}', Name: '${qrData.name}'")
                                val permanentOnly = guests.filter { !it.isVolunteerBenefit && !it.isTemporaryGuest }

                                val guest: Guest? = when (qrData.type.lowercase()) {
                                    "nanoid" -> {
                                        // New plain-text NanoID format
                                        permanentOnly.find { it.nanoId == qrData.id }
                                    }
                                    "guest" -> {
                                        // Legacy JSON format — try NanoID first, then name
                                        permanentOnly.find { it.nanoId == qrData.id && qrData.id.isNotBlank() }
                                            ?: permanentOnly.find { it.name.equals(qrData.name, ignoreCase = true) }
                                            ?: permanentOnly.find { g ->
                                                g.name.contains(qrData.name, ignoreCase = true) ||
                                                    qrData.name.contains(g.name, ignoreCase = true)
                                            }
                                    }
                                    else -> {
                                        errorMessage = context.getString(R.string.invalid_guest_qr_code)
                                        null
                                    }
                                }

                                if (guest != null) {
                                    println("✅ Found guest: ${guest.name}")
                                    onGuestFound(guest)
                                    onDismiss()
                                } else if (errorMessage == null) {
                                    val label = qrData.id.ifBlank { qrData.name }
                                    println("❌ No guest found for: '$label'")
                                    errorMessage = context.getString(R.string.guest_not_found, label)
                                }
                            } catch (e: Exception) {
                                println("❌ Error processing QR code: ${e.message}")
                                errorMessage = context.getString(R.string.error_processing_qr_code, e.message ?: "")
                            }
                        },
                        onError = { message ->
                            errorMessage = message
                        }
                    )
                } else {
                    // Permission denied state (polished)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = context.getString(R.string.camera_permission_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = context.getString(R.string.camera_permission_required),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                                ) {
                                    Text(context.getString(R.string.grant_permission))
                                }
                            }
                        }
                    }
                }
                
                // Error message
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                
                // Manual input option
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showManualInput = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(context.getString(R.string.enter_guest_name_manually))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
    
    // Manual input dialog
    if (showManualInput) {
        ManualGuestInputDialog(
            onDismiss = { showManualInput = false },
            onGuestFound = { guest ->
                onGuestFound(guest)
                showManualInput = false
                onDismiss()
            },
            guests = guests
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualGuestInputDialog(
    onDismiss: () -> Unit,
    onGuestFound: (Guest) -> Unit,
    guests: List<Guest>
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Filter to only permanent guests (not volunteer benefits)
    val permanentGuests = remember(guests) { guests.filter { !it.isVolunteerBenefit } }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = context.getString(R.string.manual_guest_input),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = context.getString(R.string.enter_guest_name_manually),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { 
                        inputText = it
                        errorMessage = null
                    },
                    label = { Text(context.getString(R.string.guest_name)) },
                    placeholder = { Text("e.g., John Doe") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (permanentGuests.isNotEmpty()) {
                    Text(
                        text = context.getString(R.string.available_guests),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Filter guests based on input
                    val filteredGuests = remember(inputText, permanentGuests) {
                        if (inputText.isBlank()) {
                            permanentGuests.take(10)
                        } else {
                            permanentGuests.filter { 
                                it.name.contains(inputText, ignoreCase = true) 
                            }.take(10)
                        }
                    }
                    
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = filteredGuests,
                            key = { guest -> guest.id }
                        ) { guest ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    inputText = guest.name
                                }
                            ) {
                                Text(
                                    text = guest.name,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val guestName = inputText.trim()
                        if (guestName.isBlank()) {
                            errorMessage = context.getString(R.string.please_enter_guest_name)
                            return@Button
                        }
                        
                        // Find guest by name (case-insensitive)
                        val guest = permanentGuests.find { it.name.equals(guestName, ignoreCase = true) }
                            ?: permanentGuests.find { it.name.contains(guestName, ignoreCase = true) }
                        
                        if (guest != null) {
                            onGuestFound(guest)
                            onDismiss()
                        } else {
                            errorMessage = context.getString(R.string.guest_not_found, guestName)
                        }
                    } catch (e: Exception) {
                        errorMessage = context.getString(R.string.invalid_input, e.message ?: "")
                    }
                },
                enabled = inputText.isNotBlank()
            ) {
                Text(context.getString(R.string.find_guest))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}
