package com.eventmanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.annotation.StringRes
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.hasStoredProfilePhoto
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.data.utils.DateTimeUtils
import com.eventmanager.app.data.utils.VolunteerActivityManager
import com.eventmanager.app.data.utils.getActivityStatusText
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.ui.util.shiftTimeLabelIfRelevant
import com.eventmanager.app.utils.QRCodeUtils
import com.eventmanager.app.R
import java.text.SimpleDateFormat
import java.util.*
import com.eventmanager.app.utils.ValidationUtils
import com.eventmanager.app.data.sync.formatDate
import com.eventmanager.app.data.sync.formatDateTime
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.GmailAuthService
import com.eventmanager.app.data.sync.GmailSendService
import com.eventmanager.app.utils.DigitalWalletPassGenerator
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
private fun getStringResource(@StringRes stringRes: Int, vararg args: Any): String {
    val context = LocalContext.current
    return context.getString(stringRes, *args)
}

@Composable
private fun getRankDisplayName(rank: VolunteerRank?): String {
    return when (rank) {
        VolunteerRank.SPECIAL -> "✨SPECIAL✨"
        else -> rank?.name ?: "No Rank"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val isPhone = !isTablet()
    val responsivePadding = if (isPhone) getPhonePortraitPadding() else getResponsivePadding()
    val seasonalFunEnabled = remember { settingsManagerFor(context).isSeasonalFunEnabled() }
    val leonardoEasterEggEnabled = remember(volunteer, seasonalFunEnabled) {
        seasonalFunEnabled && isLeonardoMondadaProfile(
            firstName = volunteer.name,
            lastNameOrAbbreviation = volunteer.lastNameAbbreviation
        )
    }
    val (easterNameColor, easterSubtitleColor) = leonardoEasterEggProfileNameColors()
    val easterHeaderIconTint = leonardoEasterEggHeaderIconTint()
    val glowTransition = rememberInfiniteTransition(label = "volunteer-glow")
    val glow by glowTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1100),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "volunteer-glow-alpha"
    )
    var showQrDialog by remember { mutableStateOf(false) }
    var showNfcDialog by remember { mutableStateOf(false) }
    
    // Calculate age from date of birth
    val age = calculateAge(volunteer.dateOfBirth)
    
    // Sort jobs by date (most recent first)
    val sortedJobs = volunteerJobs.sortedByDescending { it.date }
    
    val activityVolunteer = remember(volunteer, volunteerJobs) {
        VolunteerActivityManager.calculateActivityFromJobs(volunteer, volunteerJobs)
    }
    val totalShifts = volunteerJobs.size
    val isActive = VolunteerActivityManager.isVolunteerActive(activityVolunteer)
    val activityStatusText = VolunteerActivityManager.getActivityStatusText(activityVolunteer, context)
    
    // Calculate current rank dynamically
    val settingsManager = remember { com.eventmanager.app.data.sync.settingsManagerFor(context) }
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val volunteerBenefitStatus = remember(volunteer.id, volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.calculateVolunteerBenefitStatus(volunteer, volunteerJobs, jobTypeConfigs, offsetHours = offsetHours)
    }
    val meetingNovaBenefitsExcludedForOrion = remember(volunteer.id, volunteerJobs, jobTypeConfigs, offsetHours) {
        BenefitCalculator.isVolunteerOrionActive(volunteerJobs, jobTypeConfigs, offsetHours = offsetHours)
    }
    val currentRank = volunteerBenefitStatus.rank
    
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
                // Modern header with volunteer name and status (FIXED AT TOP)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(responsivePadding)
                        .padding(bottom = if (isPhone) 8.dp else 12.dp),
                    shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(if (isPhone) 12.dp else 20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfilePhotoHeaderAvatar(
                                name = volunteer.name,
                                photoUrl = volunteer.profilePhotoUrl,
                                photoPath = volunteer.resolvedProfilePhotoPath(),
                                size = if (isPhone) 48.dp else 56.dp,
                                canExport = true,
                                canManage = rememberProfilePhotosUploadEnabled(viewModel),
                                onUpload = { bytes -> viewModel?.uploadProfilePhotoForVolunteer(volunteer, bytes) },
                                onRemove = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
                            )
                            Spacer(modifier = Modifier.width(if (isPhone) 8.dp else 12.dp))
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = volunteer.name,
                                    style = if (isPhone) getPhonePortraitTypography() else getResponsiveTypography(),
                                    fontWeight = FontWeight.Bold,
                                    color = if (leonardoEasterEggEnabled) easterNameColor else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                
                                Spacer(modifier = Modifier.height(if (isPhone) 2.dp else 4.dp))
                                
                                Text(
                                    text = "${volunteer.lastNameAbbreviation} • ${volunteer.email}",
                                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                                    color = if (leonardoEasterEggEnabled) easterSubtitleColor else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = getStringResource(R.string.close),
                                    tint = if (leonardoEasterEggEnabled) easterHeaderIconTint else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
                        
                        // Status indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(
                                        color = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                        shape = CircleShape
                                    )
                            )
                            
                            Text(
                                text = activityStatusText,
                                style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                                color = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                
                // Scrollable content (SCROLLS BELOW HEADER)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(if (isPhone) 8.dp else 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = responsivePadding,
                        end = responsivePadding,
                        bottom = responsivePadding
                    )
                ) {
                    // Personal Information Section
                    item {
                        PersonalInformationSection(
                            volunteer = volunteer,
                            age = age,
                            isPhone = isPhone
                        )
                    }
                    
                    // Volunteer-Specific Information Section
                    item {
                        VolunteerSpecificSection(
                            volunteer = volunteer,
                            isActive = isActive,
                            activityStatusText = activityStatusText,
                            totalShifts = totalShifts,
                            lastShiftDate = activityVolunteer.lastShiftDate,
                            isPhone = isPhone,
                            currentRank = currentRank
                        )
                    }

                    if (onManualAccountAdjust != null && viewModel != null) {
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

                    item {
                        VolunteerFutureEntriesSection(
                            volunteerJobs = volunteerJobs,
                            jobTypeConfigs = jobTypeConfigs,
                            onConfirmEntry = onConfirmFutureEntry,
                            hasActiveFreeEntryBenefit = volunteerBenefitStatus.benefits.freeEntry,
                            meetingNovaBenefitsExcludedForOrion = meetingNovaBenefitsExcludedForOrion
                        )
                    }
                    
                    // Shift History Section
                    item {
                        ShiftHistorySection(
                            jobs = sortedJobs,
                            isPhone = isPhone,
                            venues = venues,
                            jobTypeConfigs = jobTypeConfigs
                        )
                    }

                    if (viewModel != null) {
                        item {
                            LocalAdminRightsSection(
                                viewModel = viewModel,
                                isAdmin = volunteer.isAdmin,
                                displayName = volunteer.name,
                                kind = com.eventmanager.app.data.security.LocalAdminTargetKind.VOLUNTEER,
                                targetId = volunteer.id,
                                volunteer = volunteer,
                            )
                        }
                    }

                    // Action Buttons Section
                    item {
                        ActionButtonsSection(
                            volunteer = volunteer,
                            onEdit = onEdit,
                            onAddNfcCard = { showNfcDialog = true },
                            onDelete = onDelete,
                            onShowQr = { showQrDialog = true },
                            isPhone = isPhone,
                            canManagePhoto = rememberProfilePhotosUploadEnabled(viewModel),
                            onUploadPhoto = { bytes -> viewModel?.uploadProfilePhotoForVolunteer(volunteer, bytes) },
                            onRemovePhoto = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
                        )
                    }
                }
            }
        }
    }

    // Email confirmation dialog state
    var showEmailConfirmDialog by remember { mutableStateOf(false) }
    var showNoEmailDialog by remember { mutableStateOf(false) }

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
                        text = getStringResource(R.string.volunteer_qr_code),
                        style = if (isPhone) getPhonePortraitTypography() else getTabletConstrainedTitleTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    val payload = remember(volunteer) {
                        println("🔍 Generating QR code for volunteer: ${volunteer.name} (ID: ${volunteer.id})")
                        volunteer.id
                    }
                    val qrImage = remember(payload) { QRCodeUtils.generateQrImageBitmap(payload, 1024) }
                    val qrContext = LocalContext.current
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (qrImage != null) {
                            Image(
                                bitmap = qrImage,
                                contentDescription = getStringResource(R.string.volunteer_qr_code),
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
                                text = volunteer.name,
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
                                        qrImage?.let { bitmap ->
                                            runCatching {
                                                sharePngImage(
                                                    context = qrContext,
                                                    image = bitmap,
                                                    fileName = "qr_code_${volunteer.id}.png",
                                                    chooserTitle = qrContext.getString(R.string.share_qr_code),
                                                    subject = qrContext.getString(R.string.qr_code_subject, volunteer.name),
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
                                    onClick = {
                                        // Check if volunteer has email
                                        if (volunteer.email.isNotBlank()) {
                                            showEmailConfirmDialog = true
                                        } else {
                                            showNoEmailDialog = true
                                        }
                                    },
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
    
    // Email Confirmation Dialog
    if (showEmailConfirmDialog) {
        val emailContext = LocalContext.current
        val emailPanelSettingsManager = remember { settingsManagerFor(emailContext) }
        val gmailAuthService = remember { GmailAuthService(emailContext) }
        val gmailSendService = remember { GmailSendService(emailContext) }
        val coroutineScope = rememberCoroutineScope()
        val isGmailAuthenticated = remember { gmailAuthService.isAccountSelected() }
        
        // Holder for authLauncher - needed to break circular dependency
        val authLauncherHolder = remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<Intent>?>(null) }
        
        // State for showing loading during email send
        var isSendingEmail by remember { mutableStateOf(false) }
        
        // API email send function - Multipart/related via Gmail API
        // Defined before authLauncher to avoid forward reference
        suspend fun sendEmailViaApi(
            emailContext: android.content.Context,
            settingsManager: SettingsManager,
            gmailAuthService: GmailAuthService,
            gmailSendService: GmailSendService,
            volunteer: Volunteer,
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
                
                // Get email settings
                val subject = settingsManager.getEmailSubject().ifEmpty { 
                    emailContext.getString(R.string.email_subject_default) 
                }
                val contentBefore = settingsManager.getEmailContentBefore().ifEmpty { 
                    emailContext.getString(R.string.email_content_before_default) 
                }
                val includeQr = settingsManager.isEmailIncludeQrEnabled()
                val contentAfter = settingsManager.getEmailContentAfter().ifEmpty { 
                    emailContext.getString(R.string.email_content_after_default) 
                }
                val signature = settingsManager.getEmailSignature().ifEmpty { 
                    emailContext.getString(R.string.email_signature_default) 
                }
                val includeDigitalWalletPass = settingsManager.isEmailIncludeDigitalWalletPassEnabled()
                val includeLogo = settingsManager.isEmailIncludeLogoEnabled()
                val logoUriString = settingsManager.getEmailLogoUri()
                val associationName = settingsManager.getEmailAssociationName()
                
                // Generate QR code
                val qrBitmap = QRCodeUtils.generateQrImageBitmap(volunteer.id, 512)
                
                // Save QR code file
                var qrFile: File? = null
                if (includeQr && qrBitmap != null) {
                    qrFile = File(emailContext.cacheDir, "qr_code_${volunteer.id}.png")
                    val outputStream = FileOutputStream(qrFile)
                    qrBitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                }

                val digitalWalletPassFile = if (includeDigitalWalletPass) {
                    DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${volunteer.id}-${System.currentTimeMillis()}",
                        holderName = volunteer.name,
                        qrPayload = volunteer.id,
                        logoUriString = logoUriString,
                        associationName = associationName
                    ) ?: DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${volunteer.id}-${System.currentTimeMillis()}",
                        holderName = volunteer.name,
                        qrPayload = volunteer.id,
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
                            logoFile = File(emailContext.cacheDir, "logo_${volunteer.id}.png")
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
                val htmlEmail = buildProfessionalEmailHtml(
                    volunteerName = volunteer.name,
                    contentBefore = contentBefore,
                    contentAfter = contentAfter,
                    signature = signature,
                    includeQr = includeQr,
                    headerText = emailContext.getString(R.string.email_html_header),
                    footerText = emailContext.getString(R.string.email_html_footer),
                    qrAttachmentText = emailContext.getString(R.string.email_qr_attachment_text),
                    qrAttachmentNote = emailContext.getString(R.string.email_qr_attachment_note),
                    includeDigitalWalletPass = includeDigitalWalletPass,
                    digitalWalletPassTitle = emailContext.getString(R.string.email_wallet_section_title),
                    digitalWalletPassDescription = emailContext.getString(R.string.email_wallet_section_description),
                    digitalWalletPassCompatibility = emailContext.getString(R.string.email_wallet_section_compatibility),
                    includeLogo = includeLogo,
                    useContentId = true
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
                
                // Send via Gmail API
                val result = gmailSendService.sendEmail(
                    gmailService = gmailService,
                    to = volunteer.email,
                    subject = subject,
                    htmlContent = htmlEmail,
                    plainText = plainTextEmail,
                    qrFile = qrFile,
                    logoFile = logoFile,
                    digitalWalletPassFile = digitalWalletPassFile
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
                            // Launch authorization dialog - keep the dialog open so the launcher remains accessible
                            android.util.Log.d("GmailAuth", "Launching OAuth consent screen")
                            android.util.Log.d("GmailAuth", "Auth intent: ${e.authIntent}")
                            val launcher = authLauncherHolder.value
                            android.util.Log.d("GmailAuth", "Launcher is null: ${launcher == null}")
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
                        } else if (e is com.eventmanager.app.data.sync.GmailNotConfiguredException) {
                            // Gmail API not configured - show user-friendly message
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
                    sendEmailViaApi(
                        emailContext,
                        emailPanelSettingsManager,
                        gmailAuthService,
                        gmailSendService,
                        volunteer,
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
        
        // Manual email send function - Simple HTML with QR code attachment
        fun sendEmailManually(
            emailContext: android.content.Context,
            settingsManager: SettingsManager,
            volunteer: Volunteer
        ) {
            try {
                // Get email settings
                val subject = settingsManager.getEmailSubject().ifEmpty { 
                    emailContext.getString(R.string.email_subject_default) 
                }
                val contentBefore = settingsManager.getEmailContentBefore().ifEmpty { 
                    emailContext.getString(R.string.email_content_before_default) 
                }
                val includeQr = settingsManager.isEmailIncludeQrEnabled()
                val contentAfter = settingsManager.getEmailContentAfter().ifEmpty { 
                    emailContext.getString(R.string.email_content_after_default) 
                }
                val signature = settingsManager.getEmailSignature().ifEmpty { 
                    emailContext.getString(R.string.email_signature_default) 
                }
                val includeDigitalWalletPass = settingsManager.isEmailIncludeDigitalWalletPassEnabled()
                val logoUriString = settingsManager.getEmailLogoUri()
                val associationName = settingsManager.getEmailAssociationName()
                
                // Generate QR code
                val qrBitmap = QRCodeUtils.generateQrImageBitmap(volunteer.id, 512)
                
                // Build simple HTML email (without Content-ID, just plain HTML)
                val htmlEmail = buildProfessionalEmailHtml(
                    volunteerName = volunteer.name,
                    contentBefore = contentBefore,
                    contentAfter = contentAfter,
                    signature = signature,
                    includeQr = false, // Don't include QR in HTML for manual send
                    headerText = emailContext.getString(R.string.email_html_header),
                    footerText = emailContext.getString(R.string.email_html_footer),
                    qrAttachmentText = emailContext.getString(R.string.email_qr_attachment_text),
                    qrAttachmentNote = emailContext.getString(R.string.email_qr_attachment_note),
                    includeDigitalWalletPass = false,
                    digitalWalletPassTitle = "",
                    digitalWalletPassDescription = "",
                    digitalWalletPassCompatibility = "",
                    includeLogo = false, // Don't include logo in HTML for manual send
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
                    val qrFile = File(emailContext.cacheDir, "qr_code_${volunteer.id}.png")
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
                        serialNumber = "${volunteer.id}-${System.currentTimeMillis()}",
                        holderName = volunteer.name,
                        qrPayload = volunteer.id,
                        logoUriString = logoUriString,
                        associationName = associationName
                    ) ?: DigitalWalletPassGenerator.createPassFile(
                        context = emailContext,
                        serialNumber = "${volunteer.id}-${System.currentTimeMillis()}",
                        holderName = volunteer.name,
                        qrPayload = volunteer.id,
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
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(volunteer.email))
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
                        text = getStringResource(R.string.email_confirm_send_message, volunteer.email),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    HorizontalDivider()
                    
                    // Manual Send Option
                    OutlinedButton(
                        onClick = {
                            showEmailConfirmDialog = false
                            sendEmailManually(emailContext, emailPanelSettingsManager, volunteer)
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
                    
                    // API Send Option (only if authenticated)
                    if (isGmailAuthenticated) {
                        Button(
                            onClick = {
                                // Don't dismiss dialog immediately - wait for success or auth required
                                isSendingEmail = true
                                coroutineScope.launch {
                                    sendEmailViaApi(
                                        emailContext,
                                        emailPanelSettingsManager,
                                        gmailAuthService,
                                        gmailSendService,
                                        volunteer,
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
    
    // No Email Dialog
    if (showNoEmailDialog) {
        AlertDialog(
            onDismissRequest = { showNoEmailDialog = false },
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
                    text = getStringResource(R.string.email_no_email_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoEmailDialog = false }) {
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
                onAssignNfcUid(
                    volunteer,
                    uid
                )
                showNfcDialog = false
            }
        )
    }
}

@Composable
private fun PersonalInformationSection(
    volunteer: Volunteer,
    age: Int?,
    isPhone: Boolean
) {
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    
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
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(if (isPhone) 20.dp else 24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = getStringResource(R.string.personal_information),
                    style = if (isPhone) getPhonePortraitTypography() else getResponsiveTitleTypography(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
            
            // Name and abbreviation
            InfoRow(
                label = getStringResource(R.string.full_name),
                value = volunteer.name,
                isPhone = isPhone
            )
            
            InfoRow(
                label = getStringResource(R.string.abbreviation),
                value = volunteer.lastNameAbbreviation,
                isPhone = isPhone
            )
            
            // Gender
            InfoRow(
                label = getStringResource(R.string.gender),
                value = volunteer.gender?.let { gender ->
                    when (gender) {
                        Gender.FEMALE -> getStringResource(R.string.female)
                        Gender.MALE -> getStringResource(R.string.male)
                        Gender.NON_BINARY -> getStringResource(R.string.non_binary)
                        Gender.OTHER -> getStringResource(R.string.other)
                        Gender.PREFER_NOT_TO_DISCLOSE -> getStringResource(R.string.prefer_not_to_disclose)
                    }
                } ?: getStringResource(R.string.not_specified),
                isPhone = isPhone
            )
            
            // Birthday and age
            if (volunteer.dateOfBirth.isNotEmpty()) {
                InfoRow(
                    label = getStringResource(R.string.birthday),
                    value = formatBirthday(volunteer.dateOfBirth, LocalContext.current),
                    isPhone = isPhone
                )
                
                age?.let { calculatedAge ->
                    InfoRow(
                        label = getStringResource(R.string.age),
                        value = getStringResource(R.string.years_old, calculatedAge),
                        isPhone = isPhone
                    )
                }
            }
            
            // Contact information
            InfoRow(
                label = getStringResource(R.string.email),
                value = volunteer.email,
                isPhone = isPhone
            )
            
            InfoRow(
                label = getStringResource(R.string.phone),
                value = volunteer.phoneNumber,
                isPhone = isPhone
            )
            
            // NanoID - unique identifier with copy functionality
            NanoIdInfoRow(
                label = "NanoID",
                value = volunteer.id,
                isPhone = isPhone
            )

            NfcUidInfoRow(
                uid = volunteer.nfcCardUid,
                isPhone = isPhone
            )
        }
    }
}

@Composable
private fun VolunteerSpecificSection(
    volunteer: Volunteer,
    isActive: Boolean,
    activityStatusText: String,
    totalShifts: Int,
    lastShiftDate: Long?,
    isPhone: Boolean,
    currentRank: VolunteerRank?
) {
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    
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
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(if (isPhone) 20.dp else 24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = getStringResource(R.string.volunteer_information),
                    style = if (isPhone) getPhonePortraitTypography() else getResponsiveTitleTypography(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
            
            // Current rank
            InfoRow(
                label = getStringResource(R.string.current_rank),
                value = currentRank?.let { getRankDisplayName(it) } ?: getStringResource(R.string.no_rank),
                isPhone = isPhone
            )
            
            // Activity status with color indicator
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = getStringResource(R.string.status),
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                        )
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = activityStatusText,
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    color = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    fontWeight = FontWeight.Medium
                )
            }
            
            // Total shifts
            InfoRow(
                label = getStringResource(R.string.total_shifts),
                value = totalShifts.toString(),
                isPhone = isPhone
            )
            
            // Last shift date
            lastShiftDate?.let { lastShift ->
                InfoRow(
                    label = getStringResource(R.string.last_shift),
                    value = formatDateTime(lastShift, LocalContext.current),
                    isPhone = isPhone
                )
            }
        }
    }
}

@Composable
private fun ShiftHistorySection(
    jobs: List<Job>,
    isPhone: Boolean,
    venues: List<VenueEntity>,
    jobTypeConfigs: List<JobTypeConfig>
) {
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    
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
                    imageVector = Icons.Default.Work,
                    contentDescription = null,
                    modifier = Modifier.size(if (isPhone) 20.dp else 24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = getStringResource(R.string.shift_history, jobs.size),
                    style = if (isPhone) getPhonePortraitTypography() else getResponsiveTitleTypography(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
            
            if (jobs.isEmpty()) {
                Text(
                    text = getStringResource(R.string.no_shifts_recorded),
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            } else {
                // Show last 10 shifts to avoid overwhelming the UI
                val recentJobs = jobs.take(10)
                
                recentJobs.forEach { job ->
                    ShiftHistoryItem(
                        job = job,
                        isPhone = isPhone,
                        venues = venues,
                        jobTypeConfigs = jobTypeConfigs
                    )
                    
                    if (job != recentJobs.last()) {
                        Spacer(modifier = Modifier.height(if (isPhone) 4.dp else 6.dp))
                    }
                }
                
                if (jobs.size > 10) {
                    Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 12.dp))
                    Text(
                        text = getStringResource(R.string.more_shifts, jobs.size - 10),
                        style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
    }
}

@Composable
private fun ShiftHistoryItem(
    job: Job,
    isPhone: Boolean,
    venues: List<VenueEntity>,
    jobTypeConfigs: List<JobTypeConfig>
) {
    val context = LocalContext.current
    val shiftTimeLabel = context.shiftTimeLabelIfRelevant(job, jobTypeConfigs)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (isPhone) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = job.jobTypeName,
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = formatDate(job.date, context),
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(if (isPhone) 2.dp else 4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = getVenueDisplayString(job.venueName, venues),
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (shiftTimeLabel != null) {
                    Text(
                        text = shiftTimeLabel,
                        style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (job.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(if (isPhone) 4.dp else 6.dp))
                Text(
                    text = job.notes,
                    style = if (isPhone) getPhonePortraitBodyTypography() else getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsSection(
    volunteer: Volunteer,
    onEdit: (Volunteer) -> Unit,
    onAddNfcCard: () -> Unit,
    onDelete: (Volunteer) -> Unit,
    onShowQr: (Volunteer) -> Unit,
    isPhone: Boolean,
    canManagePhoto: Boolean = false,
    onUploadPhoto: (ByteArray) -> Unit = {},
    onRemovePhoto: () -> Unit = {},
) {
    val responsivePadding = if (isPhone) getPhonePortraitCardPadding() else getResponsiveCardPadding()
    val responsiveSpacing = if (isPhone) getPhonePortraitSpacing() else getResponsiveSpacing()
    
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
                    text = getStringResource(R.string.actions),
                    style = if (isPhone) getPhonePortraitTypography() else getResponsiveTitleTypography(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Spacer(modifier = Modifier.height(responsiveSpacing))

            // Full-width stack: horizontal row squeezed four labels on tablet admin detail.
            Column(
                verticalArrangement = Arrangement.spacedBy(if (isPhone) 8.dp else 12.dp)
            ) {
                OutlinedButton(
                    onClick = { onShowQr(volunteer) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(getStringResource(R.string.qr_code))
                }

                OutlinedButton(
                    onClick = onAddNfcCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(getStringResource(R.string.add_nfc_card))
                }

                val pickPhoto = rememberProfilePhotoPicker(onUploadPhoto)
                ProfilePhotoActionButtons(
                    hasPhoto = volunteer.hasStoredProfilePhoto(),
                    enabled = canManagePhoto,
                    onAddOrChange = pickPhoto,
                    onRemove = onRemovePhoto,
                )

                OutlinedButton(
                    onClick = { onEdit(volunteer) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(getStringResource(R.string.edit_volunteer_button))
                }

                OutlinedButton(
                    onClick = { onDelete(volunteer) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(getStringResource(R.string.delete_volunteer_button))
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
                    // Hide check icon after 2 seconds
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

private fun calculateAge(dateOfBirth: String): Int? {
    if (dateOfBirth.isEmpty()) return null
    
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val birthDate = format.parse(dateOfBirth) ?: return null
        val today = Calendar.getInstance()
        val birthCalendar = Calendar.getInstance().apply { time = birthDate }
        
        var age = today.get(Calendar.YEAR) - birthCalendar.get(Calendar.YEAR)
        
        // Check if birthday hasn't occurred this year
        if (today.get(Calendar.DAY_OF_YEAR) < birthCalendar.get(Calendar.DAY_OF_YEAR)) {
            age--
        }
        
        age
    } catch (e: Exception) {
        null
    }
}

private fun formatBirthday(dateOfBirth: String, context: android.content.Context): String {
    if (dateOfBirth.isEmpty()) return "Not provided"
    
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val date = inputFormat.parse(dateOfBirth) ?: return dateOfBirth
        formatDate(date.time, context)
    } catch (e: Exception) {
        dateOfBirth // Return original if parsing fails
    }
}

/**
 * Builds a professional HTML email with embedded QR code
 */
private fun buildProfessionalEmailHtml(
    volunteerName: String,
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
                            <div style="background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 24px; border-radius: 12px; margin-bottom: 20px;">
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
                                $volunteerName
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
                            <td style="background: linear-gradient(135deg, #4f46e5 0%, #7c3aed 50%, #a855f7 100%); padding: 48px 40px; text-align: center; position: relative; overflow: hidden;">
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

/**
 * Builds a multipart/related email with Content-ID references for images
 */
private fun buildMultipartEmail(
    to: String,
    subject: String,
    plainText: String,
    htmlContent: String,
    qrFile: File?,
    logoFile: File?,
    boundary: String
): String {
    val sb = StringBuilder()
    
    // Email headers
    sb.append("MIME-Version: 1.0\r\n")
    sb.append("To: $to\r\n")
    sb.append("Subject: $subject\r\n")
    sb.append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n")
    sb.append("\r\n")
    
    // Plain text alternative
    sb.append("--$boundary\r\n")
    sb.append("Content-Type: multipart/alternative; boundary=\"${boundary}_alt\"\r\n")
    sb.append("\r\n")
    
    // Plain text part
    sb.append("--${boundary}_alt\r\n")
    sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
    sb.append("Content-Transfer-Encoding: 8bit\r\n")
    sb.append("\r\n")
    sb.append(plainText)
    sb.append("\r\n")
    
    // HTML part
    sb.append("--${boundary}_alt\r\n")
    sb.append("Content-Type: text/html; charset=UTF-8\r\n")
    sb.append("Content-Transfer-Encoding: 8bit\r\n")
    sb.append("\r\n")
    sb.append(htmlContent)
    sb.append("\r\n")
    
    sb.append("--${boundary}_alt--\r\n")
    sb.append("\r\n")
    
    // QR Code - First as inline with Content-ID for HTML display
    if (qrFile != null && qrFile.exists()) {
        val qrBytes = qrFile.readBytes()
        val qrBase64 = Base64.encodeToString(qrBytes, Base64.NO_WRAP)
        
        // Inline version for HTML display (with Content-ID)
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: image/png; name=\"qr_code.png\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("Content-Disposition: inline; filename=\"qr_code.png\"\r\n")
        sb.append("Content-ID: <qrcode>\r\n")
        sb.append("\r\n")
        // Split into 76-character lines (RFC 2045)
        qrBase64.chunked(76).forEach { line ->
            sb.append(line)
            sb.append("\r\n")
        }
        sb.append("\r\n")
        
        // Attachment version for download (as actual attachment)
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: image/png; name=\"qr_code.png\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("Content-Disposition: attachment; filename=\"qr_code.png\"\r\n")
        sb.append("\r\n")
        // Split into 76-character lines (RFC 2045)
        qrBase64.chunked(76).forEach { line ->
            sb.append(line)
            sb.append("\r\n")
        }
        sb.append("\r\n")
    }
    
    // Logo attachment with Content-ID
    if (logoFile != null && logoFile.exists()) {
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: image/png; name=\"logo.png\"\r\n")
        sb.append("Content-Transfer-Encoding: base64\r\n")
        sb.append("Content-Disposition: inline; filename=\"logo.png\"\r\n")
        sb.append("Content-ID: <logo>\r\n")
        sb.append("\r\n")
        
        val logoBytes = logoFile.readBytes()
        val logoBase64 = Base64.encodeToString(logoBytes, Base64.NO_WRAP)
        // Split into 76-character lines (RFC 2045)
        logoBase64.chunked(76).forEach { line ->
            sb.append(line)
            sb.append("\r\n")
        }
        sb.append("\r\n")
    }
    
    // Close boundary
    sb.append("--$boundary--\r\n")
    
    return sb.toString()
}
