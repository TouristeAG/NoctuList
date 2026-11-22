package com.eventmanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.hasStoredProfilePhoto
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.data.sync.DateFormatUtils
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.utils.VolunteerActivityManager
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun VolunteerDetailPanel(
    volunteer: Volunteer,
    volunteerJobs: List<Job>,
    venues: List<VenueEntity>,
    onEdit: (Volunteer) -> Unit,
    onAssignNfcUid: (Volunteer, String) -> Unit,
    onDelete: (Volunteer) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    jobTypeConfigs: List<JobTypeConfig>,
    onConfirmFutureEntry: ((Job, Int) -> Unit)?,
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
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showEmailConfirm by remember { mutableStateOf(false) }
    var showNoEmailDialog by remember { mutableStateOf(false) }

    val age = remember(volunteer.dateOfBirth) { desktopCalculateAge(volunteer.dateOfBirth) }
    val sortedJobs = remember(volunteerJobs) { volunteerJobs.sortedByDescending { it.date } }
    val activityVolunteer = remember(volunteer, volunteerJobs) {
        VolunteerActivityManager.calculateActivityFromJobs(volunteer, volunteerJobs)
    }
    val isActive = VolunteerActivityManager.isVolunteerActive(activityVolunteer)
    val activityStatus = VolunteerActivityManager.getActivityStatusText(activityVolunteer)
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val benefitStatus = remember(volunteer.id, volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.calculateVolunteerBenefitStatus(volunteer, volunteerJobs, jobTypeConfigs, offsetHours = offsetHours)
    }
    val meetingExcluded = remember(volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.isVolunteerOrionActive(volunteerJobs, jobTypeConfigs, offsetHours = offsetHours)
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        ProfilePhotoHeaderAvatar(
                            name = volunteer.name,
                            photoUrl = volunteer.profilePhotoUrl,
                            photoPath = volunteer.resolvedProfilePhotoPath(),
                            size = 56.dp,
                            canExport = true,
                            canManage = rememberProfilePhotosUploadEnabled(viewModel),
                            onUpload = { bytes -> viewModel?.uploadProfilePhotoForVolunteer(volunteer, bytes) },
                            onRemove = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
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
                        }
                        Row {
                            IconButton(onClick = { showQrDialog = true }) {
                                Icon(
                                    Icons.Default.QrCode,
                                    contentDescription = stringResource(Res.string.qr_code),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            IconButton(onClick = onClose) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                        )
                        Text(
                            activityStatus,
                            color = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            DesktopSectionCard(stringResource(Res.string.personal_information), Icons.Default.Person) {
                DesktopInfoRow(stringResource(Res.string.full_name), volunteer.name)
                DesktopInfoRow(stringResource(Res.string.abbreviation), volunteer.lastNameAbbreviation)
                DesktopInfoRow(stringResource(Res.string.gender), desktopGenderLabel(volunteer.gender))
                if (volunteer.dateOfBirth.isNotBlank()) {
                    DesktopInfoRow(stringResource(Res.string.birthday), desktopFormatBirthday(volunteer.dateOfBirth, platformContext))
                    age?.let { DesktopInfoRow(stringResource(Res.string.age), stringResource(Res.string.years_old, it)) }
                }
                DesktopInfoRow(stringResource(Res.string.email), volunteer.email)
                DesktopInfoRow(stringResource(Res.string.phone), volunteer.phoneNumber)
                DesktopNanoIdRow("NanoID", volunteer.id)
                NfcUidInfoRow(uid = volunteer.nfcCardUid, isPhone = false)
            }

            DesktopSectionCard(stringResource(Res.string.volunteer_information), Icons.Default.Star) {
                val rankLabel = benefitStatus.rank?.let { desktopRankDisplayName(it) }
                    ?: stringResource(Res.string.no_rank)
                DesktopInfoRow(stringResource(Res.string.current_rank), rankLabel)
                DesktopInfoRow(stringResource(Res.string.status), activityStatus)
                DesktopInfoRow(stringResource(Res.string.total_shifts), volunteerJobs.size.toString())
                activityVolunteer.lastShiftDate?.let {
                    DesktopInfoRow(
                        stringResource(Res.string.last_shift),
                        DateFormatUtils.formatDateTime(it, platformContext)
                    )
                }
            }

            if (onManualAccountAdjust != null && viewModel != null) {
                AccountInfoSection(
                    balance = accountBalance,
                    currencyCode = currencyCode,
                    recentTransfers = recentTransfers,
                    onManualAdjust = onManualAccountAdjust,
                    viewModel = viewModel,
                    allowAdjustment = true,
                    compactAdjust = true
                )
            }

            VolunteerFutureEntriesSection(
                volunteerJobs = volunteerJobs,
                jobTypeConfigs = jobTypeConfigs,
                onConfirmEntry = onConfirmFutureEntry,
                hasActiveFreeEntryBenefit = benefitStatus.benefits.freeEntry,
                meetingNovaBenefitsExcludedForOrion = meetingExcluded
            )

            DesktopShiftHistorySection(sortedJobs, venues, jobTypeConfigs, platformContext)

            VolunteerPortalAccessCard(viewModel = viewModel, volunteer = volunteer)
            com.eventmanager.app.ui.components.settings.certificate.VolunteerCertificateButton(viewModel = viewModel, volunteer = volunteer)

            if (viewModel != null) {
                LocalAdminRightsSection(
                    viewModel = viewModel,
                    isAdmin = volunteer.isAdmin,
                    displayName = volunteer.name,
                    kind = com.eventmanager.app.data.security.LocalAdminTargetKind.VOLUNTEER,
                    targetId = volunteer.id,
                    volunteer = volunteer,
                )
            }

            DesktopSectionCard(stringResource(Res.string.actions), Icons.Default.Settings) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showQrDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.qr_code))
                    }
                    OutlinedButton(onClick = { showNfcDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.add_nfc_card))
                    }
                    val pickPhoto = rememberProfilePhotoPicker { bytes ->
                        viewModel?.uploadProfilePhotoForVolunteer(volunteer, bytes)
                    }
                    ProfilePhotoActionButtons(
                        hasPhoto = volunteer.hasStoredProfilePhoto(),
                        enabled = rememberProfilePhotosUploadEnabled(viewModel),
                        onAddOrChange = pickPhoto,
                        onRemove = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
                        onRequestFromPerson = rememberVolunteerPhotoRequestLauncher(viewModel, volunteer),
                    )
                    OutlinedButton(onClick = { onEdit(volunteer) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.edit_volunteer_button))
                    }
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.delete_volunteer_button))
                    }
                }
            }
        }
    }

    if (showQrDialog) {
        DesktopQrCodeDialog(
            title = stringResource(Res.string.volunteer_qr_code),
            displayName = volunteer.name,
            qrPayload = volunteer.id,
            onDismiss = { showQrDialog = false },
            onRequestSendEmail = { requestSendEmail() }
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
            onSent = { showEmailConfirm = false }
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

    if (showNfcDialog) {
        AddNfcUidDialog(
            platformContext = platformContext,
            onDismiss = { showNfcDialog = false },
            onConfirmUid = { uid ->
                onAssignNfcUid(volunteer, uid)
                showNfcDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        DeleteVolunteerDialog(
            volunteer = volunteer,
            shiftCount = volunteerJobs.size,
            onConfirm = { onDelete(volunteer); showDeleteConfirm = false },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}
