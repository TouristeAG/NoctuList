package com.eventmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.VolunteerRank
import com.eventmanager.app.data.sync.DateFormatUtils
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.createGmailAuth
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.platform.showPlatformToast
import com.eventmanager.app.ui.util.shiftTimeLabelIfRelevant
import com.eventmanager.app.ui.utils.getVenueDisplayString
import com.eventmanager.app.utils.QRCodeUtils
import com.eventmanager.app.email.QrEmailProfile
import com.eventmanager.app.email.QrEmailRecipientCode
import com.eventmanager.app.data.sync.DesktopQrEmailService
import com.eventmanager.app.data.sync.DesktopQrEmailTemplateStrings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.text.SimpleDateFormat
import java.util.*

typealias DesktopQrEmailProfile = QrEmailProfile

@Composable
fun DesktopSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun DesktopDetailField(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DesktopInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun DesktopNanoIdRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(value))
                    copied = true
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        copied = false
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun desktopGenderLabel(gender: Gender?): String = when (gender) {
    Gender.FEMALE -> stringResource(Res.string.female)
    Gender.MALE -> stringResource(Res.string.male)
    Gender.NON_BINARY -> stringResource(Res.string.non_binary)
    Gender.OTHER -> stringResource(Res.string.other)
    Gender.PREFER_NOT_TO_DISCLOSE -> stringResource(Res.string.prefer_not_to_disclose)
    null -> stringResource(Res.string.not_specified)
}

@Composable
fun desktopRankDisplayName(rank: VolunteerRank?): String = when (rank) {
    VolunteerRank.SPECIAL -> "✨SPECIAL✨"
    else -> rank?.name ?: stringResource(Res.string.no_rank)
}

fun desktopCalculateAge(dateOfBirth: String): Int? {
    if (dateOfBirth.isBlank()) return null
    return runCatching {
        val birthDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateOfBirth) ?: return null
        val today = Calendar.getInstance()
        val birth = Calendar.getInstance().apply { time = birthDate }
        var age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        if (today.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) age--
        age
    }.getOrNull()
}

fun desktopFormatBirthday(dateOfBirth: String, platformContext: PlatformContext): String {
    if (dateOfBirth.isBlank()) return ""
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateOfBirth) ?: return dateOfBirth
        DateFormatUtils.formatDate(parsed.time, platformContext)
    }.getOrElse { dateOfBirth }
}

@Composable
fun DesktopShiftHistorySection(
    jobs: List<Job>,
    venues: List<VenueEntity>,
    jobTypeConfigs: List<JobTypeConfig>,
    platformContext: PlatformContext
) {
    val sorted = remember(jobs) { jobs.sortedByDescending { it.date } }
    DesktopSectionCard(
        title = stringResource(Res.string.shift_history, sorted.size),
        icon = Icons.Default.Work
    ) {
        if (sorted.isEmpty()) {
            Text(
                stringResource(Res.string.no_shifts_recorded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        } else {
            sorted.take(10).forEach { job ->
                val shiftTime = shiftTimeLabelIfRelevant(job, jobTypeConfigs)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(job.jobTypeName, fontWeight = FontWeight.Medium)
                            Text(
                                DateFormatUtils.formatDate(job.date, platformContext),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                getVenueDisplayString(job.venueName, venues),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            shiftTime?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (job.notes.isNotBlank()) {
                            Text(
                                job.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        }
                    }
                }
            }
            if (sorted.size > 10) {
                Text(
                    stringResource(Res.string.more_shifts, sorted.size - 10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
    }
}

@Composable
fun DesktopQrCodeDialog(
    title: String,
    displayName: String,
    qrPayload: String,
    onDismiss: () -> Unit,
    onRequestSendEmail: () -> Unit,
    staffSafeMode: Boolean = false
) {
    val qrImage = remember(qrPayload, staffSafeMode) {
        if (staffSafeMode) {
            QRCodeUtils.generateStaffObfuscatedQrImageBitmap(qrPayload, 512)
        } else {
            QRCodeUtils.generateQrImageBitmap(qrPayload, 512)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (qrImage != null) {
                    Image(
                        bitmap = qrImage,
                        contentDescription = if (staffSafeMode) null else title,
                        modifier = Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .then(if (staffSafeMode) Modifier.blur(24.dp) else Modifier)
                    )
                    Text(displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!staffSafeMode) {
                        OutlinedButton(onClick = onRequestSendEmail, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.send_by_mail))
                        }
                    } else {
                        Button(onClick = onRequestSendEmail, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.email_send_api))
                        }
                    }
                } else {
                    Text(
                        stringResource(Res.string.failed_to_generate_qr_code),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
                }
            }
        }
    }
}

@Composable
fun DesktopGuestEmailInputDialog(
    emailValue: String,
    onEmailChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(Res.string.guest_email_input_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.guest_email_input_message))
                OutlinedTextField(
                    value = emailValue,
                    onValueChange = onEmailChange,
                    label = { Text(stringResource(Res.string.guest_email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                enabled = emailValue.isNotBlank() && emailValue.contains("@")
            ) { Text(stringResource(Res.string.continue_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } }
    )
}

@Composable
fun DesktopEmailConfirmDialog(
    profile: QrEmailProfile,
    recipientEmail: String,
    recipientName: String,
    qrPayload: String,
    settingsManager: SettingsManager,
    platformContext: PlatformContext,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
    staffSafeMode: Boolean = false,
    /** More than one entry turns the mail into a grouped artist guest list. */
    codes: List<QrEmailRecipientCode> = emptyList(),
) {
    val gmailAuth = remember(platformContext) { createGmailAuth(platformContext) }
    val emailService = remember(platformContext) { DesktopQrEmailService(platformContext) }
    var isSignedIn by remember { mutableStateOf(gmailAuth.isSignedIn) }
    var isSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val successMsg = stringResource(Res.string.email_api_success)
    val notAuthMsg = stringResource(Res.string.email_api_not_authenticated)
    val oauthMissingMsg = stringResource(Res.string.email_gmail_oauth_client_missing)
    val sendFailedMsg = stringResource(Res.string.email_api_error_message, "Unknown error")
    val manualFailedMsg = stringResource(Res.string.email_error_message, "Unknown error")
    val template = DesktopQrEmailTemplateStrings(
        volunteerHeader = stringResource(Res.string.email_html_header),
        guestHeader = stringResource(Res.string.guest_email_html_header),
        volunteerFooter = stringResource(Res.string.email_html_footer),
        guestFooter = stringResource(Res.string.guest_email_html_footer),
        volunteerSubjectDefault = stringResource(Res.string.email_subject_default),
        guestSubjectDefault = stringResource(Res.string.guest_email_subject_default),
        volunteerContentBeforeDefault = stringResource(Res.string.email_content_before_default),
        guestContentBeforeDefault = stringResource(Res.string.guest_email_content_before_default),
        volunteerContentAfterDefault = stringResource(Res.string.email_content_after_default),
        guestContentAfterDefault = stringResource(Res.string.guest_email_content_after_default),
        signatureDefault = stringResource(Res.string.email_signature_default),
        qrAttachmentText = stringResource(Res.string.email_qr_attachment_text),
        qrAttachmentNote = stringResource(Res.string.email_qr_attachment_note),
        walletPassTitle = stringResource(Res.string.email_wallet_section_title),
        walletPassDescription = stringResource(Res.string.email_wallet_section_description),
        walletPassCompatibility = stringResource(Res.string.email_wallet_section_compatibility),
        tempGuestHeader = stringResource(Res.string.temp_guest_email_html_header),
        tempGuestFooter = stringResource(Res.string.guest_email_html_footer),
        tempGuestSubjectDefault = stringResource(Res.string.temp_guest_email_subject_default),
        tempGuestContentBeforeDefault = stringResource(Res.string.temp_guest_email_content_before_default),
        tempGuestContentAfterDefault = stringResource(Res.string.temp_guest_email_content_after_default),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(Res.string.email_confirm_send_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.email_confirm_send_message, recipientEmail))
                    HorizontalDivider()

                    if (!staffSafeMode) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    emailService.sendManually(
                                    profile = profile,
                                    settingsManager = settingsManager,
                                    recipientEmail = recipientEmail,
                                    recipientName = recipientName,
                                    qrPayload = qrPayload,
                                    template = template,
                                    codes = codes,
                                )
                            }
                            if (ok) {
                                onSent()
                            } else {
                                showPlatformToast(platformContext, manualFailedMsg)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(Res.string.email_send_manual), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(Res.string.email_send_manual_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                }

                if (isSignedIn) {
                    Button(
                        onClick = {
                            scope.launch {
                                isSending = true
                                val sent = emailService.sendViaGmailApi(
                                    profile = profile,
                                    settingsManager = settingsManager,
                                    recipientEmail = recipientEmail,
                                    recipientName = recipientName,
                                    qrPayload = qrPayload,
                                    template = template,
                                    codes = codes,
                                )
                                isSending = false
                                if (sent) {
                                    showPlatformToast(platformContext, successMsg)
                                    onSent()
                                } else {
                                    showPlatformToast(platformContext, sendFailedMsg)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSending
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                            if (isSending) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (isSending) stringResource(Res.string.email_sending)
                                else stringResource(Res.string.email_send_api),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(Res.string.email_send_api_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Info, contentDescription = null)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                notAuthMsg,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isSending = true
                                val ok = gmailAuth.signIn()
                                isSignedIn = ok && gmailAuth.isSignedIn
                                isSending = false
                                if (!isSignedIn) {
                                    val message = when (gmailAuth.lastSignInError) {
                                        "missing_oauth_client" -> oauthMissingMsg
                                        null, "" -> notAuthMsg
                                        else -> gmailAuth.lastSignInError ?: notAuthMsg
                                    }
                                    showPlatformToast(platformContext, message)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSending
                    ) {
                        Text(stringResource(Res.string.email_gmail_sign_in))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } }
    )
}
