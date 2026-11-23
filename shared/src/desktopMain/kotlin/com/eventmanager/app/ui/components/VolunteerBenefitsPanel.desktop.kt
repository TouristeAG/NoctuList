package com.eventmanager.app.ui.components

import com.eventmanager.app.ui.utils.companionsText
import com.eventmanager.app.ui.utils.entryPerkText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.hasStoredProfilePhoto
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.utils.effectiveBenefitFutureEntriesRemaining
import com.eventmanager.app.data.utils.effectiveBenefitFutureEntryInvites
import com.eventmanager.app.data.utils.groupFutureEntriesByInvites
import com.eventmanager.app.data.utils.jobTypeSupportsTrackedFutureEntries
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.util.shiftTimeLabelIfRelevant
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource

private data class BenefitShiftEntry(val job: Job, val rankLabel: String, val remaining: Int, val invites: Int)

@Composable
actual fun VolunteerBenefitsPanel(
    volunteer: Volunteer,
    volunteerBenefitStatus: VolunteerBenefitStatus,
    volunteerJobs: List<Job>,
    venues: List<VenueEntity>,
    jobTypeConfigs: List<JobTypeConfig>,
    onClose: () -> Unit,
    modifier: Modifier,
    onConfirmEntry: ((Job, Int) -> Unit)?,
    onAssignNfcUid: ((Volunteer, String) -> Unit)?,
    readOnly: Boolean,
    accountBalance: Double,
    currencyCode: String,
    recentTransfers: List<AccountTransfer>,
    onManualAccountAdjust: ((Double, String) -> Unit)?,
    viewModel: EventManagerViewModel?
) {
    val volunteer = rememberLiveVolunteer(volunteer, viewModel)
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    var showNfcDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showEmailConfirm by remember { mutableStateOf(false) }
    var showNoEmailDialog by remember { mutableStateOf(false) }

    val benefit = volunteerBenefitStatus.benefits
    val configsByName = remember(jobTypeConfigs) { jobTypeConfigs.associateBy { it.name } }
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val meetingExcluded = remember(volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.isVolunteerOrionActive(volunteerJobs, jobTypeConfigs, offsetHours = offsetHours)
    }
    val benefitForPerks = remember(volunteerBenefitStatus, meetingExcluded) {
        val b = volunteerBenefitStatus.benefits
        if (!meetingExcluded) b
        else {
            val leak = volunteerBenefitStatus.activeBenefits
                .asSequence()
                .filter { it.isNovaMeetingOnlyStylePerk() }
                .sumOf { it.drinkTokens }
            if (leak <= 0) b else b.copy(drinkTokens = (b.drinkTokens - leak).coerceAtLeast(0))
        }
    }
    val jobsVersion = remember(volunteerJobs) {
        volunteerJobs.fold(0L) { acc, j -> acc + j.lastModified + (j.benefitFutureEntriesRemaining ?: 0) }
    }
    val futureEntriesByShift = remember(volunteerJobs, configsByName, offsetHours, jobsVersion, meetingExcluded) {
        val now = System.currentTimeMillis()
        volunteerJobs.mapNotNull { job ->
            val config = configsByName[job.jobTypeName] ?: return@mapNotNull null
            if (!jobTypeSupportsTrackedFutureEntries(job, config)) return@mapNotNull null
            val remaining = effectiveBenefitFutureEntriesRemaining(
                job, config, now, offsetHours, meetingExcluded
            ).coerceAtLeast(0)
            if (remaining <= 0) return@mapNotNull null
            val invites = effectiveBenefitFutureEntryInvites(job, config)
            val rankLabel = when (config.benefitSystemType) {
                BenefitSystemType.MANUAL -> VolunteerRank.SPECIAL.name
                BenefitSystemType.STELLAR, null -> VolunteerRank.NOVA.name
            }
            BenefitShiftEntry(job, rankLabel, remaining, invites)
        }.sortedByDescending { it.job.date }
    }
    val futureGroups = remember(volunteerJobs, configsByName, offsetHours, jobsVersion, meetingExcluded) {
        groupFutureEntriesByInvites(volunteerJobs, configsByName, System.currentTimeMillis(), offsetHours, meetingExcluded)
    }
    val activePerks = remember(volunteerBenefitStatus.activeBenefits, meetingExcluded) {
        volunteerBenefitStatus.activeBenefits
            .filter {
                it.freeEntry || it.friendInvitation || it.inviteCount > 0 ||
                    it.drinkTokens > 0 || it.barDiscount > 0 || it.extraordinaryBenefits
            }
            .filterNot { meetingExcluded && it.isNovaMeetingOnlyStylePerk() }
    }

    fun requestSendEmail() {
        showQrDialog = false
        if (volunteer.email.isNotBlank()) showEmailConfirm = true else showNoEmailDialog = true
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfilePhotoHeaderAvatar(
                        name = volunteer.name,
                        photoUrl = volunteer.profilePhotoUrl,
                        photoPath = volunteer.resolvedProfilePhotoPath(),
                        size = 48.dp,
                        canExport = !readOnly,
                        canManage = !readOnly && (rememberProfilePhotosUploadEnabled(viewModel)),
                        onUpload = { bytes -> viewModel?.uploadProfilePhotoForVolunteer(volunteer, bytes) },
                        onRemove = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            volunteer.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "${volunteer.lastNameAbbreviation} • ${volunteer.email}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    volunteerBenefitStatus.rank?.let { desktopRankDisplayName(it) }
                                        ?: stringResource(Res.string.no_rank)
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, Modifier.size(16.dp)) }
                        )
                    }
                    Row {
                        IconButton(onClick = { showQrDialog = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = stringResource(Res.string.qr_code))
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                        }
                    }
                }
            }

            if (!readOnly) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showNfcDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Nfc, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.add_nfc_card))
                    }
                    OutlinedButton(onClick = { showQrDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.QrCode, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.qr_code))
                    }
                }
                val pickPhoto = rememberProfilePhotoPicker { bytes ->
                    viewModel?.uploadProfilePhotoForVolunteer(volunteer, bytes)
                }
                ProfilePhotoActionButtons(
                    hasPhoto = volunteer.hasStoredProfilePhoto(),
                    enabled = rememberProfilePhotosUploadEnabled(viewModel),
                    onAddOrChange = pickPhoto,
                    onRemove = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
                    sideBySide = true,
                    onRequestFromPerson = rememberVolunteerPhotoRequestLauncher(viewModel, volunteer),
                )
                NfcUidInfoRow(uid = volunteer.nfcCardUid, isPhone = false)
            }

            DesktopSectionCard(stringResource(Res.string.benefit_details), Icons.AutoMirrored.Filled.List) {
                val perkLines = buildList {
                    entryPerkText(benefitForPerks)?.let { add(it) }
                    if (benefitForPerks.drinkTokens > 0) add(stringResource(Res.string.drink_tokens, benefitForPerks.drinkTokens))
                    if (benefitForPerks.barDiscount > 0) add(stringResource(Res.string.bar_discount, benefitForPerks.barDiscount))
                    if (benefitForPerks.extraordinaryBenefits) add(stringResource(Res.string.extraordinary_benefits))
                }
                if (activePerks.size > 1) {
                    Text(
                        stringResource(Res.string.active_benefits_multiple_ranks),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    activePerks.forEach { active ->
                        Text(
                            active.rank?.name ?: stringResource(Res.string.benefit_details),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                perkLines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(line)
                    }
                }
                Text(
                    if (benefit.isActive) stringResource(Res.string.active_benefits) else stringResource(Res.string.inactive),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (benefit.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (futureGroups.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(stringResource(Res.string.future_entries_group_label_solo, futureGroups.sumOf { it.totalRemaining }), fontWeight = FontWeight.SemiBold)
                    futureGroups.forEach { group ->
                        val label = if (group.invites > 0) {
                            stringResource(Res.string.future_entries_remaining_with_invites, group.totalRemaining, companionsText(group.invites))
                        } else {
                            stringResource(Res.string.future_entries_solo, group.totalRemaining)
                        }
                        Text("• $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!readOnly && futureEntriesByShift.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        futureEntriesByShift.forEach { entry ->
                            val invLabel = if (entry.invites > 0) "(${companionsText(entry.invites)})" else "(solo)"
                            val shiftTime = shiftTimeLabelIfRelevant(entry.job, jobTypeConfigs)
                            val descriptor = if (shiftTime != null) "${entry.job.jobTypeName} • $shiftTime" else entry.job.jobTypeName
                            Text(
                                "$descriptor • ${entry.rankLabel}: ${entry.remaining} $invLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            VolunteerFutureEntriesSection(
                volunteerJobs = volunteerJobs,
                jobTypeConfigs = jobTypeConfigs,
                onConfirmEntry = onConfirmEntry,
                hasActiveFreeEntryBenefit = benefit.freeEntry,
                meetingNovaBenefitsExcludedForOrion = meetingExcluded,
                hideSecondaryGroupSummary = readOnly
            )

            if (!readOnly && viewModel != null) {
                AccountInfoSection(
                    balance = accountBalance,
                    currencyCode = currencyCode,
                    recentTransfers = recentTransfers,
                    onManualAdjust = onManualAccountAdjust ?: { _, _ -> },
                    viewModel = viewModel,
                    allowAdjustment = onManualAccountAdjust != null
                )
            }

            if (!readOnly) {
                DesktopShiftHistorySection(
                    jobs = volunteerJobs,
                    venues = venues,
                    jobTypeConfigs = jobTypeConfigs,
                    platformContext = platformContext
                )
            }
        }
    }

    if (showQrDialog) {
        DesktopQrCodeDialog(
            title = stringResource(Res.string.volunteer_qr_code),
            displayName = volunteer.name,
            qrPayload = volunteer.id,
            onDismiss = { showQrDialog = false },
            onRequestSendEmail = { requestSendEmail() },
            staffSafeMode = readOnly,
        )
    }

    if (showEmailConfirm) {
        DesktopEmailConfirmDialog(
            profile = DesktopQrEmailProfile.Volunteer,
            recipientEmail = volunteer.email,
            recipientName = volunteer.name,
            qrPayload = volunteer.id,
            settingsManager = settingsManager,
            platformContext = platformContext,
            onDismiss = { showEmailConfirm = false },
            onSent = { showEmailConfirm = false },
            staffSafeMode = readOnly,
        )
    }

    if (showNoEmailDialog) {
        AlertDialog(
            onDismissRequest = { showNoEmailDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Res.string.email_no_email_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(Res.string.email_no_email_message)) },
            confirmButton = {
                TextButton(onClick = { showNoEmailDialog = false }) { Text(stringResource(Res.string.ok)) }
            }
        )
    }

    if (showNfcDialog && onAssignNfcUid != null) {
        AddNfcUidDialog(
            platformContext = platformContext,
            onDismiss = { showNfcDialog = false },
            onConfirmUid = { uid ->
                onAssignNfcUid(volunteer, uid)
                showNfcDialog = false
            }
        )
    }
}
