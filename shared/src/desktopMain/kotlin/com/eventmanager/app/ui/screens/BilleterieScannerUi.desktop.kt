package com.eventmanager.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.EntryConfirmControl
import com.eventmanager.app.ui.components.VolunteerFutureEntriesSection
import com.eventmanager.app.ui.components.FirebaseOrgSwitcher
import com.eventmanager.app.ui.components.FirebaseOrgSwitcherPlacement
import com.eventmanager.app.ui.components.ScannerIdentityCard
import com.eventmanager.app.ui.components.temporaryEntryValidatedLabel
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopBilleterieScanningScreen(
    onBack: () -> Unit,
    cameraEnabled: Boolean,
    onToggleCamera: () -> Unit,
    errorMessage: String?,
    nfcConnected: Boolean,
    nfcReaderLabel: String,
    nfcReaderBusy: Boolean,
    cameraPreview: @Composable () -> Unit,
    viewModel: EventManagerViewModel? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.billeterie_button_scanner),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.setup_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DesktopBilleterieWebcamPanel(
                    cameraEnabled = cameraEnabled,
                    onToggleCamera = onToggleCamera,
                    cameraPreview = cameraPreview,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                )
                DesktopBilleterieNfcPanel(
                    connected = nfcConnected,
                    readerLabel = nfcReaderLabel,
                    busy = nfcReaderBusy,
                    viewModel = viewModel,
                    modifier = Modifier.weight(0.9f).fillMaxHeight(),
                )
            }

            errorMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.billeterie_scanner_ready),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DesktopBilleterieWebcamPanel(
    cameraEnabled: Boolean,
    onToggleCamera: () -> Unit,
    cameraPreview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.desktop_billeterie_webcam_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (cameraEnabled) {
                    cameraPreview()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            text = stringResource(Res.string.desktop_qr_point_at_code),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = onToggleCamera,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (cameraEnabled) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (cameraEnabled) {
                        stringResource(Res.string.desktop_billeterie_camera_stop)
                    } else {
                        stringResource(Res.string.desktop_qr_start_webcam)
                    },
                )
            }
        }
    }
}

@Composable
private fun DesktopBilleterieNfcPanel(
    connected: Boolean,
    readerLabel: String,
    busy: Boolean,
    viewModel: EventManagerViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val statusColor = when {
        busy -> MaterialTheme.colorScheme.primary
        connected -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
    }
    val statusBg = statusColor.copy(alpha = 0.12f)

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.desktop_billeterie_nfc_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (viewModel != null) {
                    FirebaseOrgSwitcher(
                        viewModel = viewModel,
                        placement = FirebaseOrgSwitcherPlacement.TopBarTitleEnd,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = statusBg,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                busy -> stringResource(Res.string.desktop_billeterie_nfc_reading)
                                connected -> stringResource(Res.string.desktop_billeterie_nfc_connected)
                                else -> stringResource(Res.string.desktop_billeterie_nfc_disconnected)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        )
                        if (connected && readerLabel.isNotBlank()) {
                            Text(
                                text = readerLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = statusColor,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        Icons.Default.Contactless,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = if (connected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    )
                    Text(
                        text = if (connected) {
                            stringResource(Res.string.usb_reader_waiting_card_short)
                        } else {
                            stringResource(Res.string.usb_reader_not_connected)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun DesktopBilleterieResultScreen(
    result: BilleterieScanResult,
    ticketConfirmed: Boolean,
    jobTypeConfigs: List<JobTypeConfig>,
    offsetHours: Int,
    onConfirmEntry: (Job, Int) -> Unit,
    onScanNext: () -> Unit,
    onCloseToMenu: () -> Unit,
    viewModel: com.eventmanager.app.ui.viewmodel.EventManagerViewModel? = null,
    onValidateTemporaryGuest: (Guest) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val isPendingValidation = result is BilleterieScanResult.TicketsAvailable && !ticketConfirmed
    // Mirror ticketConfirmed: until the single-use entry is validated, Enter must not skip the scan.
    var temporaryGuestValidated by remember(result) {
        mutableStateOf(
            result is BilleterieScanResult.TemporaryGuestFound && result.alreadyValidated,
        )
    }
    val canScanNext = when (result) {
        is BilleterieScanResult.TicketsAvailable -> ticketConfirmed
        is BilleterieScanResult.TemporaryGuestFound -> temporaryGuestValidated
        else -> true
    }

    LaunchedEffect(result, ticketConfirmed, temporaryGuestValidated) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isPendingValidation) {
                    Modifier.background(MaterialTheme.colorScheme.background)
                } else {
                    Modifier
                },
            )
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Enter &&
                    canScanNext
                ) {
                    onScanNext()
                    true
                } else {
                    false
                }
            },
    ) {
        if (isPendingValidation) {
            Column(modifier = Modifier.fillMaxSize()) {
                DesktopBilleterieTicketValidationContent(
                    result = result as BilleterieScanResult.TicketsAvailable,
                    jobTypeConfigs = jobTypeConfigs,
                    offsetHours = offsetHours,
                    viewModel = viewModel,
                    onConfirmEntry = onConfirmEntry,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                DesktopBilleterieResultActionBar(
                    canScanNext = canScanNext,
                    onScanNext = onScanNext,
                    onColoredBackground = false,
                )
            }
        } else {
            val alreadyUsedAtScan = result is BilleterieScanResult.TemporaryGuestFound &&
                result.alreadyValidated
            val isSuccess = when (result) {
                is BilleterieScanResult.FreeEntry,
                is BilleterieScanResult.GuestFound,
                -> true
                is BilleterieScanResult.TicketsAvailable -> ticketConfirmed
                is BilleterieScanResult.TemporaryGuestFound -> !alreadyUsedAtScan
                else -> false
            }
            val verdictColor = when {
                // Amber only on a re-scan after the single-use entry was already spent.
                alreadyUsedAtScan -> TemporaryGuestUsedAmber
                isSuccess -> SuccessGreen
                else -> ErrorRed
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(verdictColor),
            ) {
                DesktopBilleterieFullScreenVerdict(
                    result = result,
                    ticketConfirmed = ticketConfirmed,
                    temporaryGuestValidated = temporaryGuestValidated,
                    viewModel = viewModel,
                    onValidateTemporaryGuest = { guest ->
                        temporaryGuestValidated = true
                        onValidateTemporaryGuest(guest)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                DesktopBilleterieResultActionBar(
                    canScanNext = canScanNext,
                    onScanNext = onScanNext,
                    onColoredBackground = true,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        IconButton(
            onClick = onCloseToMenu,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(CircleShape)
                .background(
                    if (isPendingValidation) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    } else {
                        Color.Black.copy(alpha = 0.25f)
                    },
                ),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(Res.string.cancel),
                tint = if (isPendingValidation) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    Color.White
                },
            )
        }
    }
}

@Composable
private fun DesktopBilleterieFullScreenVerdict(
    result: BilleterieScanResult,
    ticketConfirmed: Boolean,
    temporaryGuestValidated: Boolean = false,
    viewModel: com.eventmanager.app.ui.viewmodel.EventManagerViewModel? = null,
    onValidateTemporaryGuest: (Guest) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val alreadyUsedAtScan = result is BilleterieScanResult.TemporaryGuestFound &&
        result.alreadyValidated
    val justValidatedTemporary = result is BilleterieScanResult.TemporaryGuestFound &&
        temporaryGuestValidated &&
        !alreadyUsedAtScan
    val isSuccess = when (result) {
        is BilleterieScanResult.FreeEntry,
        is BilleterieScanResult.GuestFound,
        -> true
        is BilleterieScanResult.TicketsAvailable -> ticketConfirmed
        is BilleterieScanResult.TemporaryGuestFound -> !alreadyUsedAtScan
        is BilleterieScanResult.NoEntry,
        is BilleterieScanResult.ScanError,
        -> false
    }

    val checkScale = remember(result, ticketConfirmed, temporaryGuestValidated) { Animatable(0f) }
    LaunchedEffect(result, ticketConfirmed, temporaryGuestValidated) {
        checkScale.snapTo(0f)
        checkScale.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 40.dp)
                .padding(bottom = 88.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (alreadyUsedAtScan) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier
                        .size(160.dp)
                        .scale(checkScale.value),
                    tint = Color.White,
                )
            } else if (isSuccess) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier
                        .size(160.dp)
                        .scale(checkScale.value),
                    tint = Color.White,
                )
            } else {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier
                        .size(160.dp)
                        .scale(checkScale.value),
                    tint = Color.White,
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                text = desktopBilleterieStatusTitle(
                    result = result,
                    ticketConfirmed = ticketConfirmed,
                    justValidatedTemporary = justValidatedTemporary,
                ),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            desktopBilleteriePersonName(result)?.let { name ->
                Spacer(Modifier.height(16.dp))
                ScannerIdentityCard(
                    name = name,
                    photoUrl = desktopBilleteriePhotoUrl(result),
                    photoPath = desktopBilleteriePhotoPath(result),
                    orgLabel = {
                        result.scannedFirebaseOrgId()?.let { orgId ->
                            BilleterieScannerScannedOrgLabel(
                                viewModel = viewModel,
                                orgId = orgId,
                                lightOnColoredBackground = true,
                            )
                        }
                    },
                    extraLines = desktopBilleterieIdentityExtras(result),
                    lightOnDark = true,
                    largeName = true,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }

            if (result is BilleterieScanResult.NoEntry) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.billeterie_scanner_no_benefits),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            if (result is BilleterieScanResult.ScanError) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }

            when (result) {
                is BilleterieScanResult.FreeEntry -> {
                    val perkTexts = result.perkTexts.ifEmpty {
                        buildBilleterieScannerPerkTexts(result.benefitStatus.benefits, excludeFreeEntry = true)
                    }
                    if (perkTexts.isNotEmpty()) {
                        Spacer(Modifier.height(32.dp))
                        DesktopBilleterieVerdictPerksCard(perkTexts, muted = false)
                    }
                }
                is BilleterieScanResult.NoEntry -> {
                    val perkTexts = result.perkTexts.ifEmpty {
                        buildBilleterieScannerPerkTexts(result.benefitStatus.benefits, excludeFreeEntry = false)
                    }
                    if (perkTexts.isNotEmpty()) {
                        Spacer(Modifier.height(28.dp))
                        DesktopBilleterieVerdictPerksCard(perkTexts, muted = true)
                    }
                }
                is BilleterieScanResult.GuestFound -> {
                    if (result.guest.invitations > 0) {
                        Spacer(Modifier.height(24.dp))
                        DesktopBilleterieVerdictDetailChip(
                            icon = Icons.Default.People,
                            label = stringResource(
                                Res.string.billeterie_scanner_with_invites,
                                result.guest.invitations,
                            ),
                        )
                    }
                }
                is BilleterieScanResult.TemporaryGuestFound -> {
                    val barDiscount = result.guest.barDiscountPercent.coerceIn(0, 100)
                    val hasNonAccessBenefits = barDiscount > 0
                    val perkTexts = buildList {
                        addAll(result.accesses.map { it.name })
                        if (barDiscount > 0) {
                            add(stringResource(Res.string.bar_discount, barDiscount))
                        }
                    }
                    if (perkTexts.isNotEmpty()) {
                        Spacer(Modifier.height(28.dp))
                        DesktopBilleterieVerdictPerksCard(
                            perkTexts = perkTexts,
                            muted = result.alreadyValidated,
                            title = stringResource(
                                if (hasNonAccessBenefits) Res.string.benefit_details
                                else Res.string.access_details,
                            ),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    if (result.alreadyValidated) {
                        Text(
                            text = temporaryEntryValidatedLabel(result.guest)?.let {
                                stringResource(Res.string.temp_guest_scan_already_used, it)
                            } ?: stringResource(Res.string.temp_guest_entry_validated_short),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                    } else if (!temporaryGuestValidated) {
                        Box(modifier = Modifier.widthIn(max = 480.dp)) {
                            EntryConfirmControl(
                                onConfirm = {
                                    onValidateTemporaryGuest(result.guest)
                                },
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun DesktopBilleterieTicketValidationContent(
    result: BilleterieScanResult.TicketsAvailable,
    jobTypeConfigs: List<JobTypeConfig>,
    offsetHours: Int,
    viewModel: EventManagerViewModel? = null,
    onConfirmEntry: (Job, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val meetingExcluded = remember(result.volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.isVolunteerOrionActive(
            result.volunteerJobs, jobTypeConfigs, System.currentTimeMillis(), offsetHours,
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScannerIdentityCard(
            name = result.volunteer.name,
            photoUrl = result.volunteer.profilePhotoUrl,
            photoPath = result.volunteer.resolvedProfilePhotoPath(),
            orgLabel = {
                BilleterieScannerScannedOrgLabel(
                    viewModel = viewModel,
                    orgId = result.volunteer.firebaseOrgId,
                    lightOnColoredBackground = false,
                )
            },
            extraLines = listOfNotNull(desktopBilleterieRank(result)),
            lightOnDark = false,
            modifier = Modifier.widthIn(max = 520.dp),
        )

        Text(
            text = stringResource(Res.string.billeterie_scanner_validate_ticket),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        VolunteerFutureEntriesSection(
            volunteerJobs = result.volunteerJobs,
            jobTypeConfigs = jobTypeConfigs,
            onConfirmEntry = onConfirmEntry,
            hasActiveFreeEntryBenefit = false,
            meetingNovaBenefitsExcludedForOrion = meetingExcluded,
            hideSecondaryGroupSummary = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DesktopBilleterieVerdictPerksCard(
    perkTexts: List<String>,
    muted: Boolean,
    title: String = stringResource(Res.string.benefit_details),
) {
    Card(
        modifier = Modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = if (muted) 0.12f else 0.15f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            perkTexts.forEach { perk ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (muted) Icons.Default.Info else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White.copy(alpha = if (muted) 0.6f else 0.85f),
                    )
                    Text(
                        text = perk,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopBilleterieVerdictDetailChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.15f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun DesktopBilleterieResultActionBar(
    canScanNext: Boolean,
    onScanNext: () -> Unit,
    onColoredBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (onColoredBackground) {
            Color.Black.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (onColoredBackground) 0.dp else 4.dp,
        shadowElevation = if (onColoredBackground) 0.dp else 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.desktop_billeterie_scan_next_shortcut),
                style = MaterialTheme.typography.bodyMedium,
                color = if (onColoredBackground) {
                    Color.White.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Button(
                onClick = onScanNext,
                enabled = canScanNext,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 220.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (onColoredBackground) {
                    ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.1f),
                        disabledContentColor = Color.White.copy(alpha = 0.4f),
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(Res.string.billeterie_scanner_scan_next),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun desktopBilleterieStatusTitle(
    result: BilleterieScanResult,
    ticketConfirmed: Boolean,
    justValidatedTemporary: Boolean = false,
): String =
    when (result) {
        is BilleterieScanResult.FreeEntry -> stringResource(Res.string.billeterie_scanner_entry_approved)
        is BilleterieScanResult.TicketsAvailable -> if (ticketConfirmed) {
            stringResource(Res.string.billeterie_scanner_entry_validated)
        } else {
            stringResource(Res.string.billeterie_scanner_validate_ticket)
        }
        is BilleterieScanResult.NoEntry -> stringResource(Res.string.billeterie_scanner_no_entry)
        is BilleterieScanResult.GuestFound -> stringResource(Res.string.billeterie_scanner_guest_found)
        is BilleterieScanResult.TemporaryGuestFound -> if (justValidatedTemporary) {
            stringResource(Res.string.billeterie_scanner_entry_validated)
        } else {
            stringResource(Res.string.temp_guest_scan_title)
        }
        is BilleterieScanResult.ScanError -> stringResource(Res.string.billeterie_scanner_error)
    }

private fun desktopBilleteriePersonName(result: BilleterieScanResult): String? = when (result) {
    is BilleterieScanResult.FreeEntry -> result.volunteer.name
    is BilleterieScanResult.TicketsAvailable -> result.volunteer.name
    is BilleterieScanResult.NoEntry -> result.volunteer.name
    is BilleterieScanResult.GuestFound -> result.guest.name
    is BilleterieScanResult.TemporaryGuestFound -> result.guest.name
    is BilleterieScanResult.ScanError -> null
}

private fun desktopBilleteriePhotoUrl(result: BilleterieScanResult): String = when (result) {
    is BilleterieScanResult.FreeEntry -> result.volunteer.profilePhotoUrl
    is BilleterieScanResult.TicketsAvailable -> result.volunteer.profilePhotoUrl
    is BilleterieScanResult.NoEntry -> result.volunteer.profilePhotoUrl
    is BilleterieScanResult.GuestFound -> result.guest.profilePhotoUrl
    is BilleterieScanResult.TemporaryGuestFound -> result.guest.profilePhotoUrl
    is BilleterieScanResult.ScanError -> ""
}

private fun desktopBilleteriePhotoPath(result: BilleterieScanResult): String = when (result) {
    is BilleterieScanResult.FreeEntry -> result.volunteer.resolvedProfilePhotoPath()
    is BilleterieScanResult.TicketsAvailable -> result.volunteer.resolvedProfilePhotoPath()
    is BilleterieScanResult.NoEntry -> result.volunteer.resolvedProfilePhotoPath()
    is BilleterieScanResult.GuestFound -> result.guest.resolvedProfilePhotoPath()
    is BilleterieScanResult.TemporaryGuestFound -> result.guest.resolvedProfilePhotoPath()
    is BilleterieScanResult.ScanError -> ""
}

@Composable
private fun desktopBilleterieIdentityExtras(result: BilleterieScanResult): List<String> {
    val extras = mutableListOf<String>()
    desktopBilleterieRank(result)?.let { extras.add(it) }
    if (result is BilleterieScanResult.GuestFound && result.guest.venueName.isNotBlank()) {
        extras.add(result.guest.venueName)
    }
    if (result is BilleterieScanResult.TemporaryGuestFound) {
        result.guest.temporaryArtistName.takeIf { it.isNotBlank() }?.let { extras.add(it) }
        result.guest.temporaryVenueName.takeIf { it.isNotBlank() }?.let { extras.add(it) }
    }
    return extras
}

@Composable
private fun desktopBilleterieRank(result: BilleterieScanResult): String? {
    val rank = when (result) {
        is BilleterieScanResult.FreeEntry -> result.benefitStatus.rank
        is BilleterieScanResult.TicketsAvailable -> result.benefitStatus.rank
        is BilleterieScanResult.NoEntry -> result.benefitStatus.rank
        else -> null
    } ?: return null
    return when (rank) {
        VolunteerRank.NOVA -> "Nova"
        VolunteerRank.ETOILE -> "Étoile"
        VolunteerRank.GALAXIE -> "Galaxie"
        VolunteerRank.ORION -> "Orion"
        VolunteerRank.VETERAN -> "Vétéran"
        VolunteerRank.SPECIAL -> "Special"
    }
}
