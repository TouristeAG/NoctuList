package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueAccessCatalog
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.activeBarDiscountPercent
import com.eventmanager.app.data.models.temporaryAccessIdSet
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.email.QrEmailRecipientCode
import com.eventmanager.app.data.remote.hasStoredProfilePhoto
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.utils.DateTimeUtils
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.utils.getVenueDisplayString
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun GuestDetailPanel(
    guest: Guest,
    venues: List<VenueEntity>,
    onEdit: (Guest) -> Unit,
    onAssignNfcUid: (Guest, String) -> Unit,
    onDelete: (Guest) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    readOnly: Boolean,
    accountBalance: Double,
    currencyCode: String,
    recentTransfers: List<AccountTransfer>,
    onManualAccountAdjust: ((Double, String) -> Unit)?,
    viewModel: EventManagerViewModel?
) {
    val guest = rememberLiveGuest(guest, viewModel)
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    var showNfcDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showEmailConfirm by remember { mutableStateOf(false) }
    var showEmailInput by remember { mutableStateOf(false) }
    var showNoEmailStaff by remember { mutableStateOf(false) }
    var emailInputValue by remember { mutableStateOf("") }
    var showTempSendChoice by remember { mutableStateOf(false) }
    var tempSendScope by remember { mutableStateOf(TemporaryGuestSendScope.SinglePerson) }

    val tempFeatures = rememberTemporaryGuestFeatures(viewModel)
    val temporaryEnabled = tempFeatures.enabled && guest.isTemporaryGuest
    val guestAccesses = remember(guest.temporaryAccessIds, tempFeatures.venueAccesses) {
        VenueAccessCatalog.resolve(tempFeatures.venueAccesses, guest.temporaryAccessIdSet())
    }
    val batch = remember(guest.nanoId, temporaryEnabled) {
        if (temporaryEnabled) viewModel?.temporaryGuestBatch(guest).orEmpty() else emptyList()
    }

    val staffSafeQrMode = readOnly
    val temporaryTargetEmail = if (tempSendScope == TemporaryGuestSendScope.WholeBatch &&
        guest.temporaryContactEmail.isNotBlank()
    ) {
        guest.temporaryContactEmail
    } else {
        emailInputValue
    }
    val targetEmail = when {
        temporaryEnabled -> temporaryTargetEmail
        guest.email.isNotBlank() -> guest.email
        else -> emailInputValue
    }

    fun requestSendEmail() {
        showQrDialog = false
        when {
            temporaryEnabled -> {
                if (batch.size > 1) {
                    showTempSendChoice = true
                } else {
                    tempSendScope = TemporaryGuestSendScope.WholeBatch
                    if (guest.temporaryContactEmail.isNotBlank()) {
                        showEmailConfirm = true
                    } else {
                        emailInputValue = ""
                        showEmailInput = true
                    }
                }
            }
            guest.email.isNotBlank() -> showEmailConfirm = true
            readOnly -> showNoEmailStaff = true
            else -> {
                emailInputValue = ""
                showEmailInput = true
            }
        }
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
                        name = guest.name,
                        photoUrl = guest.profilePhotoUrl,
                        photoPath = guest.resolvedProfilePhotoPath(),
                        size = 56.dp,
                        canExport = !readOnly,
                        canManage = !readOnly && (rememberProfilePhotosUploadEnabled(viewModel)) && !guest.isTemporaryGuest,
                        onUpload = { bytes -> viewModel?.uploadProfilePhotoForGuest(guest, bytes) },
                        onRemove = { viewModel?.removeProfilePhotoForGuest(guest) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (guest.isTemporaryGuest) {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(Res.string.temp_guest_chip_label), fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.Default.Event, contentDescription = null, Modifier.size(14.dp)) }
                            )
                        } else {
                            Text(
                                stringResource(Res.string.guest_information),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            guest.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (guest.isTemporaryGuest) {
                            val artist = guest.temporaryArtistName.ifBlank { "-" }
                            Text(artist, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        } else if (guest.lastNameAbbreviation.isNotBlank()) {
                            Text(guest.lastNameAbbreviation, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Row {
                        if (readOnly || !guest.isTemporaryGuest || temporaryEnabled) {
                            IconButton(onClick = { showQrDialog = true }) {
                                Icon(
                                    Icons.Default.QrCode,
                                    contentDescription = stringResource(Res.string.qr_code),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                        }
                    }
                }
            }

            if (guest.isTemporaryGuest) {
                DesktopSectionCard(stringResource(Res.string.guest_information), Icons.Default.Person) {
                    DesktopDetailField(
                        stringResource(Res.string.temp_guest_event_date_label),
                        guest.temporaryEventDate?.let { DateTimeUtils.formatGenevaDateOnly(it) } ?: "-"
                    )
                    DesktopDetailField(stringResource(Res.string.temp_guest_artist_label), guest.temporaryArtistName.ifBlank { "-" })
                    DesktopDetailField(stringResource(Res.string.temp_guest_contact_phone_label), guest.temporaryContactPhone.ifBlank { "-" })
                    if (temporaryEnabled) {
                        DesktopDetailField(
                            stringResource(Res.string.temp_guest_contact_email_label),
                            guest.temporaryContactEmail.ifBlank { "-" },
                        )
                        DesktopDetailField(
                            stringResource(Res.string.temp_guest_venue_label),
                            guest.temporaryVenueName.ifBlank { "-" },
                        )
                        DesktopDetailField(
                            stringResource(Res.string.temp_guest_entry_status_label),
                            temporaryEntryValidatedLabel(guest)?.let {
                                stringResource(Res.string.temp_guest_entry_validated_at, it)
                            } ?: stringResource(Res.string.temp_guest_entry_not_yet),
                        )
                        val tempDiscount = guest.activeBarDiscountPercent(
                            rememberGuestBarDiscountEnabled(viewModel)
                        )
                        if (tempDiscount > 0) {
                            DesktopDetailField(
                                stringResource(Res.string.bar_discount_percent_label),
                                tempDiscount.toString(),
                            )
                        }
                    }
                    DesktopDetailField(stringResource(Res.string.notes), guest.notes.ifBlank { "-" })
                    if (temporaryEnabled && guestAccesses.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        TemporaryGuestAccessBlock(accesses = guestAccesses)
                    }
                }

                if (temporaryEnabled && !guest.temporaryEntryValidated && viewModel != null) {
                    TemporaryGuestEntrySection(
                        guest = guest,
                        onValidate = { viewModel.validateTemporaryGuestEntry(guest) },
                    )
                }

                if (temporaryEnabled && tempFeatures.creditsEnabled && !readOnly &&
                    viewModel != null && onManualAccountAdjust != null
                ) {
                    AccountInfoSection(
                        balance = accountBalance,
                        currencyCode = currencyCode,
                        recentTransfers = recentTransfers,
                        onManualAdjust = onManualAccountAdjust,
                        viewModel = viewModel,
                        allowAdjustment = true,
                        compactAdjust = true,
                    )
                }
            } else {
                DesktopSectionCard(stringResource(Res.string.guest_information), Icons.Default.Person) {
                    DesktopInfoRow(stringResource(Res.string.invitations), guest.invitations.toString())
                    DesktopInfoRow(
                        stringResource(Res.string.venue),
                        getVenueDisplayString(guest.venueName, venues)
                    )
                    if (guest.email.isNotBlank()) DesktopInfoRow(stringResource(Res.string.guest_email), guest.email)
                    if (guest.phoneNumber.isNotBlank()) DesktopInfoRow(stringResource(Res.string.guest_phone_number), guest.phoneNumber)
                    if (guest.notes.isNotBlank()) DesktopInfoRow(stringResource(Res.string.notes), guest.notes)
                    val barDiscountPercent = guest.activeBarDiscountPercent(
                        rememberGuestBarDiscountEnabled(viewModel)
                    )
                    if (barDiscountPercent > 0) {
                        DesktopInfoRow(
                            stringResource(Res.string.bar_discount_percent_label),
                            barDiscountPercent.toString(),
                        )
                    }
                }
            }

            if (!readOnly && !guest.isTemporaryGuest && !guest.isVolunteerBenefit && onManualAccountAdjust != null && viewModel != null) {
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

            if (!readOnly && viewModel != null && !guest.isTemporaryGuest && !guest.isVolunteerBenefit) {
                LocalAdminRightsSection(
                    viewModel = viewModel,
                    isAdmin = guest.isAdmin,
                    displayName = guest.name,
                    kind = com.eventmanager.app.data.security.LocalAdminTargetKind.GUEST,
                    targetId = guest.nanoId,
                    guest = guest,
                )
            }

            NfcUidInfoRow(uid = guest.nfcCardUid, isPhone = false)

            if (!readOnly) {
                DesktopNanoIdRow("NanoID", guest.nanoId)
            }

            if (!readOnly) {
                DesktopSectionCard(stringResource(Res.string.actions), Icons.Default.Settings) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!guest.isTemporaryGuest) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { showNfcDialog = true }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.add_nfc_card))
                                }
                                OutlinedButton(onClick = { showQrDialog = true }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.qr_code))
                                }
                            }
                            val pickPhoto = rememberProfilePhotoPicker { bytes ->
                                viewModel?.uploadProfilePhotoForGuest(guest, bytes)
                            }
                            ProfilePhotoActionButtons(
                                hasPhoto = guest.hasStoredProfilePhoto(),
                                enabled = rememberProfilePhotosUploadEnabled(viewModel),
                                onAddOrChange = pickPhoto,
                                onRemove = { viewModel?.removeProfilePhotoForGuest(guest) },
                                sideBySide = true,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onEdit(guest) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.edit_guest))
                            }
                            OutlinedButton(
                                onClick = { showDeleteConfirm = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.delete_guest))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQrDialog) {
        DesktopQrCodeDialog(
            title = stringResource(Res.string.guest_qr_code),
            displayName = guest.name,
            qrPayload = guest.nanoId,
            onDismiss = { showQrDialog = false },
            onRequestSendEmail = { requestSendEmail() },
            staffSafeMode = staffSafeQrMode
        )
    }

    if (showTempSendChoice) {
        TemporaryGuestSendChoiceDialog(
            artistName = guest.temporaryArtistName.ifBlank { guest.name },
            batchSize = batch.size,
            onDismiss = { showTempSendChoice = false },
            onChoose = { scope ->
                showTempSendChoice = false
                tempSendScope = scope
                val known = scope == TemporaryGuestSendScope.WholeBatch &&
                    guest.temporaryContactEmail.isNotBlank()
                if (known) {
                    showEmailConfirm = true
                } else {
                    emailInputValue = ""
                    showEmailInput = true
                }
            },
        )
    }

    if (showEmailInput) {
        DesktopGuestEmailInputDialog(
            emailValue = emailInputValue,
            onEmailChange = { emailInputValue = it },
            onDismiss = { showEmailInput = false },
            onContinue = {
                showEmailInput = false
                // Only the artist address is worth keeping; a one-off recipient is not.
                if (temporaryEnabled && tempSendScope == TemporaryGuestSendScope.WholeBatch) {
                    viewModel?.setTemporaryGuestBatchContactEmail(guest, emailInputValue)
                }
                showEmailConfirm = true
            }
        )
    }

    if (showEmailConfirm && targetEmail.isNotBlank()) {
        val sendWholeBatch = temporaryEnabled && tempSendScope == TemporaryGuestSendScope.WholeBatch
        DesktopEmailConfirmDialog(
            profile = if (temporaryEnabled) DesktopQrEmailProfile.TempGuest else DesktopQrEmailProfile.Guest,
            recipientEmail = targetEmail,
            recipientName = if (sendWholeBatch) {
                guest.temporaryArtistName.ifBlank { guest.name }
            } else {
                guest.name
            },
            qrPayload = guest.nanoId,
            settingsManager = settingsManager,
            platformContext = platformContext,
            onDismiss = { showEmailConfirm = false },
            onSent = { showEmailConfirm = false },
            codes = if (sendWholeBatch) {
                batch.map { QrEmailRecipientCode(holderName = it.name, qrPayload = it.nanoId) }
            } else {
                emptyList()
            },
        )
    }

    if (showNoEmailStaff) {
        AlertDialog(
            onDismissRequest = { showNoEmailStaff = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Res.string.email_no_email_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(Res.string.email_no_email_guest_staff_message)) },
            confirmButton = {
                TextButton(onClick = { showNoEmailStaff = false }) { Text(stringResource(Res.string.ok)) }
            }
        )
    }

    if (showNfcDialog) {
        AddNfcUidDialog(
            platformContext = platformContext,
            onDismiss = { showNfcDialog = false },
            onConfirmUid = { uid ->
                onAssignNfcUid(guest, uid)
                showNfcDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(Res.string.delete_guest)) },
            text = { Text(guest.name) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(guest)
                    showDeleteConfirm = false
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(Res.string.cancel)) }
            }
        )
    }
}
