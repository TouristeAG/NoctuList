package com.eventmanager.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eventmanager.app.ui.components.FirebaseOrgSwitcher
import com.eventmanager.app.ui.components.FirebaseOrgSwitcherPlacement
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.NfcUidMatchOption
import com.eventmanager.app.ui.components.OrgColorDot
import com.eventmanager.app.ui.components.ScannerMatch
import com.eventmanager.app.ui.components.ScannerIdentityCard
import com.eventmanager.app.ui.components.EntryConfirmControl
import com.eventmanager.app.ui.components.VolunteerFutureEntriesSection
import com.eventmanager.app.ui.components.temporaryEntryValidatedLabel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

internal val SuccessGreen = Color(0xFF1B5E20)
internal val ErrorRed = Color(0xFFB71C1C)
internal val ScannerDark = Color(0xFF121212)
internal val ScannerCardDark = Color(0xFF1E1E1E)

sealed class BilleterieScanResult {
    data class FreeEntry(
        val volunteer: Volunteer,
        val benefitStatus: VolunteerBenefitStatus,
        val perkTexts: List<String> = emptyList(),
    ) : BilleterieScanResult()

    data class TicketsAvailable(
        val volunteer: Volunteer,
        val benefitStatus: VolunteerBenefitStatus,
        val volunteerJobs: List<Job>,
    ) : BilleterieScanResult()

    data class NoEntry(
        val volunteer: Volunteer,
        val benefitStatus: VolunteerBenefitStatus,
        val perkTexts: List<String> = emptyList(),
    ) : BilleterieScanResult()

    data class GuestFound(val guest: Guest) : BilleterieScanResult()

    /**
     * Firebase-only. A temporary guest holds one entry, so the screen shows their venue accesses
     * and either offers to burn that entry or reports that it is already gone.
     */
    data class TemporaryGuestFound(
        val guest: Guest,
        val accesses: List<VenueAccess>,
        val alreadyValidated: Boolean,
    ) : BilleterieScanResult()

    data class ScanError(val message: String) : BilleterieScanResult()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilleterieScannerScanningScreen(
    onBack: () -> Unit,
    cameraEnabled: Boolean,
    onToggleCamera: () -> Unit,
    hasCameraPermission: Boolean,
    cameraAvailable: Boolean,
    onRequestCameraPermission: () -> Unit,
    errorMessage: String?,
    showNfcStrip: Boolean,
    externalReaderBusy: Boolean = false,
    cameraPreview: @Composable () -> Unit,
    nfcStatusFooter: (@Composable () -> Unit)? = null,
    viewModel: EventManagerViewModel? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ScannerDark),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(Res.string.billeterie_button_scanner),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = if (viewModel != null) Modifier.weight(1f) else Modifier,
                        )
                        if (viewModel != null) {
                            FirebaseOrgSwitcher(
                                viewModel = viewModel,
                                placement = FirebaseOrgSwitcherPlacement.ScannerDarkTopEnd,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.setup_back),
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )

            BilleterieScannerBody(
                cameraEnabled = cameraEnabled,
                onToggleCamera = onToggleCamera,
                hasCameraPermission = hasCameraPermission,
                cameraAvailable = cameraAvailable,
                onRequestCameraPermission = onRequestCameraPermission,
                errorMessage = errorMessage,
                showNfcStrip = showNfcStrip,
                externalReaderBusy = externalReaderBusy,
                cameraPreview = cameraPreview,
                nfcStatusFooter = nfcStatusFooter,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BilleterieScannerBody(
    cameraEnabled: Boolean,
    onToggleCamera: () -> Unit,
    hasCameraPermission: Boolean,
    cameraAvailable: Boolean,
    onRequestCameraPermission: () -> Unit,
    errorMessage: String?,
    showNfcStrip: Boolean,
    externalReaderBusy: Boolean,
    cameraPreview: @Composable () -> Unit,
    nfcStatusFooter: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleCamera,
                ),
        ) {
            when {
                cameraEnabled && hasCameraPermission && cameraAvailable -> {
                    cameraPreview()

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.VideocamOff,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color.White.copy(alpha = 0.7f),
                            )
                            Text(
                                text = stringResource(Res.string.billeterie_scanner_tap_to_pause),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                !cameraEnabled -> BilleterieCameraPausedPlaceholder()
                else -> BilleterieCameraPermissionPlaceholder(onRequestCameraPermission)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showNfcStrip) {
            BilleterieNfcCompactStrip(
                externalReaderBusy = externalReaderBusy,
                nfcStatusFooter = nfcStatusFooter,
            )
        }

        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (errorMessage == null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.billeterie_scanner_ready),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.4f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun BilleterieCameraPausedPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScannerCardDark),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.VideocamOff,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = Color.White.copy(alpha = 0.5f),
            )
            Text(
                text = stringResource(Res.string.billeterie_scanner_tap_to_activate),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun BilleterieCameraPermissionPlaceholder(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScannerCardDark),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = Color.White.copy(alpha = 0.5f),
            )
            Text(
                text = stringResource(Res.string.camera_permission_title),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequestPermission) {
                Text(stringResource(Res.string.grant_permission))
            }
        }
    }
}

@Composable
private fun BilleterieNfcCompactStrip(
    externalReaderBusy: Boolean,
    nfcStatusFooter: (@Composable () -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ScannerCardDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Nfc,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.scan_nfc_card_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                )
                nfcStatusFooter?.let { footer ->
                    Spacer(modifier = Modifier.height(6.dp))
                    footer()
                }
            }
            if (externalReaderBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

fun BilleterieScanResult.scannedFirebaseOrgId(): String? = when (this) {
    is BilleterieScanResult.FreeEntry -> volunteer.firebaseOrgId.takeIf { it.isNotBlank() }
    is BilleterieScanResult.TicketsAvailable -> volunteer.firebaseOrgId.takeIf { it.isNotBlank() }
    is BilleterieScanResult.NoEntry -> volunteer.firebaseOrgId.takeIf { it.isNotBlank() }
    is BilleterieScanResult.GuestFound -> guest.firebaseOrgId.takeIf { it.isNotBlank() }
    is BilleterieScanResult.TemporaryGuestFound -> guest.firebaseOrgId.takeIf { it.isNotBlank() }
    is BilleterieScanResult.ScanError -> null
}

@Composable
fun BilleterieScannerScannedOrgLabel(
    viewModel: EventManagerViewModel?,
    orgId: String,
    lightOnColoredBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null || !viewModel.isFirebaseAllOrgsMode() || orgId.isBlank()) return

    val textColor = if (lightOnColoredBackground) {
        Color.White.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OrgColorDot(orgId = orgId, viewModel = viewModel, size = 10.dp)
        Text(
            text = stringResource(Res.string.billeterie_scanner_scanned_org, orgId),
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
        )
    }
}

@Composable
fun BilleterieScanResultOverlay(
    result: BilleterieScanResult,
    ticketConfirmed: Boolean,
    jobTypeConfigs: List<JobTypeConfig>,
    offsetHours: Int,
    onConfirmEntry: (Job, Int) -> Unit,
    onScanNext: () -> Unit,
    onCloseToMenu: () -> Unit,
    viewModel: EventManagerViewModel? = null,
    onValidateTemporaryGuest: (Guest) -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (result) {
            is BilleterieScanResult.FreeEntry -> BilleterieFreeEntryContent(
                volunteer = result.volunteer,
                benefitStatus = result.benefitStatus,
                perkTexts = result.perkTexts,
                viewModel = viewModel,
                onScanNext = onScanNext,
            )
            is BilleterieScanResult.TicketsAvailable -> BilleterieTicketValidationContent(
                volunteer = result.volunteer,
                benefitStatus = result.benefitStatus,
                volunteerJobs = result.volunteerJobs,
                jobTypeConfigs = jobTypeConfigs,
                offsetHours = offsetHours,
                ticketConfirmed = ticketConfirmed,
                viewModel = viewModel,
                onConfirmEntry = onConfirmEntry,
                onScanNext = onScanNext,
            )
            is BilleterieScanResult.NoEntry -> BilleterieNoEntryContent(
                volunteer = result.volunteer,
                benefitStatus = result.benefitStatus,
                perkTexts = result.perkTexts,
                viewModel = viewModel,
                onScanNext = onScanNext,
            )
            is BilleterieScanResult.GuestFound -> BilleterieGuestFoundContent(
                guest = result.guest,
                viewModel = viewModel,
                onScanNext = onScanNext,
            )
            is BilleterieScanResult.TemporaryGuestFound -> BilleterieTemporaryGuestContent(
                guest = result.guest,
                accesses = result.accesses,
                alreadyValidated = result.alreadyValidated,
                viewModel = viewModel,
                onValidate = { onValidateTemporaryGuest(result.guest) },
                onScanNext = onScanNext,
            )
            is BilleterieScanResult.ScanError -> BilleterieScanErrorContent(
                message = result.message,
                onScanNext = onScanNext,
            )
        }

        IconButton(
            onClick = onCloseToMenu,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f)),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.cancel),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun BilleterieFreeEntryContent(
    volunteer: Volunteer,
    benefitStatus: VolunteerBenefitStatus,
    perkTexts: List<String>,
    viewModel: EventManagerViewModel? = null,
    onScanNext: () -> Unit,
) {
    val displayPerkTexts = perkTexts.ifEmpty {
        buildBilleterieScannerPerkTexts(benefitStatus.benefits, excludeFreeEntry = true)
    }
    val checkScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(80)
        checkScale.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SuccessGreen)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier
                .size(100.dp)
                .scale(checkScale.value),
            tint = Color.White,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.billeterie_scanner_entry_approved),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        ScannerIdentityCard(
            name = volunteer.name,
            photoUrl = volunteer.profilePhotoUrl,
            photoPath = volunteer.resolvedProfilePhotoPath(),
            orgLabel = {
                BilleterieScannerScannedOrgLabel(
                    viewModel = viewModel,
                    orgId = volunteer.firebaseOrgId,
                    lightOnColoredBackground = true,
                )
            },
            extraLines = listOfNotNull(benefitStatus.rank?.let { billeterieScannerRankName(it) }),
            lightOnDark = true,
        )

        if (displayPerkTexts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.benefit_details),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    displayPerkTexts.forEach { perk ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF81C784),
                            )
                            Text(
                                text = perk,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        BilleterieScanNextButton(onScanNext)
    }
}

@Composable
private fun BilleterieTicketValidationContent(
    volunteer: Volunteer,
    benefitStatus: VolunteerBenefitStatus,
    volunteerJobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    offsetHours: Int,
    ticketConfirmed: Boolean,
    viewModel: EventManagerViewModel? = null,
    onConfirmEntry: (Job, Int) -> Unit,
    onScanNext: () -> Unit,
) {
    val meetingExcluded = remember(volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.isVolunteerOrionActive(
            volunteerJobs, jobTypeConfigs, System.currentTimeMillis(), offsetHours,
        )
    }

    if (ticketConfirmed) {
        val checkScale = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            delay(80)
            checkScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SuccessGreen)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .scale(checkScale.value),
                tint = Color.White,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.billeterie_scanner_entry_validated),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            ScannerIdentityCard(
                name = volunteer.name,
                photoUrl = volunteer.profilePhotoUrl,
                photoPath = volunteer.resolvedProfilePhotoPath(),
                orgLabel = {
                    BilleterieScannerScannedOrgLabel(
                        viewModel = viewModel,
                        orgId = volunteer.firebaseOrgId,
                        lightOnColoredBackground = true,
                    )
                },
                extraLines = emptyList(),
                lightOnDark = true,
            )
            Spacer(modifier = Modifier.height(32.dp))
            BilleterieScanNextButton(onScanNext)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            ScannerIdentityCard(
                name = volunteer.name,
                photoUrl = volunteer.profilePhotoUrl,
                photoPath = volunteer.resolvedProfilePhotoPath(),
                orgLabel = {
                    BilleterieScannerScannedOrgLabel(
                        viewModel = viewModel,
                        orgId = volunteer.firebaseOrgId,
                        lightOnColoredBackground = false,
                    )
                },
                extraLines = listOfNotNull(benefitStatus.rank?.let { billeterieScannerRankName(it) }),
                lightOnDark = false,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.ConfirmationNumber,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.billeterie_scanner_validate_ticket),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            VolunteerFutureEntriesSection(
                volunteerJobs = volunteerJobs,
                jobTypeConfigs = jobTypeConfigs,
                onConfirmEntry = onConfirmEntry,
                hasActiveFreeEntryBenefit = false,
                meetingNovaBenefitsExcludedForOrion = meetingExcluded,
                hideSecondaryGroupSummary = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(onClick = onScanNext) {
                Text(
                    text = stringResource(Res.string.billeterie_scanner_scan_next),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BilleterieNoEntryContent(
    volunteer: Volunteer,
    benefitStatus: VolunteerBenefitStatus,
    perkTexts: List<String>,
    viewModel: EventManagerViewModel? = null,
    onScanNext: () -> Unit,
) {
    val displayPerkTexts = perkTexts.ifEmpty {
        buildBilleterieScannerPerkTexts(benefitStatus.benefits, excludeFreeEntry = false)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ErrorRed)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Cancel,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = Color.White,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.billeterie_scanner_no_entry),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        ScannerIdentityCard(
            name = volunteer.name,
            photoUrl = volunteer.profilePhotoUrl,
            photoPath = volunteer.resolvedProfilePhotoPath(),
            orgLabel = {
                BilleterieScannerScannedOrgLabel(
                    viewModel = viewModel,
                    orgId = volunteer.firebaseOrgId,
                    lightOnColoredBackground = true,
                )
            },
            extraLines = listOfNotNull(benefitStatus.rank?.let { billeterieScannerRankName(it) }),
            lightOnDark = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.billeterie_scanner_no_benefits),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

        if (displayPerkTexts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    displayPerkTexts.forEach { perk ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = Color.White.copy(alpha = 0.6f),
                            )
                            Text(
                                text = perk,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        BilleterieScanNextButton(onScanNext)
    }
}

@Composable
private fun BilleterieGuestFoundContent(
    guest: Guest,
    viewModel: EventManagerViewModel? = null,
    onScanNext: () -> Unit,
) {
    val checkScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(80)
        checkScale.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SuccessGreen)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier
                .size(100.dp)
                .scale(checkScale.value),
            tint = Color.White,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.billeterie_scanner_guest_found),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        ScannerIdentityCard(
            name = guest.name,
            photoUrl = guest.profilePhotoUrl,
            photoPath = guest.resolvedProfilePhotoPath(),
            orgLabel = {
                BilleterieScannerScannedOrgLabel(
                    viewModel = viewModel,
                    orgId = guest.firebaseOrgId,
                    lightOnColoredBackground = true,
                )
            },
            extraLines = listOfNotNull(guest.venueName.takeIf { it.isNotBlank() }),
            lightOnDark = true,
        )

        if (guest.invitations > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                    Text(
                        text = stringResource(
                            Res.string.billeterie_scanner_with_invites,
                            guest.invitations,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        BilleterieScanNextButton(onScanNext)
    }
}

/**
 * Temporary guest result. Green while the single entry is still available, amber once it has been
 * used — the accesses stay visible either way, since the door still needs to know where the person
 * is allowed to go.
 */
@Composable
private fun BilleterieTemporaryGuestContent(
    guest: Guest,
    accesses: List<VenueAccess>,
    alreadyValidated: Boolean,
    viewModel: EventManagerViewModel?,
    onValidate: () -> Unit,
    onScanNext: () -> Unit,
) {
    var validated by remember(guest.nanoId) { mutableStateOf(alreadyValidated) }
    val background = if (validated) Color(0xFF8D6E00) else SuccessGreen

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Icon(
            if (validated) Icons.Default.History else Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(88.dp),
            tint = Color.White,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.temp_guest_scan_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        ScannerIdentityCard(
            name = guest.name,
            photoUrl = guest.profilePhotoUrl,
            photoPath = guest.resolvedProfilePhotoPath(),
            orgLabel = {
                BilleterieScannerScannedOrgLabel(
                    viewModel = viewModel,
                    orgId = guest.firebaseOrgId,
                    lightOnColoredBackground = true,
                )
            },
            extraLines = listOfNotNull(
                guest.temporaryArtistName.takeIf { it.isNotBlank() },
                guest.temporaryVenueName.takeIf { it.isNotBlank() },
            ),
            lightOnDark = true,
        )

        val barDiscount = guest.barDiscountPercent.coerceIn(0, 100)
        val hasNonAccessBenefits = barDiscount > 0
        if (accesses.isNotEmpty() || hasNonAccessBenefits) {
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.16f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (hasNonAccessBenefits) Res.string.benefit_details
                            else Res.string.access_details,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    accesses.forEach { access ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = Color.White,
                            )
                            Text(
                                text = access.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    if (barDiscount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.LocalBar,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = Color.White,
                            )
                            Text(
                                text = stringResource(Res.string.bar_discount, barDiscount),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (validated) {
            Text(
                text = temporaryEntryValidatedLabel(guest)?.let {
                    stringResource(Res.string.temp_guest_scan_already_used, it)
                } ?: stringResource(Res.string.temp_guest_entry_validated_short),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.16f)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.temp_guest_entry_single_use_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    EntryConfirmControl(
                        onConfirm = {
                            validated = true
                            onValidate()
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        BilleterieScanNextButton(onScanNext)

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun BilleterieScanErrorContent(
    message: String,
    onScanNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ErrorRed)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.White,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.billeterie_scanner_error),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(32.dp))

        BilleterieScanNextButton(onScanNext)
    }
}

@Composable
private fun BilleterieScanNextButton(onScanNext: () -> Unit) {
    Button(
        onClick = onScanNext,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.22f),
            contentColor = Color.White,
        ),
    ) {
        Icon(
            Icons.Default.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.billeterie_scanner_scan_next),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilleterieDuplicateUidPickerDialog(
    matches: List<NfcUidMatchOption>,
    viewModel: com.eventmanager.app.ui.viewmodel.EventManagerViewModel,
    onSelect: (ScannerMatch) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.nfc_uid_multiple_matches_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { option ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelect(option.match) },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                val orgId = when (val match = option.match) {
                                    is ScannerMatch.VolunteerMatch -> match.volunteer.firebaseOrgId
                                    is ScannerMatch.GuestMatch -> match.guest.firebaseOrgId
                                }
                                if (orgId.isNotBlank()) {
                                    com.eventmanager.app.ui.components.OrgColorDot(
                                        orgId = orgId,
                                        viewModel = viewModel,
                                        size = 10.dp,
                                    )
                                }
                                Text(
                                    option.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                option.typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
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

@Composable
fun buildBilleterieScannerPerkTexts(benefit: Benefit, excludeFreeEntry: Boolean): List<String> =
    listOfNotNull(
        if (!excludeFreeEntry && benefit.freeEntry) stringResource(Res.string.free_entry) else null,
        if (benefit.friendInvitation) {
            if (benefit.inviteCount > 1) {
                stringResource(Res.string.invites_n, benefit.inviteCount)
            } else {
                stringResource(Res.string.friend_invitation)
            }
        } else {
            null
        },
        if (benefit.drinkTokens > 0) stringResource(Res.string.drink_tokens, benefit.drinkTokens) else null,
        if (benefit.barDiscount > 0) stringResource(Res.string.bar_discount, benefit.barDiscount) else null,
        if (benefit.extraordinaryBenefits) stringResource(Res.string.extraordinary_benefits) else null,
    )

@Composable
private fun billeterieScannerRankName(rank: VolunteerRank?): String = when (rank) {
    VolunteerRank.NOVA -> "Nova"
    VolunteerRank.ETOILE -> "Étoile"
    VolunteerRank.GALAXIE -> "Galaxie"
    VolunteerRank.ORION -> "Orion"
    VolunteerRank.VETERAN -> "Vétéran"
    VolunteerRank.SPECIAL -> "Special"
    null -> ""
}
