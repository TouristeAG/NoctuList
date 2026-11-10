package com.eventmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.PortalGrantResult
import com.eventmanager.app.portal.SignupFormState
import com.eventmanager.app.portal.VolunteerSignupForm
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.guest_form_copy_link
import com.eventmanager.app.resources.guest_form_link_copied
import com.eventmanager.app.resources.portal_access_admin_note
import com.eventmanager.app.resources.portal_access_create
import com.eventmanager.app.resources.portal_access_email_note
import com.eventmanager.app.resources.portal_access_existing
import com.eventmanager.app.resources.portal_access_failed
import com.eventmanager.app.resources.portal_access_no_email
import com.eventmanager.app.resources.portal_access_reset
import com.eventmanager.app.resources.portal_access_reset_failed
import com.eventmanager.app.resources.portal_access_reset_sent
import com.eventmanager.app.resources.portal_access_revoke
import com.eventmanager.app.resources.portal_access_revoke_confirm_body
import com.eventmanager.app.resources.portal_access_revoke_confirm_title
import com.eventmanager.app.resources.portal_access_status_active
import com.eventmanager.app.resources.portal_access_status_none
import com.eventmanager.app.resources.portal_access_status_pending
import com.eventmanager.app.resources.portal_access_title
import com.eventmanager.app.resources.portal_add_choice_fill
import com.eventmanager.app.resources.portal_add_choice_fill_desc
import com.eventmanager.app.resources.portal_add_choice_link
import com.eventmanager.app.resources.portal_add_choice_link_desc
import com.eventmanager.app.resources.portal_add_choice_title
import com.eventmanager.app.resources.portal_create_access_toggle
import com.eventmanager.app.resources.portal_create_access_toggle_desc
import com.eventmanager.app.resources.portal_enable
import com.eventmanager.app.resources.portal_password_body
import com.eventmanager.app.resources.portal_password_copied
import com.eventmanager.app.resources.portal_password_copy
import com.eventmanager.app.resources.portal_password_reset_sent
import com.eventmanager.app.resources.portal_password_sign_in_at
import com.eventmanager.app.resources.portal_password_title
import com.eventmanager.app.resources.portal_settings_body
import com.eventmanager.app.resources.portal_settings_title
import com.eventmanager.app.resources.portal_sign_in_url
import com.eventmanager.app.resources.portal_signup_link_body
import com.eventmanager.app.resources.portal_signup_link_creating
import com.eventmanager.app.resources.portal_signup_link_done
import com.eventmanager.app.resources.portal_signup_link_expired
import com.eventmanager.app.resources.portal_signup_link_failed
import com.eventmanager.app.resources.portal_signup_link_received
import com.eventmanager.app.resources.portal_signup_link_title
import com.eventmanager.app.resources.portal_signup_link_waiting
import com.eventmanager.app.resources.portal_working
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.utils.QRCodeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private const val SIGNUP_POLL_MS = 4_000L

/** Follows the org-wide volunteer portal flag (Firebase only). */
@Composable
fun rememberVolunteerPortalEnabled(viewModel: EventManagerViewModel?): Boolean {
    val fallback = remember { MutableStateFlow(false) }
    val enabled by (viewModel?.volunteerPortalEnabled ?: fallback).collectAsState()
    return enabled
}

/** Two-letter code for the Firebase e-mails (reset links) sent to volunteers. */
internal fun portalMailLanguage(viewModel: EventManagerViewModel?): String =
    viewModel?.currentLanguageCode()?.take(2)?.lowercase()?.takeIf { it in setOf("fr", "en", "es", "de", "it") } ?: "fr"

@Composable
fun VolunteerPortalSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    portalUrl: String,
    canEdit: Boolean,
) {
    GuidedStepCard(
        title = stringResource(Res.string.portal_settings_title),
        body = stringResource(Res.string.portal_settings_body),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(Res.string.portal_enable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = canEdit)
        }
        if (enabled && portalUrl.isNotBlank()) {
            Text(
                stringResource(Res.string.portal_sign_in_url, portalUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CopyableUrlBox(url = portalUrl)
        }
    }
}

/** "Fill in myself" or "send a registration form", shown when the portal is on. */
@Composable
fun AddVolunteerChoiceDialog(
    onFillIn: () -> Unit,
    onSendForm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.portal_add_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceRow(
                    icon = Icons.Default.EditNote,
                    title = stringResource(Res.string.portal_add_choice_fill),
                    body = stringResource(Res.string.portal_add_choice_fill_desc),
                    onClick = onFillIn,
                )
                ChoiceRow(
                    icon = Icons.Default.QrCode2,
                    title = stringResource(Res.string.portal_add_choice_link),
                    body = stringResource(Res.string.portal_add_choice_link_desc),
                    onClick = onSendForm,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Creates a single-use registration link and waits for the answer (like the photo request). */
@Composable
fun VolunteerSignupLinkDialog(viewModel: EventManagerViewModel, onDismiss: () -> Unit) {
    var form by remember { mutableStateOf<VolunteerSignupForm?>(null) }
    var failed by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(SignupFormState.Waiting) }

    LaunchedEffect(Unit) {
        val created = viewModel.createVolunteerSignupForm()
        if (created == null) failed = true else form = created
    }
    LaunchedEffect(form) {
        val current = form ?: return@LaunchedEffect
        while (true) {
            delay(SIGNUP_POLL_MS)
            state = viewModel.checkVolunteerSignupForm(current)
            if (state == SignupFormState.Completed || state == SignupFormState.Expired) return@LaunchedEffect
        }
    }

    val url = form?.let { viewModel.volunteerSignupUrl(it) }.orEmpty()
    val qr: ImageBitmap? = remember(url) { if (url.isBlank()) null else QRCodeUtils.generateQrImageBitmap(url, 420) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.portal_signup_link_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    failed -> Text(
                        stringResource(Res.string.portal_signup_link_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    form == null -> {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text(stringResource(Res.string.portal_signup_link_creating), style = MaterialTheme.typography.bodyMedium)
                    }
                    state == SignupFormState.Completed -> DoneMessage(stringResource(Res.string.portal_signup_link_done))
                    state == SignupFormState.Expired -> Text(
                        stringResource(Res.string.portal_signup_link_expired),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    else -> {
                        Text(stringResource(Res.string.portal_signup_link_body), style = MaterialTheme.typography.bodyMedium)
                        qr?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(200.dp)) }
                        if (url.isNotBlank()) CopyableUrlBox(url = url)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(
                                    if (state == SignupFormState.Submitted) Res.string.portal_signup_link_received
                                    else Res.string.portal_signup_link_waiting,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

@Composable
private fun DoneMessage(text: String) {
    Icon(
        Icons.Default.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp),
    )
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

/** Switch shown in "Add volunteer" while the portal is on. */
@Composable
fun PortalAccessToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(Res.string.portal_create_access_toggle), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.portal_create_access_toggle_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Shows the generated password exactly once, with copy and the portal address. */
@Composable
fun PortalGrantResultDialog(
    viewModel: EventManagerViewModel?,
    volunteer: Volunteer,
    result: PortalGrantResult,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val portalUrl = viewModel?.volunteerPortalUrl().orEmpty()
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(Res.string.portal_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (result) {
                    is PortalGrantResult.Created -> {
                        Text(stringResource(Res.string.portal_password_body, volunteer.name))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    result.password,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(result.password))
                                    copied = true
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(if (copied) Res.string.portal_password_copied else Res.string.portal_password_copy))
                                }
                            }
                        }
                        if (result.resetSent) {
                            Text(
                                stringResource(Res.string.portal_password_reset_sent, volunteer.email),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is PortalGrantResult.ExistingAccount -> Text(stringResource(Res.string.portal_access_existing, volunteer.email))
                    is PortalGrantResult.Failed -> Text(
                        stringResource(Res.string.portal_access_failed, result.code),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result !is PortalGrantResult.Failed && portalUrl.isNotBlank()) {
                    Text(
                        stringResource(Res.string.portal_password_sign_in_at, portalUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** "Volunteer portal" card on a volunteer profile: status and admin actions. */
@Composable
fun VolunteerPortalAccessCard(
    viewModel: EventManagerViewModel?,
    volunteer: Volunteer,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null || !rememberVolunteerPortalEnabled(viewModel)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var grantResult by remember { mutableStateOf<PortalGrantResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val resetSentText = stringResource(Res.string.portal_access_reset_sent, volunteer.email)
    val resetFailedText = stringResource(Res.string.portal_access_reset_failed)

    val (statusText, statusColor) = when {
        !volunteer.portalEnabled -> stringResource(Res.string.portal_access_status_none) to MaterialTheme.colorScheme.onSurfaceVariant
        volunteer.portalUid.isBlank() -> stringResource(Res.string.portal_access_status_pending) to MaterialTheme.colorScheme.tertiary
        else -> stringResource(Res.string.portal_access_status_active) to Color(0xFF2E7D4F)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.portal_access_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor, fontWeight = FontWeight.Medium)
            }
            if (volunteer.isAdmin && volunteer.portalEnabled) {
                Text(stringResource(Res.string.portal_access_admin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (volunteer.portalEnabled && volunteer.portalUid.isNotBlank()) {
                Text(stringResource(Res.string.portal_access_email_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.portal_working), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!volunteer.portalEnabled) {
                if (volunteer.email.isBlank()) {
                    Text(stringResource(Res.string.portal_access_no_email), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            grantResult = viewModel.grantVolunteerPortalAccess(volunteer, portalMailLanguage(viewModel))
                            busy = false
                        }
                    },
                    enabled = !busy && volunteer.email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.portal_access_create))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val ok = viewModel.sendVolunteerPortalPasswordReset(volunteer, portalMailLanguage(viewModel))
                            message = if (ok) resetSentText else resetFailedText
                            busy = false
                        }
                    },
                    enabled = !busy && volunteer.email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.portal_access_reset))
                }
                OutlinedButton(
                    onClick = { confirmRevoke = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.portal_access_revoke))
                }
            }
        }
    }

    grantResult?.let { result ->
        PortalGrantResultDialog(viewModel, volunteer, result, onDismiss = { grantResult = null })
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(Res.string.portal_access_revoke_confirm_title)) },
            text = { Text(stringResource(Res.string.portal_access_revoke_confirm_body, volunteer.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = false
                        busy = true
                        scope.launch {
                            viewModel.revokeVolunteerPortalAccess(volunteer)
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.portal_access_revoke)) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
