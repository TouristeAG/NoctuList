package com.eventmanager.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.utils.groupFutureEntriesByInvites
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.PlatformBackHandler
import com.eventmanager.app.platform.createCardReaderService
import com.eventmanager.app.platform.UidReadResult
import com.eventmanager.app.platform.hardware.DesktopExternalNfcReader
import com.eventmanager.app.platform.hardware.DesktopWebcamQrScanView
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.NfcUidMatchOption
import com.eventmanager.app.ui.components.ScannerMatch
import com.eventmanager.app.ui.components.rememberTemporaryGuestFeatures
import com.eventmanager.app.ui.components.resolveDesktopScannerPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

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
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val cardReader = remember(platformContext) { createCardReaderService(platformContext) }

    var scanResult by remember { mutableStateOf<BilleterieScanResult?>(null) }
    var ticketConfirmed by remember { mutableStateOf(false) }
    var cameraEnabled by remember { mutableStateOf(false) }
    var isReaderBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var duplicateUidMatches by remember { mutableStateOf<List<NfcUidMatchOption>>(emptyList()) }
    var hasReaderConnected by remember { mutableStateOf(false) }
    var readerLabel by remember { mutableStateOf("") }

    val noMatchMsg = stringResource(Res.string.billeterie_nfc_no_match)
    val temporaryFeatures = rememberTemporaryGuestFeatures(viewModel)

    LaunchedEffect(cardReader, settingsManager) {
        while (isActive) {
            val status = withContext(Dispatchers.IO) {
                DesktopExternalNfcReader.refreshStatus(settingsManager)
            }
            hasReaderConnected = status.usbConnected || status.bleAvailable
            readerLabel = if (hasReaderConnected) {
                status.description
            } else {
                ""
            }
            delay(1500)
        }
    }

    fun resolveScanMatch(match: ScannerMatch) {
        errorMessage = null
        when (match) {
            is ScannerMatch.VolunteerMatch -> {
                val volunteer = match.volunteer
                val volunteerJobs = jobs.filter { it.volunteerId == volunteer.id }
                val benefitStatus = BenefitCalculator.calculateVolunteerBenefitStatus(
                    volunteer = volunteer,
                    jobs = jobs,
                    jobTypeConfigs = jobTypeConfigs,
                    offsetHours = offsetHours,
                )
                val benefit = benefitStatus.benefits
                val configsByName = jobTypeConfigs.associateBy { it.name }
                val meetingExcluded = BenefitCalculator.isVolunteerOrionActive(
                    volunteerJobs, jobTypeConfigs, offsetHours = offsetHours,
                )
                val futureGroups = groupFutureEntriesByInvites(
                    volunteerJobs,
                    configsByName,
                    offsetHours = offsetHours,
                    meetingNovaBenefitsExcludedForOrion = meetingExcluded,
                )
                val hasFutureEntries = futureGroups.any { it.totalRemaining > 0 }

                scanResult = when {
                    benefit.freeEntry -> BilleterieScanResult.FreeEntry(
                        volunteer = volunteer,
                        benefitStatus = benefitStatus,
                    )
                    hasFutureEntries -> BilleterieScanResult.TicketsAvailable(
                        volunteer = volunteer,
                        benefitStatus = benefitStatus,
                        volunteerJobs = volunteerJobs,
                    )
                    else -> BilleterieScanResult.NoEntry(
                        volunteer = volunteer,
                        benefitStatus = benefitStatus,
                    )
                }
                cameraEnabled = false
                ticketConfirmed = false
            }
            is ScannerMatch.GuestMatch -> {
                val guest = match.guest
                when {
                    guest.isVolunteerBenefit -> errorMessage = noMatchMsg
                    guest.isTemporaryGuest && temporaryFeatures.enabled -> {
                        scanResult = BilleterieScanResult.TemporaryGuestFound(
                            guest = guest,
                            accesses = VenueAccessCatalog.resolve(
                                temporaryFeatures.venueAccesses,
                                guest.temporaryAccessIdSet(),
                            ),
                            alreadyValidated = guest.temporaryEntryValidated,
                        )
                        cameraEnabled = false
                    }
                    guest.isTemporaryGuest -> errorMessage = noMatchMsg
                    else -> {
                        scanResult = BilleterieScanResult.GuestFound(guest)
                        cameraEnabled = false
                    }
                }
            }
        }
    }

    fun handlePayload(raw: String) {
        if (scanResult != null) return
        val (match, duplicates) = resolveDesktopScannerPayload(
            raw = raw,
            volunteers = volunteers,
            guests = guests,
            includeTemporaryGuests = temporaryFeatures.enabled,
        )
        when {
            match != null -> resolveScanMatch(match)
            duplicates.isNotEmpty() -> duplicateUidMatches = duplicates
            else -> errorMessage = noMatchMsg
        }
    }

    LaunchedEffect(scanResult, hasReaderConnected) {
        if (scanResult != null || !hasReaderConnected) {
            isReaderBusy = false
            return@LaunchedEffect
        }
        var lastConnectionRefreshAtMs = 0L
        while (isActive && scanResult == null) {
            val nowMs = System.currentTimeMillis()
            val stillConnected = withContext(Dispatchers.IO) {
                if (nowMs - lastConnectionRefreshAtMs >= 2_500L) {
                    cardReader.refreshConnectionState()
                    lastConnectionRefreshAtMs = nowMs
                }
                cardReader.isReaderConnected()
            }
            if (!stillConnected) break
            isReaderBusy = true
            when (val uid = cardReader.readUid()) {
                is UidReadResult.Success -> {
                    isReaderBusy = false
                    handlePayload(uid.uid)
                    break
                }
                is UidReadResult.Fatal -> {
                    errorMessage = uid.error
                    delay(500)
                }
                is UidReadResult.Retryable -> delay(20)
                UidReadResult.NoReader -> {
                    isReaderBusy = false
                    break
                }
            }
        }
        isReaderBusy = false
    }

    fun resetForNextScan() {
        scanResult = null
        ticketConfirmed = false
        cameraEnabled = false
        errorMessage = null
    }

    PlatformBackHandler {
        if (scanResult != null) {
            resetForNextScan()
        } else {
            onBack()
        }
    }

    val currentResult = scanResult
    if (currentResult != null) {
        DesktopBilleterieResultScreen(
            result = currentResult,
            ticketConfirmed = ticketConfirmed,
            jobTypeConfigs = jobTypeConfigs,
            offsetHours = offsetHours,
            onConfirmEntry = { job, invites ->
                onConfirmEntry(job, invites)
                ticketConfirmed = true
            },
            onScanNext = ::resetForNextScan,
            onCloseToMenu = onBack,
            viewModel = viewModel,
            onValidateTemporaryGuest = { guest -> viewModel?.validateTemporaryGuestEntry(guest) },
        )
    } else {
        DesktopBilleterieScanningScreen(
            onBack = onBack,
            viewModel = viewModel,
            cameraEnabled = cameraEnabled,
            onToggleCamera = { cameraEnabled = !cameraEnabled },
            errorMessage = errorMessage,
            nfcConnected = hasReaderConnected,
            nfcReaderLabel = readerLabel,
            nfcReaderBusy = isReaderBusy,
            cameraPreview = {
                DesktopWebcamQrScanView(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    onQrDetected = { payload ->
                        cameraEnabled = false
                        handlePayload(payload)
                    },
                    onError = { msg ->
                        cameraEnabled = false
                        errorMessage = msg
                    },
                    onCancel = { cameraEnabled = false },
                )
            },
        )
    }

    if (duplicateUidMatches.isNotEmpty()) {
        BilleterieDuplicateUidPickerDialog(
            matches = duplicateUidMatches,
            viewModel = viewModel ?: return,
            onSelect = { match ->
                resolveScanMatch(match)
                duplicateUidMatches = emptyList()
            },
            onDismiss = { duplicateUidMatches = emptyList() },
        )
    }
}
