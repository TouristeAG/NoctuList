package com.eventmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.hasStoredProfilePhoto
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.utils.QRCodeUtils
import com.eventmanager.app.R
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.GmailAuthService
import com.eventmanager.app.data.sync.GmailQrAttachment
import com.eventmanager.app.data.sync.GmailSendService
import com.eventmanager.app.email.MimeEmailBuilder
import com.eventmanager.app.email.QrEmailCode
import com.eventmanager.app.email.QrEmailHtmlBuilder
import com.eventmanager.app.email.QrEmailHtmlOptions
import com.eventmanager.app.email.QrEmailTheme
import com.eventmanager.app.utils.DigitalWalletPassGenerator
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes

@Composable
private fun getStringResource(@StringRes stringRes: Int, vararg args: Any): String {
    val context = LocalContext.current
    return context.getString(stringRes, *args)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun GuestDetailPanel(
    guest: Guest,
    venues: List<VenueEntity>,
    onEdit: (Guest) -> Unit,
    onAssignNfcUid: (Guest, String) -> Unit,
    onDelete: (Guest) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
    /** When true (e.g. Billeterie guest list), hide identifiers and all mutation actions. */
    readOnly: Boolean,
    accountBalance: Double,
    currencyCode: String,
    recentTransfers: List<AccountTransfer>,
    onManualAccountAdjust: ((Double, String) -> Unit)?,
    viewModel: EventManagerViewModel?
) {
    val guest = rememberLiveGuest(guest, viewModel)
    val context = LocalContext.current
    val isPhone = !isTablet()
    val responsivePadding = if (isPhone) getPhonePortraitPadding() else getResponsivePadding()
    val seasonalFunEnabled = remember { settingsManagerFor(context).isSeasonalFunEnabled() }
    val leonardoEasterEggEnabled = remember(guest, seasonalFunEnabled) {
        seasonalFunEnabled && isLeonardoMondadaProfile(
            firstName = guest.name,
            lastNameOrAbbreviation = guest.lastNameAbbreviation
        )
    }
    val glowTransition = rememberInfiniteTransition(label = "guest-glow")
    val glow by glowTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1150),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "guest-glow-alpha"
    )
    var showQrDialog by remember { mutableStateOf(false) }
    var showNfcDialog by remember { mutableStateOf(false) }

    val temporaryGuestFeatures = rememberTemporaryGuestFeatures(viewModel)
    val guestAccesses = remember(temporaryGuestFeatures.venueAccesses, guest) {
        VenueAccessCatalog.resolve(temporaryGuestFeatures.venueAccesses, guest.temporaryAccessIdSet())
    }

    ProfileEasterEggHost(
        enabled = leonardoEasterEggEnabled,
        modifier = modifier.fillMaxSize(),
    ) {
        // Background
        Card(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (leonardoEasterEggEnabled) {
                        shadowElevation = 18.dp.toPx() * glow
                    }
                }
                .then(
                    if (leonardoEasterEggEnabled) {
                        Modifier.border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    Color(0xFFFFC857).copy(alpha = 0.75f),
                                    Color(0xFF7F5AF0).copy(alpha = 0.75f),
                                    Color(0xFF2CB67D).copy(alpha = 0.75f)
                                )
                            ),
                            shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp)
                        )
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Scrollable content
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(if (isPhone) 8.dp else 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        top = responsivePadding,
                        start = responsivePadding,
                        end = responsivePadding,
                        bottom = responsivePadding
                    )
                ) {
                    // Guest Information Section
                    item {
                        GuestInformationSection(
                            guest = guest,
                            venues = venues,
                            isPhone = isPhone,
                            onClose = onClose,
                            easterEggEnabled = leonardoEasterEggEnabled,
                            barDiscountPercent = guest.activeBarDiscountPercent(
                                rememberGuestBarDiscountEnabled(viewModel)
                            ),
                            readOnly = readOnly,
                            canManagePhoto = !readOnly && (rememberProfilePhotosUploadEnabled(viewModel)) && !guest.isTemporaryGuest,
                            canExportPhoto = !readOnly,
                            onUploadPhoto = { bytes -> viewModel?.uploadProfilePhotoForGuest(guest, bytes) },
                            onRemovePhoto = { viewModel?.removeProfilePhotoForGuest(guest) },
                            onShowQr = if (readOnly || guest.isTemporaryGuest) {
                                { showQrDialog = true }
                            } else {
                                null
                            },
                            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
                            temporaryAccesses = guestAccesses,
                        )
                    }

                    // Single-use door validation, available to both Billeterie and Admin.
                    if (guest.isTemporaryGuest &&
                        temporaryGuestFeatures.enabled &&
                        !guest.temporaryEntryValidated &&
                        viewModel != null
                    ) {
                        item {
                            TemporaryGuestEntrySection(
                                guest = guest,
                                onValidate = { viewModel.validateTemporaryGuestEntry(guest) },
                            )
                        }
                    }

                    if (!readOnly &&
                        guest.isTemporaryGuest &&
                        temporaryGuestFeatures.creditsEnabled &&
                        onManualAccountAdjust != null &&
                        viewModel != null
                    ) {
                        item {
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
                    }

                    if (!readOnly && !guest.isTemporaryGuest && !guest.isVolunteerBenefit && onManualAccountAdjust != null && viewModel != null) {
                        item {
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
                    }

                    if (!readOnly && viewModel != null && !guest.isTemporaryGuest && !guest.isVolunteerBenefit) {
                        item {
                            LocalAdminRightsSection(
                                viewModel = viewModel,
                                isAdmin = guest.isAdmin,
                                displayName = guest.name,
                                kind = com.eventmanager.app.data.security.LocalAdminTargetKind.GUEST,
                                targetId = guest.nanoId,
                                guest = guest,
                            )
                        }
                    }
                    
                    // Action Buttons Section
                    if (!readOnly) {
                        item {
                            ActionButtonsSection(
                                guest = guest,
                                onEdit = onEdit,
                                onAddNfcCard = { showNfcDialog = true },
                                onDelete = onDelete,
                                onShowQr = { showQrDialog = true },
                                isPhone = isPhone,
                                canManagePhoto = rememberProfilePhotosUploadEnabled(viewModel) && !guest.isTemporaryGuest,
                                onUploadPhoto = { bytes -> viewModel?.uploadProfilePhotoForGuest(guest, bytes) },
                                onRemovePhoto = { viewModel?.removeProfilePhotoForGuest(guest) },
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Email confirmation dialog state
    var showEmailConfirmDialog by remember { mutableStateOf(false) }
    var showEmailInputDialog by remember { mutableStateOf(false) }
    var emailInputValue by remember { mutableStateOf("") }
    var showGuestNoEmailStaffDialog by remember { mutableStateOf(false) }
    var showTempSendChoiceDialog by remember { mutableStateOf(false) }
    var tempSendScope by remember { mutableStateOf(TemporaryGuestSendScope.SinglePerson) }

    val staffSafeGuestQrMode = readOnly
    val temporaryEnabled = temporaryGuestFeatures.enabled && guest.isTemporaryGuest
    val temporaryBatch = remember(guest.nanoId, temporaryEnabled) {
        if (temporaryEnabled) viewModel?.temporaryGuestBatch(guest).orEmpty() else emptyList()
    }

    /**
     * Routes the "send by mail" action. Temporary guests first pick between the whole artist
     * guest list and a single person, then reuse the regular address prompt.
     */
    fun requestGuestEmailSend() {
        showQrDialog = false
        when {
            temporaryEnabled && temporaryBatch.size > 1 -> showTempSendChoiceDialog = true
            temporaryEnabled -> {
                tempSendScope = TemporaryGuestSendScope.WholeBatch
                if (guest.temporaryContactEmail.isNotBlank()) {
                    showEmailConfirmDialog = true
                } else {
                    emailInputValue = ""
                    showEmailInputDialog = true
                }
            }
            guest.email.isNotBlank() -> showEmailConfirmDialog = true
            readOnly -> showGuestNoEmailStaffDialog = true
            else -> {
                emailInputValue = ""
                showEmailInputDialog = true
            }
        }
    }

    if (showQrDialog) {
        val tabletMaxWidth = getTabletConstrainedDialogMaxWidth()
        val tabletQrSize = getTabletConstrainedQRCodeSize()
        val tabletDialogPadding = getTabletConstrainedDialogPadding()
        val isTabletDevice = isTablet()
        
        Dialog(
            onDismissRequest = { showQrDialog = false },
            properties = phoneFractionDialogProperties(),
        ) {
            DialogFractionSizer(profile = FractionalDialogProfile.Card) { maxDialogWidth, maxDialogHeight ->
            Card(
                modifier = Modifier
                    .widthIn(max = if (isTabletDevice) tabletMaxWidth else maxDialogWidth)
                    .heightIn(max = maxDialogHeight)
                    .padding(tabletDialogPadding),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title
                    Text(
                        text = getStringResource(R.string.guest_qr_code),
                        style = if (isPhone) getPhonePortraitTypography() else getTabletConstrainedTitleTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    val payload = remember(guest) { guest.nanoId }
                    val qrImage = remember(payload) { QRCodeUtils.generateQrImageBitmap(payload, 1024) }
                    val qrContext = LocalContext.current
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (staffSafeGuestQrMode) {
                            StaffObfuscatedQrPreview(
                                qrPayload = payload,
                                isPhone = isPhone,
                                isTabletDevice = isTabletDevice,
                                tabletQrSize = tabletQrSize
                            )
                            Spacer(modifier = Modifier.height(if (isPhone) 12.dp else 16.dp))
                            Button(
                                onClick = { requestGuestEmailSend() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (isTabletDevice) 48.dp else 64.dp)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(qrContext.getString(R.string.email_send_api))
                            }
                        } else if (qrImage != null) {
                            Image(
                                bitmap = qrImage,
                                contentDescription = getStringResource(R.string.guest_qr_code),
                                modifier = Modifier
                                    .then(
                                        if (isTabletDevice) {
                                            Modifier.size(tabletQrSize)
                                        } else {
                                            Modifier.fillMaxWidth().aspectRatio(1f)
                                        }
                                    )
                                    .clip(RoundedCornerShape(if (isPhone) 8.dp else 12.dp))
                                    .background(Color.White)
                            )
                            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
                            Text(
                                text = guest.name,
                                style = if (isPhone) getPhonePortraitBodyTypography() else getTabletConstrainedBodyTypography(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(if (isTabletDevice) 8.dp else 12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        qrImage.let { bitmap ->
                                            runCatching {
                                                sharePngImage(
                                                    context = qrContext,
                                                    image = bitmap,
                                                    fileName = "qr_code_guest_${guest.id}.png",
                                                    chooserTitle = qrContext.getString(R.string.share_qr_code),
                                                    subject = qrContext.getString(R.string.qr_code_subject_guest, guest.name),
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (isTabletDevice) 48.dp else 64.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(getStringResource(R.string.share))
                                }
                                OutlinedButton(
                                    onClick = { requestGuestEmailSend() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (isTabletDevice) 48.dp else 64.dp)
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(getStringResource(R.string.send_by_mail))
                                }
                            }
                        } else {
                            Text(
                                text = getStringResource(R.string.failed_to_generate_qr_code),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    
                    // Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showQrDialog = false }) {
                            Text(getStringResource(R.string.close))
                        }
                    }
                }
            }
        }
        }
    }
    
    // Whole artist guest list, or just this person?
    if (showTempSendChoiceDialog) {
        TemporaryGuestSendChoiceDialog(
            artistName = guest.temporaryArtistName.ifBlank { guest.name },
            batchSize = temporaryBatch.size,
            onDismiss = { showTempSendChoiceDialog = false },
            onChoose = { scope ->
                showTempSendChoiceDialog = false
                tempSendScope = scope
                if (scope == TemporaryGuestSendScope.WholeBatch && guest.temporaryContactEmail.isNotBlank()) {
                    showEmailConfirmDialog = true
                } else {
                    emailInputValue = ""
                    showEmailInputDialog = true
                }
            },
        )
    }

    // Email Input Dialog (for guests without email)
    if (showEmailInputDialog) {
        AlertDialog(
            onDismissRequest = { showEmailInputDialog = false },
            icon = {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = getStringResource(R.string.guest_email_input_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = getStringResource(R.string.guest_email_input_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    OutlinedTextField(
                        value = emailInputValue,
                        onValueChange = { emailInputValue = it },
                        label = { Text(getStringResource(R.string.guest_email)) },
                        placeholder = { Text("example@email.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (emailInputValue.isNotBlank() && emailInputValue.contains("@")) {
                            showEmailInputDialog = false
                            // Only the artist address is worth keeping; a one-off recipient is not.
                            if (temporaryEnabled && tempSendScope == TemporaryGuestSendScope.WholeBatch) {
                                viewModel?.setTemporaryGuestBatchContactEmail(guest, emailInputValue)
                            }
                            showEmailConfirmDialog = true
                        }
                    },
                    enabled = emailInputValue.isNotBlank() && emailInputValue.contains("@")
                ) {
                    Text(getStringResource(R.string.continue_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailInputDialog = false }) {
                    Text(getStringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Email Confirmation Dialog
    if (showEmailConfirmDialog) {
        val emailContext = LocalContext.current
        val settingsManager = remember { settingsManagerFor(emailContext) }
        val gmailAuthService = remember { GmailAuthService(emailContext) }
        val gmailSendService = remember { GmailSendService(emailContext) }
        val coroutineScope = rememberCoroutineScope()
        val isGmailAuthenticated = remember { gmailAuthService.isAccountSelected() }
        
        val sendWholeBatch = temporaryEnabled && tempSendScope == TemporaryGuestSendScope.WholeBatch
        // Use either the guest's email or the manually entered email
        val targetEmail = when {
            sendWholeBatch && guest.temporaryContactEmail.isNotBlank() -> guest.temporaryContactEmail
            temporaryEnabled -> emailInputValue
            guest.email.isNotBlank() -> guest.email
            else -> emailInputValue
        }
        val batchCodes = if (sendWholeBatch) temporaryBatch else listOf(guest)
        
        // Holder for authLauncher - needed to break circular dependency
        val authLauncherHolder = remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<Intent>?>(null) }
        
        // State for showing loading during email send
        var isSendingEmail by remember { mutableStateOf(false) }
        
        // API email send function - Multipart/related via Gmail API
        suspend fun sendGuestEmailViaApi(
            emailContext: android.content.Context,
            settingsManager: SettingsManager,
            gmailAuthService: GmailAuthService,
            gmailSendService: GmailSendService,
            guest: Guest,
            targetEmail: String,
            onSuccess: () -> Unit
        ) {
            try {
                // Get Gmail service
                val gmailService = gmailAuthService.createGmailService()
                if (gmailService == null) {
                    Toast.makeText(
                        emailContext,
                        emailContext.getString(R.string.email_api_not_authenticated),
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
                
                // Get email settings — temporary guests have their own artist-guest-list template
                val subject = if (temporaryEnabled) {
                    settingsManager.getTemporaryGuestEmailSubject().ifEmpty {
                        emailContext.getString(R.string.temp_guest_email_subject_default)
                    }
                } else {
                    settingsManager.getGuestEmailSubject().ifEmpty {
                        emailContext.getString(R.string.guest_email_subject_default)
                    }
                }
                val contentBefore = if (temporaryEnabled) {
                    settingsManager.getTemporaryGuestEmailContentBefore().ifEmpty {
                        emailContext.getString(R.string.temp_guest_email_content_before_default)
                    }
                } else {
                    settingsManager.getGuestEmailContentBefore().ifEmpty {
                        emailContext.getString(R.string.guest_email_content_before_default)
                    }
                }
                val includeQr = if (temporaryEnabled) {
                    settingsManager.isTemporaryGuestEmailIncludeQrEnabled()
                } else {
                    settingsManager.isGuestEmailIncludeQrEnabled()
                }
                val contentAfter = if (temporaryEnabled) {
                    settingsManager.getTemporaryGuestEmailContentAfter().ifEmpty {
                        emailContext.getString(R.string.temp_guest_email_content_after_default)
                    }
                } else {
                    settingsManager.getGuestEmailContentAfter().ifEmpty {
                        emailContext.getString(R.string.guest_email_content_after_default)
                    }
                }
                val signature = settingsManager.getGuestEmailSignature().ifEmpty { 
                    emailContext.getString(R.string.email_signature_default) 
                }
                // A grouped artist mail covers several people, so a single-holder pass is meaningless.
                val includeDigitalWalletPass =
                    settingsManager.isEmailIncludeDigitalWalletPassEnabled() && !temporaryEnabled
                val includeLogo = settingsManager.isEmailIncludeLogoEnabled()
                val logoUriString = settingsManager.getEmailLogoUri()
                val associationName = settingsManager.getEmailAssociationName()

                // One PNG per code: a single-guest mail keeps the historic `qrcode` Content-ID.
                val qrAttachments = if (includeQr) {
                    batchCodes.mapIndexedNotNull { index, holder ->
                        val bitmap = QRCodeUtils.generateQrImageBitmap(holder.nanoId, 512)
                            ?: return@mapIndexedNotNull null
                        val fileName = MimeEmailBuilder.qrFileNameFor(holder.name, index)
                        val file = File(emailContext.cacheDir, "qr_${holder.nanoId}.png")
                        FileOutputStream(file).use { stream ->
                            bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
                        }
                        GmailQrAttachment(
                            file = file,
                            contentId = if (batchCodes.size == 1) "qrcode" else "qrcode-$index",
                            fileName = if (batchCodes.size == 1) "qr_code.png" else fileName,
                        )
                    }
                } else emptyList()

                val digitalWalletPassFile = if (includeDigitalWalletPass) {
                    DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${guest.nanoId}-${System.currentTimeMillis()}",
                        holderName = guest.name,
                        qrPayload = guest.nanoId,
                        logoUriString = logoUriString,
                        associationName = associationName
                    ) ?: DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${guest.nanoId}-${System.currentTimeMillis()}",
                        holderName = guest.name,
                        qrPayload = guest.nanoId,
                        logoUriString = null,
                        associationName = associationName
                    )
                } else {
                    null
                }
                
                // Save logo file
                var logoFile: File? = null
                if (includeLogo && logoUriString.isNotBlank()) {
                    try {
                        val logoBitmap = BitmapFactory.decodeStream(
                            emailContext.contentResolver.openInputStream(Uri.parse(logoUriString))
                        )
                        if (logoBitmap != null) {
                            logoFile = File(emailContext.cacheDir, "logo_guest_${guest.id}.png")
                            val outputStream = FileOutputStream(logoFile)
                            logoBitmap.compress(Bitmap.CompressFormat.PNG, 90, outputStream)
                            outputStream.close()
                            logoBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                // Build HTML email with Content-ID references
                val htmlEmail = if (temporaryEnabled) {
                    QrEmailHtmlBuilder.build(
                        QrEmailHtmlOptions(
                            holderName = if (sendWholeBatch) {
                                guest.temporaryArtistName.ifBlank { guest.name }
                            } else {
                                guest.name
                            },
                            contentBefore = contentBefore,
                            contentAfter = contentAfter,
                            signature = signature,
                            includeQr = includeQr,
                            headerText = emailContext.getString(R.string.temp_guest_email_html_header),
                            footerText = emailContext.getString(R.string.guest_email_html_footer),
                            qrAttachmentText = emailContext.getString(R.string.email_qr_attachment_text),
                            qrAttachmentNote = emailContext.getString(R.string.email_qr_attachment_note),
                            includeDigitalWalletPass = false,
                            digitalWalletPassTitle = "",
                            digitalWalletPassDescription = "",
                            digitalWalletPassCompatibility = "",
                            includeLogo = includeLogo,
                            useContentId = true,
                            theme = QrEmailTheme.TempGuest,
                            qrCodes = if (qrAttachments.size > 1) {
                                qrAttachments.mapIndexed { index, attachment ->
                                    QrEmailCode(
                                        holderName = batchCodes[index].name,
                                        contentId = attachment.contentId,
                                    )
                                }
                            } else emptyList(),
                        )
                    )
                } else {
                    buildGuestEmailHtml(
                        guestName = guest.name,
                        contentBefore = contentBefore,
                        contentAfter = contentAfter,
                        signature = signature,
                        includeQr = includeQr,
                        headerText = emailContext.getString(R.string.guest_email_html_header),
                        footerText = emailContext.getString(R.string.guest_email_html_footer),
                        qrAttachmentText = emailContext.getString(R.string.email_qr_attachment_text),
                        qrAttachmentNote = emailContext.getString(R.string.email_qr_attachment_note),
                        includeDigitalWalletPass = includeDigitalWalletPass,
                        digitalWalletPassTitle = emailContext.getString(R.string.email_wallet_section_title),
                        digitalWalletPassDescription = emailContext.getString(R.string.email_wallet_section_description),
                        digitalWalletPassCompatibility = emailContext.getString(R.string.email_wallet_section_compatibility),
                        includeLogo = includeLogo,
                        useContentId = true
                    )
                }
                
                // Plain text fallback
                val plainTextEmail = buildString {
                    append(contentBefore)
                    append("\n\n")
                    if (includeQr) {
                        append("[ QR Code - See attachment ]\n\n")
                    }
                    if (includeDigitalWalletPass) {
                        append("[ Digital Wallet Pass (.pkpass) - See attachment ]\n\n")
                    }
                    append(contentAfter)
                    append("\n\n")
                    append(signature)
                }
                
                // Send via Gmail API
                val result = gmailSendService.sendEmail(
                    gmailService = gmailService,
                    to = targetEmail,
                    subject = subject,
                    htmlContent = htmlEmail,
                    plainText = plainTextEmail,
                    qrFile = null,
                    logoFile = logoFile,
                    digitalWalletPassFile = digitalWalletPassFile,
                    qrAttachments = qrAttachments
                )
                
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            emailContext,
                            emailContext.getString(R.string.email_api_success),
                            Toast.LENGTH_SHORT
                        ).show()
                        // Close dialog on success
                        onSuccess()
                    },
                    onFailure = { e ->
                        android.util.Log.d("GmailAuth", "Email send failed: ${e.javaClass.simpleName} - ${e.message}")
                        // Check if authorization is required
                        if (e is com.eventmanager.app.data.sync.GmailAuthorizationRequiredException) {
                            // Launch authorization dialog
                            android.util.Log.d("GmailAuth", "Launching OAuth consent screen")
                            val launcher = authLauncherHolder.value
                            if (launcher != null) {
                                try {
                                    launcher.launch(e.authIntent)
                                    android.util.Log.d("GmailAuth", "OAuth consent screen launched successfully")
                                } catch (ex: Exception) {
                                    android.util.Log.e("GmailAuth", "Error launching OAuth consent screen", ex)
                                    Toast.makeText(
                                        emailContext,
                                        "Error launching authorization screen: ${ex.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                android.util.Log.e("GmailAuth", "Auth launcher is null! Cannot launch OAuth consent")
                                Toast.makeText(
                                    emailContext,
                                    "Error: Authorization launcher not ready. Please try again.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else if (e is com.eventmanager.app.data.sync.GmailNotConfiguredException ||
                            e is com.eventmanager.app.data.sync.GmailPlayServicesUnavailableException
                        ) {
                            Toast.makeText(
                                emailContext,
                                e.message,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                emailContext,
                                emailContext.getString(R.string.email_api_error_message, e.message ?: "Unknown error"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(
                    emailContext,
                    emailContext.getString(R.string.email_api_error_message, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        
        // Launcher for Gmail authorization
        val authLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            // After authorization, retry sending email
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                android.util.Log.d("GmailAuth", "OAuth authorization successful, retrying email send")
                isSendingEmail = true
                coroutineScope.launch {
                    sendGuestEmailViaApi(
                        emailContext,
                        settingsManager,
                        gmailAuthService,
                        gmailSendService,
                        guest,
                        targetEmail,
                        onSuccess = {
                            isSendingEmail = false
                            showEmailConfirmDialog = false
                        }
                    )
                    isSendingEmail = false
                }
            } else {
                android.util.Log.w("GmailAuth", "OAuth authorization cancelled or failed")
            }
        }
        
        // Initialize the holder immediately when launcher is created
        LaunchedEffect(authLauncher) {
            authLauncherHolder.value = authLauncher
            android.util.Log.d("GmailAuth", "Auth launcher initialized")
        }
        
        // Manual email send function
        fun sendGuestEmailManually(
            emailContext: android.content.Context,
            settingsManager: SettingsManager,
            guest: Guest,
            targetEmail: String
        ) {
            try {
                // Get email settings (use volunteer settings as fallback)
                val subject = settingsManager.getGuestEmailSubject().ifEmpty { 
                    emailContext.getString(R.string.guest_email_subject_default) 
                }
                val contentBefore = settingsManager.getGuestEmailContentBefore().ifEmpty { 
                    emailContext.getString(R.string.guest_email_content_before_default) 
                }
                val includeQr = settingsManager.isGuestEmailIncludeQrEnabled()
                val contentAfter = settingsManager.getGuestEmailContentAfter().ifEmpty { 
                    emailContext.getString(R.string.guest_email_content_after_default) 
                }
                val signature = settingsManager.getGuestEmailSignature().ifEmpty { 
                    emailContext.getString(R.string.email_signature_default) 
                }
                val includeDigitalWalletPass = settingsManager.isEmailIncludeDigitalWalletPassEnabled()
                val logoUriString = settingsManager.getEmailLogoUri()
                val associationName = settingsManager.getEmailAssociationName()
                
                // Generate QR code for guest (NanoID plain text for Lightspeed compatibility)
                val qrBitmap = QRCodeUtils.generateQrImageBitmap(guest.nanoId, 512)
                
                // Build simple HTML email
                val htmlEmail = buildGuestEmailHtml(
                    guestName = guest.name,
                    contentBefore = contentBefore,
                    contentAfter = contentAfter,
                    signature = signature,
                    includeQr = false, // Don't include QR in HTML for manual send
                    headerText = emailContext.getString(R.string.guest_email_html_header),
                    footerText = emailContext.getString(R.string.guest_email_html_footer),
                    qrAttachmentText = emailContext.getString(R.string.email_qr_attachment_text),
                    qrAttachmentNote = emailContext.getString(R.string.email_qr_attachment_note),
                    includeDigitalWalletPass = false,
                    digitalWalletPassTitle = "",
                    digitalWalletPassDescription = "",
                    digitalWalletPassCompatibility = "",
                    includeLogo = false,
                    useContentId = false
                )
                
                // Plain text fallback
                val plainTextEmail = buildString {
                    append(contentBefore)
                    append("\n\n")
                    if (includeQr) {
                        append("[ QR Code - See attachment ]\n\n")
                    }
                    if (includeDigitalWalletPass) {
                        append("[ Digital Wallet Pass (.pkpass) - See attachment ]\n\n")
                    }
                    append(contentAfter)
                    append("\n\n")
                    append(signature)
                }
                
                // Save QR code / digital wallet pass files for attachments
                var qrUri: Uri? = null
                if (includeQr && qrBitmap != null) {
                    val qrFile = File(emailContext.cacheDir, "qr_code_guest_${guest.id}.png")
                    val outputStream = FileOutputStream(qrFile)
                    qrBitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                    
                    qrUri = FileProvider.getUriForFile(
                        emailContext,
                        "${emailContext.packageName}.fileprovider",
                        qrFile
                    )
                }

                var walletPassUri: Uri? = null
                if (includeDigitalWalletPass) {
                    val walletPassFile = DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${guest.nanoId}-${System.currentTimeMillis()}",
                        holderName = guest.name,
                        qrPayload = guest.nanoId,
                        logoUriString = logoUriString,
                        associationName = associationName
                    ) ?: DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${guest.nanoId}-${System.currentTimeMillis()}",
                        holderName = guest.name,
                        qrPayload = guest.nanoId,
                        logoUriString = null,
                        associationName = associationName
                    )
                    if (walletPassFile != null && walletPassFile.exists()) {
                        walletPassUri = FileProvider.getUriForFile(
                            emailContext,
                            "${emailContext.packageName}.fileprovider",
                            walletPassFile
                        )
                    }
                }
                
                // Create email intent with HTML and QR attachment
                val emailIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (qrUri != null || walletPassUri != null) "*/*" else "text/html"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, plainTextEmail)
                    putExtra("android.intent.extra.HTML_TEXT", htmlEmail)

                    val attachments = ArrayList<Uri>()
                    qrUri?.let { attachments.add(it) }
                    walletPassUri?.let { attachments.add(it) }
                    if (attachments.size == 1) {
                        putExtra(Intent.EXTRA_STREAM, attachments.first())
                    } else if (attachments.size > 1) {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments)
                        action = Intent.ACTION_SEND_MULTIPLE
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                emailContext.startActivity(Intent.createChooser(emailIntent, emailContext.getString(R.string.send_by_mail)))
            } catch (e: Exception) {
                Toast.makeText(
                    emailContext,
                    emailContext.getString(R.string.email_error_message, e.message ?: "Unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        
        AlertDialog(
            onDismissRequest = { showEmailConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = getStringResource(R.string.email_confirm_send_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = getStringResource(R.string.email_confirm_send_message, targetEmail),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    HorizontalDivider()
                    
                    // Manual send — admin only (Billeterie: Gmail API only)
                    if (!readOnly) {
                        OutlinedButton(
                            onClick = {
                                showEmailConfirmDialog = false
                                sendGuestEmailManually(emailContext, settingsManager, guest, targetEmail)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = getStringResource(R.string.email_send_manual),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = getStringResource(R.string.email_send_manual_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    
                    // API Send Option (only if authenticated)
                    if (isGmailAuthenticated) {
                        Button(
                            onClick = {
                                isSendingEmail = true
                                coroutineScope.launch {
                                    sendGuestEmailViaApi(
                                        emailContext,
                                        settingsManager,
                                        gmailAuthService,
                                        gmailSendService,
                                        guest,
                                        targetEmail,
                                        onSuccess = {
                                            isSendingEmail = false
                                            showEmailConfirmDialog = false
                                        }
                                    )
                                    isSendingEmail = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSendingEmail
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                if (isSendingEmail) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isSendingEmail) getStringResource(R.string.email_sending) else getStringResource(R.string.email_send_api),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = getStringResource(R.string.email_send_api_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                )
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = getStringResource(R.string.email_api_not_authenticated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showEmailConfirmDialog = false }) {
                    Text(getStringResource(R.string.cancel))
                }
            }
        )
    }

    if (showGuestNoEmailStaffDialog) {
        AlertDialog(
            onDismissRequest = { showGuestNoEmailStaffDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = getStringResource(R.string.email_no_email_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = getStringResource(R.string.email_no_email_guest_staff_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showGuestNoEmailStaffDialog = false }) {
                    Text(getStringResource(R.string.ok))
                }
            }
        )
    }

    if (showNfcDialog) {
        val platformContext = remember { com.eventmanager.app.platform.createPlatformContext(context) }
        AddNfcUidDialog(
            platformContext = platformContext,
            onDismiss = { showNfcDialog = false },
            onConfirmUid = { uid ->
                onAssignNfcUid(guest, uid)
                showNfcDialog = false
            }
        )
    }
}

@Composable
private fun GuestInformationSection(
    guest: Guest,
    venues: List<VenueEntity>,
    isPhone: Boolean,
    onClose: () -> Unit,
    easterEggEnabled: Boolean,
    barDiscountPercent: Int = 0,
    readOnly: Boolean = false,
    canManagePhoto: Boolean = false,
    canExportPhoto: Boolean = false,
    onUploadPhoto: (ByteArray) -> Unit = {},
    onRemovePhoto: () -> Unit = {},
    /** Billeterie permanent guest: open staff-safe QR dialog (blurred + API email only). */
    onShowQr: (() -> Unit)? = null,
    temporaryFeaturesEnabled: Boolean = false,
    temporaryAccesses: List<VenueAccess> = emptyList(),
) {
    val context = LocalContext.current
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    val (easterNameColor, easterSubtitleColor) = leonardoEasterEggProfileNameColors()
    val easterHeaderIconTint = leonardoEasterEggHeaderIconTint()
    
    if (guest.isTemporaryGuest) {
        TemporaryGuestInformationSection(
            guest = guest,
            isPhone = isPhone,
            onClose = onClose,
            readOnly = readOnly,
            temporaryFeaturesEnabled = temporaryFeaturesEnabled,
            accesses = temporaryAccesses,
            barDiscountPercent = barDiscountPercent,
            onShowQr = onShowQr,
        )
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(responsivePadding)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(if (isPhone) 12.dp else 14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (isPhone) 14.dp else 18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfilePhotoHeaderAvatar(
                            name = guest.name,
                            photoUrl = guest.profilePhotoUrl,
                            photoPath = guest.resolvedProfilePhotoPath(),
                            size = if (isPhone) 48.dp else 56.dp,
                            canExport = canExportPhoto,
                            canManage = canManagePhoto,
                            onUpload = onUploadPhoto,
                            onRemove = onRemovePhoto,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = context.getString(R.string.guest_information),
                                style = if (isPhone) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = guest.name,
                                style = if (isPhone) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (easterEggEnabled) easterNameColor else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (guest.lastNameAbbreviation.isNotEmpty()) {
                                Text(
                                    text = guest.lastNameAbbreviation,
                                    style = if (isPhone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                    color = if (easterEggEnabled) easterSubtitleColor else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            onShowQr?.let { openQr ->
                                IconButton(onClick = openQr) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode,
                                        contentDescription = context.getString(R.string.qr_code),
                                        tint = if (easterEggEnabled) easterHeaderIconTint else MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = context.getString(R.string.close),
                                    tint = if (easterEggEnabled) easterHeaderIconTint else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(if (isPhone) 10.dp else 14.dp))

                val detailItems = buildList {
                    add(
                        Triple(
                            context.getString(R.string.invitations),
                            guest.invitations.toString(),
                            Icons.Default.People
                        )
                    )
                    add(
                        Triple(
                            context.getString(R.string.venue),
                            getVenueDisplayString(guest.venueName, venues),
                            Icons.Default.LocationOn
                        )
                    )
                    if (guest.email.isNotEmpty()) {
                        add(
                            Triple(
                                context.getString(R.string.guest_email),
                                guest.email,
                                Icons.Default.Email
                            )
                        )
                    }
                    if (guest.phoneNumber.isNotEmpty()) {
                        add(
                            Triple(
                                context.getString(R.string.guest_phone_number),
                                guest.phoneNumber,
                                Icons.Default.Phone
                            )
                        )
                    }
                    if (guest.notes.isNotEmpty()) {
                        add(
                            Triple(
                                context.getString(R.string.notes),
                                guest.notes,
                                Icons.AutoMirrored.Filled.Notes
                            )
                        )
                    }
                    if (barDiscountPercent > 0) {
                        add(
                            Triple(
                                context.getString(R.string.bar_discount_percent_label),
                                barDiscountPercent.toString(),
                                Icons.Default.LocalBar
                            )
                        )
                    }
                }

                if (isPhone) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        detailItems.forEach { (label, value, icon) ->
                            DetailTile(
                                label = label,
                                value = value,
                                icon = icon
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        detailItems.chunked(2).forEach { rowItems ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowItems.forEach { (label, value, icon) ->
                                    DetailTile(
                                        label = label,
                                        value = value,
                                        icon = icon,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                if (!readOnly) {
                    NfcUidInfoRow(
                        uid = guest.nfcCardUid,
                        isPhone = isPhone
                    )

                    Spacer(modifier = Modifier.height(if (isPhone) 6.dp else 8.dp))

                    NanoIdInfoRow(
                        label = "NanoID",
                        value = guest.nanoId,
                        isPhone = isPhone
                    )
                }
            }
        }
    }
}

@Composable
private fun TemporaryGuestInformationSection(
    guest: Guest,
    isPhone: Boolean,
    onClose: () -> Unit,
    readOnly: Boolean = false,
    temporaryFeaturesEnabled: Boolean = false,
    accesses: List<VenueAccess> = emptyList(),
    barDiscountPercent: Int = 0,
    onShowQr: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    val eventDateText = remember(guest.temporaryEventDate) {
        guest.temporaryEventDate?.let { com.eventmanager.app.data.utils.DateTimeUtils.formatGenevaDateOnly(it) } ?: "-"
    }
    val artistText = guest.temporaryArtistName.ifBlank { "-" }
    val contactText = guest.temporaryContactPhone.ifBlank { "-" }
    val notesText = guest.notes.ifBlank { "-" }
    val venueText = guest.temporaryVenueName.ifBlank { "-" }
    val contactEmailText = guest.temporaryContactEmail.ifBlank { "-" }
    val entryStatusText = temporaryEntryValidatedLabel(guest)
        ?.let { context.getString(R.string.temp_guest_entry_validated_at, it) }
        ?: context.getString(R.string.temp_guest_entry_not_yet)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(responsivePadding)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (isPhone) 12.dp else 14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(if (isPhone) 14.dp else 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AssistChip(
                            onClick = { },
                            label = { Text(context.getString(R.string.temp_guest_chip_label), fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Event,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                        Text(
                            text = guest.name,
                            style = if (isPhone) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = artistText,
                            style = if (isPhone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onShowQr != null) {
                            IconButton(onClick = onShowQr) {
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = context.getString(R.string.qr_code),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = context.getString(R.string.close),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isPhone) 10.dp else 14.dp))

            val tiles = buildList {
                add(
                    Triple(
                        context.getString(R.string.temp_guest_event_date_label),
                        eventDateText,
                        Icons.Default.DateRange,
                    )
                )
                add(
                    Triple(
                        context.getString(R.string.temp_guest_artist_label),
                        artistText,
                        Icons.Default.Group,
                    )
                )
                add(
                    Triple(
                        context.getString(R.string.temp_guest_contact_phone_label),
                        contactText,
                        Icons.Default.Phone,
                    )
                )
                if (temporaryFeaturesEnabled) {
                    add(
                        Triple(
                            context.getString(R.string.temp_guest_contact_email_label),
                            contactEmailText,
                            Icons.Default.Email,
                        )
                    )
                    add(
                        Triple(
                            context.getString(R.string.temp_guest_venue_label),
                            venueText,
                            Icons.Default.LocationOn,
                        )
                    )
                    add(
                        Triple(
                            context.getString(R.string.temp_guest_entry_status_label),
                            entryStatusText,
                            Icons.Default.CheckCircle,
                        )
                    )
                    if (barDiscountPercent > 0) {
                        add(
                            Triple(
                                context.getString(R.string.bar_discount_percent_label),
                                "$barDiscountPercent%",
                                Icons.Default.LocalOffer,
                            )
                        )
                    }
                }
                add(
                    Triple(
                        context.getString(R.string.notes),
                        notesText,
                        Icons.AutoMirrored.Filled.Notes,
                    )
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isPhone) {
                    tiles.forEach { (label, value, icon) ->
                        DetailTile(label = label, value = value, icon = icon)
                    }
                } else {
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (label, value, icon) ->
                                DetailTile(
                                    label = label,
                                    value = value,
                                    icon = icon,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Keeps a lone trailing tile at half width instead of stretching it.
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (temporaryFeaturesEnabled && accesses.isNotEmpty()) {
                    TemporaryGuestAccessBlock(accesses = accesses)
                }
            }

            if (!readOnly) {
                Spacer(modifier = Modifier.height(if (isPhone) 6.dp else 8.dp))

                NanoIdInfoRow(
                    label = "NanoID",
                    value = guest.nanoId,
                    isPhone = isPhone
                )
            }
        }
    }
}

@Composable
private fun DetailTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(
    guest: Guest,
    onEdit: (Guest) -> Unit,
    onAddNfcCard: () -> Unit,
    onDelete: (Guest) -> Unit,
    onShowQr: () -> Unit,
    isPhone: Boolean,
    canManagePhoto: Boolean = false,
    onUploadPhoto: (ByteArray) -> Unit = {},
    onRemovePhoto: () -> Unit = {},
) {
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    val responsiveSpacing = if (isPhone) getPhonePortraitSpacing() else getResponsiveSpacing()
    val context = LocalContext.current
    val pickPhoto = rememberProfilePhotoPicker(onUploadPhoto)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(responsivePadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(if (isPhone) 20.dp else 24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = context.getString(R.string.actions),
                    style = if (isPhone) getPhonePortraitTypography() else getResponsiveTitleTypography(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(responsiveSpacing))
            
            if (isPhone) {
                // Stack vertically on phones
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!guest.isTemporaryGuest) {
                        OutlinedButton(
                            onClick = onAddNfcCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.add_nfc_card))
                        }

                        OutlinedButton(
                            onClick = onShowQr,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.qr_code))
                        }

                        ProfilePhotoActionButtons(
                            hasPhoto = guest.hasStoredProfilePhoto(),
                            enabled = canManagePhoto,
                            onAddOrChange = pickPhoto,
                            onRemove = onRemovePhoto,
                        )
                    }
                    
                    OutlinedButton(
                        onClick = { onEdit(guest) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(context.getString(R.string.edit_guest))
                    }
                    
                    OutlinedButton(
                        onClick = { onDelete(guest) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(context.getString(R.string.delete_guest))
                    }
                }
            } else {
                // Two-row layout on larger screens to avoid cramped actions.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!guest.isTemporaryGuest) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onAddNfcCard,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(context.getString(R.string.add_nfc_card))
                            }

                            OutlinedButton(
                                onClick = onShowQr,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(context.getString(R.string.qr_code))
                            }
                        }
                        ProfilePhotoActionButtons(
                            hasPhoto = guest.hasStoredProfilePhoto(),
                            enabled = canManagePhoto,
                            onAddOrChange = pickPhoto,
                            onRemove = onRemovePhoto,
                            sideBySide = true,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onEdit(guest) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.edit_guest))
                        }

                        OutlinedButton(
                            onClick = { onDelete(guest) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(context.getString(R.string.delete_guest))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    isPhone: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Text(
            text = value,
            style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun NanoIdInfoRow(
    label: String,
    value: String,
    isPhone: Boolean
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showCopiedToast by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(end = 8.dp)
            )

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(value))
                    showCopiedToast = true
                    Toast.makeText(context, "NanoID copied to clipboard", Toast.LENGTH_SHORT).show()
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(2000)
                        showCopiedToast = false
                    }
                },
                modifier = Modifier.size(if (isPhone) 32.dp else 40.dp)
            ) {
                Icon(
                    imageVector = if (showCopiedToast) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (showCopiedToast) "Copied" else "Copy NanoID",
                    tint = if (showCopiedToast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isPhone) 16.dp else 20.dp)
                )
            }
        }
    }
}

/**
 * Builds a professional HTML email for guests with embedded QR code
 */
private fun buildGuestEmailHtml(
    guestName: String,
    contentBefore: String,
    contentAfter: String,
    signature: String,
    qrCodeBase64: String? = null,
    includeQr: Boolean,
    headerText: String,
    footerText: String,
    qrAttachmentText: String,
    qrAttachmentNote: String,
    includeDigitalWalletPass: Boolean,
    digitalWalletPassTitle: String,
    digitalWalletPassDescription: String,
    digitalWalletPassCompatibility: String,
    logoBase64: String? = null,
    includeLogo: Boolean,
    useContentId: Boolean = false
): String {
    // Convert newlines to HTML breaks and escape HTML
    fun String.toHtmlParagraphs(): String {
        return this.split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("") { paragraph ->
                "<p style=\"margin: 0 0 16px 0; line-height: 1.6;\">${paragraph.replace("\n", "<br>")}</p>"
            }
    }
    
    // Convert signature to HTML with bold formatting
    fun String.toHtmlSignature(): String {
        return this.split("\n")
            .filter { it.isNotBlank() }
            .joinToString("<br>") { line ->
                "<strong style=\"color: #1f2937; font-weight: 600;\">${line}</strong>"
            }
    }
    
    val qrSection = if (includeQr) {
        """
        <tr>
            <td style="padding: 40px 40px; text-align: center; background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);">
                <table role="presentation" cellpadding="0" cellspacing="0" style="margin: 0 auto; width: 100%; max-width: 300px;">
                    <tr>
                        <td style="background-color: #ffffff; padding: 32px; border-radius: 16px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); border: 2px solid #e2e8f0;">
                            <div style="background: linear-gradient(135deg, #10b981 0%, #059669 100%); padding: 24px; border-radius: 12px; margin-bottom: 20px;">
                                <table role="presentation" cellpadding="0" cellspacing="0" style="width: 200px; height: 200px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                                    <tr>
                                        <td style="text-align: center; vertical-align: middle; padding: 10px;">
                                            ${if (useContentId && includeQr) {
                                                """<img src="cid:qrcode" 
                                                     alt="QR Code" 
                                                     width="180" 
                                                     height="180" 
                                                     style="display: block; margin: 0 auto; border: none; max-width: 180px; max-height: 180px;">"""
                                            } else if (qrCodeBase64 != null) {
                                                """<img src="data:image/png;base64,$qrCodeBase64" 
                                                     alt="QR Code" 
                                                     width="180" 
                                                     height="180" 
                                                     style="display: block; margin: 0 auto; border: none; max-width: 180px; max-height: 180px;">"""
                                            } else {
                                                """<div style="color: #94a3b8; font-size: 14px; text-align: center; padding: 20px;">
                                                    <table role="presentation" cellpadding="0" cellspacing="0" style="width: 120px; height: 120px; margin: 0 auto 12px; border: 3px dashed #cbd5e1; border-radius: 8px; background-color: #f8fafc;">
                                                        <tr>
                                                            <td style="text-align: center; vertical-align: middle;">
                                                                <div style="font-size: 32px; color: #94a3b8; font-weight: 600;">QR</div>
                                                            </td>
                                                        </tr>
                                                    </table>
                                                    <div style="color: #64748b; font-weight: 500;">${qrAttachmentText.replace("\n", "<br>")}</div>
                                                 </div>"""
                                            }}
                                        </td>
                                    </tr>
                                </table>
                            </div>
                            <p style="margin: 0; font-size: 14px; color: #475569; font-weight: 500; letter-spacing: 0.3px;">
                                $guestName
                            </p>
                            ${if (qrCodeBase64 == null) {
                                """<p style="margin: 12px 0 0 0; font-size: 12px; color: #64748b; font-style: italic;">
                                    $qrAttachmentNote
                                </p>"""
                            } else ""}
                        </td>
                    </tr>
                </table>
            </td>
        </tr>
        """
    } else ""
    
    val walletPassSection = if (includeDigitalWalletPass && useContentId) {
        """
        <tr>
            <td style="padding: 10px 40px 26px 40px;">
                <table role="presentation" cellpadding="0" cellspacing="0" width="100%" style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px;">
                    <tr>
                        <td style="padding: 12px 14px;">
                            <div style="font-size: 14px; font-weight: 700; color: #1f2937; margin-bottom: 4px;">$digitalWalletPassTitle</div>
                            <div style="font-size: 12px; color: #475569; line-height: 1.45;">$digitalWalletPassDescription</div>
                            <div style="margin-top: 4px; font-size: 11px; color: #64748b;">$digitalWalletPassCompatibility</div>
                        </td>
                    </tr>
                </table>
            </td>
        </tr>
        """
    } else ""

    return """
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Your QR Code</title>
    </head>
    <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f4f4f5;">
        <table role="presentation" cellpadding="0" cellspacing="0" width="100%" style="min-width: 100%; background-color: #f4f4f5;">
            <tr>
                <td align="center" style="padding: 40px 20px;">
                    <!-- Main Container -->
                    <table role="presentation" cellpadding="0" cellspacing="0" width="600" style="max-width: 600px; width: 100%; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.07);">
                        
                        <!-- Header -->
                        <tr>
                            <td style="background: linear-gradient(135deg, #10b981 0%, #059669 50%, #047857 100%); padding: 48px 40px; text-align: center; position: relative; overflow: hidden;">
                                <div style="position: absolute; top: -50%; left: -50%; width: 200%; height: 200%; background: radial-gradient(circle, rgba(255,255,255,0.1) 0%, transparent 70%); pointer-events: none;"></div>
                                <h1 style="margin: 0; color: #ffffff; font-size: 28px; font-weight: 700; letter-spacing: -0.8px; text-shadow: 0 2px 8px rgba(0,0,0,0.15); position: relative; z-index: 1;">
                                    $headerText
                                </h1>
                                <div style="margin-top: 12px; height: 3px; width: 60px; background-color: rgba(255,255,255,0.6); margin-left: auto; margin-right: auto; border-radius: 2px; position: relative; z-index: 1;"></div>
                            </td>
                        </tr>
                        
                        <!-- Content Before -->
                        <tr>
                            <td style="padding: 40px 40px 24px 40px; color: #1f2937; font-size: 16px; line-height: 1.7;">
                                ${contentBefore.toHtmlParagraphs()}
                            </td>
                        </tr>
                        
                        <!-- QR Code Section -->
                        $qrSection
                        
                        <!-- Content After -->
                        <tr>
                            <td style="padding: 24px 40px 40px 40px; color: #1f2937; font-size: 16px; line-height: 1.7;">
                                ${contentAfter.toHtmlParagraphs()}
                            </td>
                        </tr>

                        <!-- Signature -->
                        <tr>
                            <td style="padding: 32px 40px; border-top: 2px solid #f1f5f9; background-color: #fafbfc;">
                                <table role="presentation" cellpadding="0" cellspacing="0" width="100%">
                                    <tr>
                                        <td style="vertical-align: ${if (includeLogo && logoBase64 != null) "top" else "middle"};">
                                            <div style="color: #1f2937; font-size: 15px; line-height: 1.8;">
                                                ${signature.toHtmlSignature()}
                                            </div>
                                        </td>
                                        ${if (includeLogo) {
                                            if (useContentId) {
                                                """<td style="text-align: right; padding-left: 24px; vertical-align: middle;">
                                                    <img src="cid:logo" 
                                                         alt="Logo" 
                                                         style="max-width: 120px; max-height: 80px; display: block; border: none; height: auto;">
                                                </td>"""
                                            } else if (logoBase64 != null) {
                                                """<td style="text-align: right; padding-left: 24px; vertical-align: middle;">
                                                    <img src="data:image/png;base64,$logoBase64" 
                                                         alt="Logo" 
                                                         style="max-width: 120px; max-height: 80px; display: block; border: none; height: auto;">
                                                </td>"""
                                            } else ""
                                        } else ""}
                                    </tr>
                                </table>
                            </td>
                        </tr>

                        $walletPassSection
                        
                    </table>
                    
                    <!-- Footer -->
                    <table role="presentation" cellpadding="0" cellspacing="0" width="600" style="max-width: 600px; width: 100%;">
                        <tr>
                            <td style="padding: 32px 40px; text-align: center;">
                                <div style="width: 40px; height: 1px; background: linear-gradient(90deg, transparent, #e5e7eb, transparent); margin: 0 auto 20px;"></div>
                                <p style="margin: 0; font-size: 12px; color: #9ca3af; line-height: 1.6;">
                                    $footerText
                                </p>
                            </td>
                        </tr>
                    </table>
                    
                </td>
            </tr>
        </table>
    </body>
    </html>
    """.trimIndent()
}
