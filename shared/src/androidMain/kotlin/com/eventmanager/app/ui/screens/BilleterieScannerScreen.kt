package com.eventmanager.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eventmanager.app.R
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.utils.groupFutureEntriesByInvites
import com.eventmanager.app.hardware.Acr122uUsbNfcReader
import com.eventmanager.app.hardware.Acr1255uj1BleNfcReader
import com.eventmanager.app.hardware.ExternalAcsUidReader
import com.eventmanager.app.hardware.ExternalReaderPermissions
import com.eventmanager.app.hardware.rememberUsbHardwareGeneration
import com.eventmanager.app.platform.PlatformBackHandler
import com.eventmanager.app.platform.createPlatformContext
import com.eventmanager.app.ui.components.*
import com.eventmanager.app.ui.components.isCameraAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
actual fun BilleterieScannerScreen(
    volunteers: List<Volunteer>,
    guests: List<Guest>,
    jobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    onBack: () -> Unit,
    onConfirmEntry: (Job, Int) -> Unit,
    viewModel: com.eventmanager.app.ui.viewmodel.EventManagerViewModel?,
) {
    val context = LocalContext.current
    val bleReaderFxScope = rememberCoroutineScope()
    val activity = remember(context) { context.billeterieFindActivity() }
    val nfcAdapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val haptic = LocalHapticFeedback.current
    val settingsManager = remember { settingsManagerFor(context) }
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }

    var cameraEnabled by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var cameraAvailable by remember { mutableStateOf(false) }

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

    var scanResult by remember { mutableStateOf<BilleterieScanResult?>(null) }
    var ticketConfirmed by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permanentGuests = remember(guests) {
        guests.filter { !it.isVolunteerBenefit && !it.isTemporaryGuest }
    }
    val temporaryFeatures = rememberTemporaryGuestFeatures(viewModel)
    // On Firebase, temporary guests carry a scannable single-use entry too.
    val scannableTemporaryGuests = remember(guests, temporaryFeatures.enabled) {
        if (temporaryFeatures.enabled) guests.filter { it.isTemporaryGuest } else emptyList()
    }
    val volunteersByNfcUid = remember(volunteers) {
        volunteers.filter { it.nfcCardUid.isNotBlank() }
            .groupBy { it.nfcCardUid.billeterieNormalizeUid() }
    }
    val guestsByNfcUid = remember(permanentGuests) {
        permanentGuests.filter { it.nfcCardUid.isNotBlank() }
            .groupBy { it.nfcCardUid.billeterieNormalizeUid() }
    }

    val processMatch: (ScannerMatch) -> Unit = { match ->
        when (match) {
            is ScannerMatch.VolunteerMatch -> {
                val volunteer = match.volunteer
                val benefitStatus = BenefitCalculator.calculateVolunteerBenefitStatus(
                    volunteer = volunteer,
                    jobs = jobs,
                    jobTypeConfigs = jobTypeConfigs,
                    offsetHours = offsetHours
                )
                val benefit = benefitStatus.benefits
                val volunteerJobs = jobs.filter { it.volunteerId == volunteer.id }
                val configsByName = jobTypeConfigs.associateBy { it.name }
                val meetingExcluded = BenefitCalculator.isVolunteerOrionActive(
                    volunteerJobs, jobTypeConfigs, System.currentTimeMillis(), offsetHours
                )
                val futureGroups = groupFutureEntriesByInvites(
                    volunteerJobs, configsByName, System.currentTimeMillis(),
                    offsetHours, meetingExcluded
                )
                val hasFutureEntries = futureGroups.any { it.totalRemaining > 0 }

                when {
                    benefit.freeEntry -> {
                        scanResult = BilleterieScanResult.FreeEntry(
                            volunteer, benefitStatus,
                        )
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        bleReaderFxScope.launch {
                            ExternalAcsUidReader.feedbackBleAccessOutcome(context, granted = true)
                        }
                    }
                    hasFutureEntries -> {
                        scanResult = BilleterieScanResult.TicketsAvailable(
                            volunteer, benefitStatus, volunteerJobs
                        )
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        bleReaderFxScope.launch {
                            ExternalAcsUidReader.feedbackBleAccessOutcome(context, granted = true)
                        }
                    }
                    else -> {
                        scanResult = BilleterieScanResult.NoEntry(
                            volunteer, benefitStatus,
                        )
                        bleReaderFxScope.launch {
                            ExternalAcsUidReader.feedbackBleAccessOutcome(context, granted = false)
                        }
                    }
                }
                cameraEnabled = false
                ticketConfirmed = false
            }
            is ScannerMatch.GuestMatch -> {
                val guest = match.guest
                scanResult = if (guest.isTemporaryGuest && temporaryFeatures.enabled) {
                    BilleterieScanResult.TemporaryGuestFound(
                        guest = guest,
                        accesses = VenueAccessCatalog.resolve(
                            temporaryFeatures.venueAccesses,
                            guest.temporaryAccessIdSet(),
                        ),
                        alreadyValidated = guest.temporaryEntryValidated,
                    )
                } else {
                    BilleterieScanResult.GuestFound(guest)
                }
                cameraEnabled = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                bleReaderFxScope.launch {
                    ExternalAcsUidReader.feedbackBleAccessOutcome(
                        context,
                        granted = !(guest.isTemporaryGuest && guest.temporaryEntryValidated),
                    )
                }
            }
        }
        errorMessage = null
    }

    val resolveUidMatch: (String) -> Unit = { rawUid ->
        if (scanResult == null) {
            val uid = rawUid.billeterieNormalizeUid()
            if (uid.isBlank()) {
                errorMessage = context.getString(R.string.nfc_uid_read_failed)
            } else {
                val volunteerMatches = volunteersByNfcUid[uid].orEmpty()
                val guestMatches = guestsByNfcUid[uid].orEmpty()
                val allMatches = buildList {
                    volunteerMatches.forEach { v ->
                        add(NfcUidMatchOption(
                            match = ScannerMatch.VolunteerMatch(v),
                            title = v.name,
                            subtitle = v.lastNameAbbreviation,
                            typeLabel = context.getString(R.string.volunteer)
                        ))
                    }
                    guestMatches.forEach { g ->
                        add(NfcUidMatchOption(
                            match = ScannerMatch.GuestMatch(g),
                            title = g.name,
                            subtitle = "",
                            typeLabel = context.getString(R.string.permanent_guest_label)
                        ))
                    }
                }
                when {
                    allMatches.isEmpty() -> {
                        errorMessage = context.getString(R.string.billeterie_nfc_no_match)
                        bleReaderFxScope.launch {
                            ExternalAcsUidReader.feedbackBleAccessOutcome(context, granted = false)
                        }
                    }
                    allMatches.size == 1 ->
                        processMatch(allMatches.first().match)
                    else -> {
                        duplicateUid = uid
                        duplicateUidMatches = allMatches
                    }
                }
            }
        }
    }

    val onQRCodeScanned: (QRCodeData) -> Unit = { qrData ->
        if (scanResult == null) {
            try {
                when (qrData.type.lowercase()) {
                    "nanoid" -> {
                        val volunteer = volunteers.find { it.id == qrData.id }
                        if (volunteer != null) {
                            processMatch(ScannerMatch.VolunteerMatch(volunteer))
                        } else {
                            val guest = permanentGuests.find { it.nanoId == qrData.id }
                                ?: scannableTemporaryGuests.find { it.nanoId == qrData.id }
                            if (guest != null) {
                                processMatch(ScannerMatch.GuestMatch(guest))
                            } else {
                                errorMessage = context.getString(R.string.invalid_qr_or_nfc_data)
                            }
                        }
                    }
                    "volunteer" -> {
                        val volunteer = volunteers.find { it.id == qrData.id }
                            ?: volunteers.find { it.name.equals(qrData.name, ignoreCase = true) }
                        if (volunteer != null) {
                            processMatch(ScannerMatch.VolunteerMatch(volunteer))
                        } else {
                            errorMessage = context.getString(
                                R.string.volunteer_not_found, qrData.name, qrData.id
                            )
                        }
                    }
                    "guest" -> {
                        val guestPool = permanentGuests + scannableTemporaryGuests
                        val guest = guestPool.find {
                            it.nanoId == qrData.id && qrData.id.isNotBlank()
                        } ?: guestPool.find {
                            it.name.equals(qrData.name, ignoreCase = true)
                        } ?: guestPool.find {
                            it.name.contains(qrData.name, ignoreCase = true) ||
                                qrData.name.contains(it.name, ignoreCase = true)
                        }
                        if (guest != null) {
                            processMatch(ScannerMatch.GuestMatch(guest))
                        } else {
                            errorMessage = context.getString(R.string.guest_not_found, qrData.name)
                        }
                    }
                    else -> errorMessage = context.getString(R.string.invalid_qr_or_nfc_data)
                }
            } catch (e: Exception) {
                errorMessage = context.getString(
                    R.string.error_processing_qr_code, e.message ?: ""
                )
            }
        }
    }

    val latestResolveUidMatch by rememberUpdatedState(resolveUidMatch)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (isGranted) {
            cameraAvailable = isCameraAvailable(context)
        }
    }

    val bluetoothConnectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        bluetoothConnectResultReturned = true
    }

    LaunchedEffect(Unit) {
        externalReaderBtRecoverAttempts = 0
        bluetoothConnectResultReturned = false
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            cameraAvailable = isCameraAvailable(context)
        } else {
            if (Acr122uUsbNfcReader.isConnected(context) &&
                !Acr122uUsbNfcReader.hasUsbPermissionForConnectedReader(context)
            ) {
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                while (
                    Acr122uUsbNfcReader.isConnected(context) &&
                    !Acr122uUsbNfcReader.hasUsbPermissionForConnectedReader(context) &&
                    SystemClock.elapsedRealtime() < deadline
                ) { delay(200) }
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
                    context, Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

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
                        val uidStr = outcome.uid
                        if (scanResult == null && duplicateUid == null) {
                            val norm = uidStr.billeterieNormalizeUid()
                            val now = SystemClock.elapsedRealtime()
                            if (norm != lastUsbDispatchedUid ||
                                now - lastUsbDispatchElapsedMs >= 850L
                            ) {
                                lastUsbDispatchedUid = norm
                                lastUsbDispatchElapsedMs = now
                                latestResolveUidMatch(uidStr)
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
                        errorMessage = outcome.error
                            ?: context.getString(R.string.nfc_uid_read_failed)
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

    DisposableEffect(activity, nfcAdapter, volunteers, permanentGuests, suppressPhoneNfcReaderMode) {
        if (activity == null || nfcAdapter == null || !nfcAdapter.isEnabled) {
            onDispose { }
        } else if (suppressPhoneNfcReaderMode) {
            onDispose { }
        } else {
            val callback = NfcAdapter.ReaderCallback { tag ->
                val uid = tag.id?.billeterieToHexUid().orEmpty()
                mainHandler.post { latestResolveUidMatch(uid) }
            }
            try {
                nfcAdapter.enableReaderMode(
                    activity, callback,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null
                )
            } catch (e: Exception) {
                errorMessage = context.getString(R.string.nfc_reader_error, e.message ?: "")
            }
            onDispose {
                try { nfcAdapter.disableReaderMode(activity) } catch (_: Exception) {}
            }
        }
    }

    PlatformBackHandler {
        if (scanResult != null) {
            scanResult = null
            ticketConfirmed = false
            cameraEnabled = false
            errorMessage = null
        } else {
            onBack()
        }
    }

    val currentResult = scanResult
    if (currentResult != null) {
        BilleterieScanResultOverlay(
            result = currentResult,
            ticketConfirmed = ticketConfirmed,
            jobTypeConfigs = jobTypeConfigs,
            offsetHours = offsetHours,
            onConfirmEntry = { job, invites ->
                onConfirmEntry(job, invites)
                ticketConfirmed = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onValidateTemporaryGuest = { guest -> viewModel?.validateTemporaryGuestEntry(guest) },
            onScanNext = {
                scanResult = null
                ticketConfirmed = false
                cameraEnabled = false
                errorMessage = null
            },
            onCloseToMenu = onBack,
            viewModel = viewModel,
        )
    } else {
        BilleterieScannerScanningScreen(
            onBack = onBack,
            viewModel = viewModel,
            cameraEnabled = cameraEnabled,
            onToggleCamera = { cameraEnabled = !cameraEnabled },
            hasCameraPermission = hasPermission,
            cameraAvailable = cameraAvailable,
            onRequestCameraPermission = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            errorMessage = errorMessage,
            showNfcStrip = hasExternalReaderConnected ||
                (nfcAdapter != null && nfcAdapter.isEnabled),
            externalReaderBusy = hasExternalReaderConnected && isExternalReaderBusy,
            cameraPreview = {
                QRScannerView(
                    onQRCodeScanned = onQRCodeScanned,
                    onError = { },
                    modifier = Modifier.fillMaxSize()
                )
            },
            nfcStatusFooter = if (Acr1255uj1BleNfcReader.isReaderAvailable(context)) {
                {
                    BleReaderScannerStatusFooter(
                        platformContext = createPlatformContext(context),
                        isExternalReaderBusy = isExternalReaderBusy,
                        labelColor = Color.White.copy(alpha = 0.55f),
                        activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                        idleWarnColor = Color(0xFFFFB74D),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                null
            },
        )
    }

    if (duplicateUidMatches.isNotEmpty()) {
        BilleterieDuplicateUidPickerDialog(
            matches = duplicateUidMatches,
            viewModel = viewModel ?: return,
            onSelect = { match ->
                processMatch(match)
                duplicateUidMatches = emptyList()
                duplicateUid = null
            },
            onDismiss = {
                duplicateUidMatches = emptyList()
                duplicateUid = null
            }
        )
    }
}

private fun ByteArray.billeterieToHexUid(): String =
    joinToString(separator = "") { byte -> "%02X".format(byte) }

private fun String.billeterieNormalizeUid(): String =
    trim().replace(" ", "").replace(":", "").uppercase()

private tailrec fun Context.billeterieFindActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.billeterieFindActivity()
    else -> null
}
