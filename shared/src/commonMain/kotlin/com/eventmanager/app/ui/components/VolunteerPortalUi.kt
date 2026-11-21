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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.DateFormatUtils
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.portal.DomainCheck
import com.eventmanager.app.portal.PortalGrantResult
import com.eventmanager.app.portal.PortalHolder
import com.eventmanager.app.portal.PortalHolderKind
import com.eventmanager.app.portal.asPortalHolder
import com.eventmanager.app.portal.isPermanentPortalGuest
import com.eventmanager.app.portal.PortalServerStatus
import com.eventmanager.app.portal.SignupFormState
import com.eventmanager.app.portal.VolunteerSignupForm
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.edit
import com.eventmanager.app.resources.remove
import com.eventmanager.app.resources.save
import com.eventmanager.app.resources.portal_access_google_title
import com.eventmanager.app.resources.portal_access_google_hint
import com.eventmanager.app.resources.portal_access_google_pending
import com.eventmanager.app.resources.portal_access_google_linked
import com.eventmanager.app.resources.portal_access_google_by_volunteer
import com.eventmanager.app.resources.portal_access_google_err_domain
import com.eventmanager.app.resources.portal_access_google_err_same_as_email
import com.eventmanager.app.resources.portal_access_google_err_taken
import com.eventmanager.app.resources.portal_access_google_err_invalid
import com.eventmanager.app.resources.portal_access_google_err_save
import com.eventmanager.app.resources.portal_access_google_remove_title
import com.eventmanager.app.resources.portal_access_google_remove_body
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
import com.eventmanager.app.resources.portal_google_error
import com.eventmanager.app.resources.portal_google_needs_domain
import com.eventmanager.app.resources.portal_show_logo
import com.eventmanager.app.resources.portal_show_logo_hint
import com.eventmanager.app.resources.volunteer_portal_qr_enabled
import com.eventmanager.app.resources.portal_google_not_found
import com.eventmanager.app.resources.portal_google_other
import com.eventmanager.app.resources.portal_google_title
import com.eventmanager.app.resources.portal_google_scope_all
import com.eventmanager.app.resources.portal_google_scope_internal
import com.eventmanager.app.resources.portal_access_google_title_any
import com.eventmanager.app.resources.portal_access_google_err_same_as_email_any
import com.eventmanager.app.resources.portal_password_body
import com.eventmanager.app.resources.portal_password_copied
import com.eventmanager.app.resources.portal_password_copy
import com.eventmanager.app.resources.portal_password_sign_in_at
import com.eventmanager.app.resources.portal_password_title
import com.eventmanager.app.resources.portal_signup_link_body
import com.eventmanager.app.resources.portal_signup_link_creating
import com.eventmanager.app.resources.portal_signup_link_done
import com.eventmanager.app.resources.portal_signup_link_expired
import com.eventmanager.app.resources.portal_signup_link_failed
import com.eventmanager.app.resources.portal_signup_link_received
import com.eventmanager.app.resources.portal_signup_link_title
import com.eventmanager.app.resources.portal_signup_link_waiting
import com.eventmanager.app.resources.portal_working
import com.eventmanager.app.resources.portal_guest_access_title
import com.eventmanager.app.resources.portal_guest_access_no_email
import com.eventmanager.app.resources.portal_guest_access_admin_note
import com.eventmanager.app.resources.portal_access_google_by_guest
import com.eventmanager.app.resources.portal_access_google_err_taken_guest
import com.eventmanager.app.resources.portal_access_google_err_same_as_email_guest
import com.eventmanager.app.resources.portal_guest_signup_body
import com.eventmanager.app.resources.portal_guest_signup_received
import com.eventmanager.app.resources.portal_guest_signup_done
import com.eventmanager.app.resources.portal_guest_signup_create
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

/** Date and time of the last automatic processing run, or null when none was seen. */
@Composable
internal fun portalServerLastRun(status: PortalServerStatus?): String? {
    val platformContext = LocalPlatformContext.current
    return status?.let { DateFormatUtils.formatDateTime(it.lastRunAt, platformContext) }
}

/**
 * Messages for the outcome of a Google Workspace domain check: null when the domain was verified
 * ([DomainCheck.GOOGLE]), otherwise the error to show. The strings are resolved here so the
 * mapping can run inside a coroutine. Shared by the old portal card and the Fonctions en ligne page.
 */
@Composable
internal fun domainCheckMessages(): (DomainCheck) -> String? {
    val other = stringResource(Res.string.portal_google_other)
    val notFound = stringResource(Res.string.portal_google_not_found)
    val error = stringResource(Res.string.portal_google_error)
    return { check ->
        when (check) {
            DomainCheck.GOOGLE -> null
            DomainCheck.OTHER -> other
            DomainCheck.NOT_FOUND -> notFound
            DomainCheck.ERROR -> error
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

/** Texts of a registration link dialog that name the kind of person being added. */
private data class SignupLinkTexts(val body: String, val received: String, val done: String)

/** Creates a single-use registration link and waits for the answer (like the photo request). */
@Composable
fun VolunteerSignupLinkDialog(viewModel: EventManagerViewModel, onDismiss: () -> Unit) {
    SignupLinkDialog(
        viewModel = viewModel,
        texts = SignupLinkTexts(
            body = stringResource(Res.string.portal_signup_link_body),
            received = stringResource(Res.string.portal_signup_link_received),
            done = stringResource(Res.string.portal_signup_link_done),
        ),
        create = { viewModel.createVolunteerSignupForm() },
        onDismiss = onDismiss,
    )
}

/**
 * Registration link for a new permanent guest. The admin picks the venue first: the form carries
 * it and the public cannot change it.
 */
@Composable
fun GuestSignupLinkDialog(viewModel: EventManagerViewModel, venues: List<VenueEntity>, onDismiss: () -> Unit) {
    var venueName by remember { mutableStateOf<String?>(null) }
    var confirmedVenue by remember { mutableStateOf<String?>(null) }
    val chosen = confirmedVenue
    if (chosen == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.portal_signup_link_title)) },
            text = {
                GuestVenueDropdownField(
                    venues = venues,
                    selectedVenueName = venueName,
                    onVenueSelected = { venueName = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmedVenue = venueName }, enabled = !venueName.isNullOrBlank()) {
                    Text(stringResource(Res.string.portal_guest_signup_create))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        )
        return
    }
    SignupLinkDialog(
        viewModel = viewModel,
        texts = SignupLinkTexts(
            body = stringResource(Res.string.portal_guest_signup_body),
            received = stringResource(Res.string.portal_guest_signup_received),
            done = stringResource(Res.string.portal_guest_signup_done),
        ),
        create = { viewModel.createGuestSignupForm(chosen) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun SignupLinkDialog(
    viewModel: EventManagerViewModel,
    texts: SignupLinkTexts,
    create: suspend () -> VolunteerSignupForm?,
    onDismiss: () -> Unit,
) {
    var form by remember { mutableStateOf<VolunteerSignupForm?>(null) }
    var failed by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(SignupFormState.Waiting) }

    LaunchedEffect(Unit) {
        val created = create()
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
                    state == SignupFormState.Completed -> DoneMessage(texts.done)
                    state == SignupFormState.Expired -> Text(
                        stringResource(Res.string.portal_signup_link_expired),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    else -> {
                        Text(texts.body, style = MaterialTheme.typography.bodyMedium)
                        qr?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(200.dp)) }
                        if (url.isNotBlank()) CopyableUrlBox(url = url)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                if (state == SignupFormState.Submitted) texts.received
                                else stringResource(Res.string.portal_signup_link_waiting),
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

/**
 * The admin actions on one profile's portal access, bound to a volunteer or a permanent guest, so
 * the card and the password window serve both.
 */
class PortalAccessActions(
    val holder: PortalHolder,
    val name: String,
    val grant: suspend () -> PortalGrantResult,
    val sendReset: suspend () -> Boolean,
    val revoke: suspend () -> Boolean,
    val assignGoogle: suspend (String) -> String?,
    val removeGoogle: suspend () -> Boolean,
)

fun EventManagerViewModel.portalAccessActions(volunteer: Volunteer) = PortalAccessActions(
    holder = volunteer.asPortalHolder(),
    name = volunteer.name,
    grant = { grantVolunteerPortalAccess(volunteer) },
    sendReset = { sendVolunteerPortalPasswordReset(volunteer, portalMailLanguage(this)) },
    revoke = { revokeVolunteerPortalAccess(volunteer) },
    assignGoogle = { assignVolunteerPortalGoogleAccount(volunteer, it) },
    removeGoogle = { removeVolunteerPortalGoogleAccount(volunteer) },
)

fun EventManagerViewModel.portalAccessActions(guest: Guest) = PortalAccessActions(
    holder = guest.asPortalHolder(),
    name = guest.name,
    grant = { grantGuestPortalAccess(guest) },
    sendReset = { sendGuestPortalPasswordReset(guest, portalMailLanguage(this)) },
    revoke = { revokeGuestPortalAccess(guest) },
    assignGoogle = { assignGuestPortalGoogleAccount(guest, it) },
    removeGoogle = { removeGuestPortalGoogleAccount(guest) },
)

/** Card and Google texts that name a volunteer or a guest. */
private data class PortalHolderTexts(
    val title: String,
    val noEmail: String,
    val adminNote: String,
    val linkedBySelf: String,
    val googleTaken: String,
    val googleSameAsEmail: String,
)

@Composable
private fun portalHolderTexts(kind: PortalHolderKind): PortalHolderTexts = when (kind) {
    PortalHolderKind.VOLUNTEER -> PortalHolderTexts(
        title = stringResource(Res.string.portal_access_title),
        noEmail = stringResource(Res.string.portal_access_no_email),
        adminNote = stringResource(Res.string.portal_access_admin_note),
        linkedBySelf = stringResource(Res.string.portal_access_google_by_volunteer),
        googleTaken = stringResource(Res.string.portal_access_google_err_taken),
        googleSameAsEmail = stringResource(Res.string.portal_access_google_err_same_as_email),
    )
    PortalHolderKind.GUEST -> PortalHolderTexts(
        title = stringResource(Res.string.portal_guest_access_title),
        noEmail = stringResource(Res.string.portal_guest_access_no_email),
        adminNote = stringResource(Res.string.portal_guest_access_admin_note),
        linkedBySelf = stringResource(Res.string.portal_access_google_by_guest),
        googleTaken = stringResource(Res.string.portal_access_google_err_taken_guest),
        googleSameAsEmail = stringResource(Res.string.portal_access_google_err_same_as_email_guest),
    )
}

/** A one-time password with its Copy button. */
@Composable
internal fun OneTimePasswordBox(password: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(password) { mutableStateOf(false) }
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
                password,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(password))
                copied = true
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (copied) Res.string.portal_password_copied else Res.string.portal_password_copy))
            }
        }
    }
}

/**
 * "Send a password reset link", sent only on click, with its outcome below. [once] keeps it
 * disabled after a successful send (result windows); the card lets the admin send again.
 */
@Composable
internal fun PortalResetLinkButton(
    email: String,
    send: suspend () -> Boolean,
    enabled: Boolean = true,
    once: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Boolean?>(null) }
    val sentText = stringResource(Res.string.portal_access_reset_sent, email)
    val failedText = stringResource(Res.string.portal_access_reset_failed)
    OutlinedButton(
        onClick = {
            sending = true
            outcome = null
            scope.launch {
                outcome = send()
                sending = false
            }
        },
        enabled = enabled && !sending && email.isNotBlank() && !(once && outcome == true),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.portal_access_reset))
    }
    outcome?.let { ok ->
        Text(
            if (ok) sentText else failedText,
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

/** "Sign in at" line under a password, when the portal address is known. */
@Composable
internal fun PortalSignInAtLine(portalUrl: String) {
    if (portalUrl.isBlank()) return
    Text(
        stringResource(Res.string.portal_password_sign_in_at, portalUrl),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Shows the generated password exactly once, with copy and the portal address. */
@Composable
fun PortalGrantResultDialog(
    viewModel: EventManagerViewModel?,
    volunteer: Volunteer,
    result: PortalGrantResult,
    onDismiss: () -> Unit,
) {
    PortalGrantResultDialog(
        viewModel = viewModel,
        name = volunteer.name,
        email = volunteer.email,
        result = result,
        sendReset = viewModel?.let { vm -> { vm.sendVolunteerPortalPasswordReset(volunteer, portalMailLanguage(vm)) } },
        onDismiss = onDismiss,
    )
}

/** Same window for a permanent guest. */
@Composable
fun PortalGrantResultDialog(
    viewModel: EventManagerViewModel?,
    guest: Guest,
    result: PortalGrantResult,
    onDismiss: () -> Unit,
) {
    PortalGrantResultDialog(
        viewModel = viewModel,
        name = guest.name,
        email = guest.email,
        result = result,
        sendReset = viewModel?.let { vm -> { vm.sendGuestPortalPasswordReset(guest, portalMailLanguage(vm)) } },
        onDismiss = onDismiss,
    )
}

@Composable
private fun PortalGrantResultDialog(
    viewModel: EventManagerViewModel?,
    name: String,
    email: String,
    result: PortalGrantResult,
    sendReset: (suspend () -> Boolean)?,
    onDismiss: () -> Unit,
) {
    val portalUrl = viewModel?.volunteerPortalUrl().orEmpty()
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Key, contentDescription = null) },
        title = { Text(stringResource(Res.string.portal_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (result) {
                    is PortalGrantResult.Created -> {
                        Text(stringResource(Res.string.portal_password_body, name))
                        OneTimePasswordBox(result.password)
                    }
                    is PortalGrantResult.ExistingAccount -> Text(stringResource(Res.string.portal_access_existing, email))
                    is PortalGrantResult.Failed -> Text(
                        stringResource(Res.string.portal_access_failed, result.code),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result !is PortalGrantResult.Failed && sendReset != null && email.isNotBlank()) {
                    PortalResetLinkButton(email = email, send = sendReset, once = true)
                }
                if (result !is PortalGrantResult.Failed) PortalSignInAtLine(portalUrl)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/**
 * The access opened by `volunteer_portal_auto_access` for a guest just added: its password is
 * shown once, then forgotten.
 */
@Composable
fun GuestPortalAutoGrantHost(viewModel: EventManagerViewModel?) {
    if (viewModel == null) return
    val pending by viewModel.guestPortalAutoGrant.collectAsState()
    pending?.let { (guest, result) ->
        PortalGrantResultDialog(viewModel, guest, result, onDismiss = { viewModel.consumeGuestPortalAutoGrant() })
    }
}

/** "Portal access" card on a volunteer profile: status and admin actions. */
@Composable
fun VolunteerPortalAccessCard(
    viewModel: EventManagerViewModel?,
    volunteer: Volunteer,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null || !rememberVolunteerPortalEnabled(viewModel)) return
    PortalAccessCard(viewModel, viewModel.portalAccessActions(volunteer), modifier)
}

/** Same card on a permanent guest, while the guest space is on. */
@Composable
fun GuestPortalAccessCard(
    viewModel: EventManagerViewModel?,
    guest: Guest,
    modifier: Modifier = Modifier,
) {
    if (viewModel == null || !rememberVolunteerPortalEnabled(viewModel)) return
    val guestSpaceOn by viewModel.guestPortalEnabled.collectAsState()
    if (!guestSpaceOn || !guest.isPermanentPortalGuest) return
    PortalAccessCard(viewModel, viewModel.portalAccessActions(guest), modifier)
}

@Composable
private fun PortalAccessCard(
    viewModel: EventManagerViewModel,
    actions: PortalAccessActions,
    modifier: Modifier = Modifier,
) {
    val holder = actions.holder
    val texts = portalHolderTexts(holder.kind)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var grantResult by remember { mutableStateOf<PortalGrantResult?>(null) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val googleEnabled by viewModel.volunteerPortalGoogleEnabled.collectAsState()

    val (statusText, statusColor) = when {
        !holder.portalEnabled -> stringResource(Res.string.portal_access_status_none) to MaterialTheme.colorScheme.onSurfaceVariant
        holder.portalUid.isBlank() -> stringResource(Res.string.portal_access_status_pending) to MaterialTheme.colorScheme.tertiary
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
                Text(texts.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor, fontWeight = FontWeight.Medium)
            }
            if (holder.isAdmin && holder.portalEnabled) {
                Text(texts.adminNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (holder.portalEnabled && holder.portalUid.isNotBlank()) {
                Text(stringResource(Res.string.portal_access_email_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.portal_working), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!holder.portalEnabled) {
                if (holder.email.isBlank()) {
                    Text(texts.noEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            grantResult = actions.grant()
                            busy = false
                        }
                    },
                    enabled = !busy && holder.email.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.portal_access_create))
                }
            } else {
                PortalResetLinkButton(email = holder.email, send = actions.sendReset, enabled = !busy)
                if (googleEnabled) PortalGoogleAccountRow(viewModel, actions, texts)
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
        PortalGrantResultDialog(
            viewModel = viewModel,
            name = actions.name,
            email = holder.email,
            result = result,
            sendReset = actions.sendReset,
            onDismiss = { grantResult = null },
        )
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text(stringResource(Res.string.portal_access_revoke_confirm_title)) },
            text = { Text(stringResource(Res.string.portal_access_revoke_confirm_body, actions.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = false
                        busy = true
                        scope.launch {
                            actions.revoke()
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

/** Google account of a profile whose portal access is open (only when the option is on). */
@Composable
private fun PortalGoogleAccountRow(viewModel: EventManagerViewModel, actions: PortalAccessActions, texts: PortalHolderTexts) {
    val holder = actions.holder
    val scope = rememberCoroutineScope()
    val anyAccount by viewModel.volunteerPortalGoogleAnyAccount.collectAsState()
    var editing by remember(holder.kind, holder.id, holder.portalGoogleEmail) { mutableStateOf(holder.portalGoogleEmail.isBlank()) }
    var input by remember(holder.kind, holder.id, holder.portalGoogleEmail) { mutableStateOf(holder.portalGoogleEmail) }
    var errorCode by remember(holder.kind, holder.id) { mutableStateOf<String?>(null) }
    var busy by remember(holder.kind, holder.id) { mutableStateOf(false) }
    var confirmRemove by remember(holder.kind, holder.id) { mutableStateOf(false) }
    val hasAccount = holder.portalGoogleEmail.isNotBlank()
    val errorText = when (errorCode) {
        null -> null
        "DOMAIN" -> stringResource(Res.string.portal_access_google_err_domain)
        "TAKEN" -> texts.googleTaken
        "SAME_AS_EMAIL" -> if (anyAccount) stringResource(Res.string.portal_access_google_err_same_as_email_any) else texts.googleSameAsEmail
        "INVALID" -> stringResource(Res.string.portal_access_google_err_invalid)
        else -> stringResource(Res.string.portal_access_google_err_save)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(if (anyAccount) Res.string.portal_access_google_title_any else Res.string.portal_access_google_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        if (hasAccount && !editing) {
            Text(holder.portalGoogleEmail, style = MaterialTheme.typography.bodyMedium)
            val state = when {
                holder.portalGoogleUid.isBlank() -> stringResource(Res.string.portal_access_google_pending)
                holder.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_VOLUNTEER ->
                    stringResource(Res.string.portal_access_google_linked) + ", " + texts.linkedBySelf
                else -> stringResource(Res.string.portal_access_google_linked)
            }
            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { errorCode = null; editing = true }, enabled = !busy) {
                    Text(stringResource(Res.string.edit))
                }
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.remove)) }
            }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; errorCode = null },
                label = { Text(stringResource(Res.string.portal_access_google_hint)) },
                singleLine = true,
                isError = errorText != null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            errorCode = actions.assignGoogle(input)
                            if (errorCode == null) editing = false
                            busy = false
                        }
                    },
                    enabled = !busy && input.isNotBlank(),
                ) { Text(stringResource(Res.string.save)) }
                if (hasAccount) {
                    TextButton(
                        onClick = { input = holder.portalGoogleEmail; errorCode = null; editing = false },
                        enabled = !busy,
                    ) { Text(stringResource(Res.string.cancel)) }
                }
            }
        }
        errorText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(Res.string.portal_access_google_remove_title)) },
            text = { Text(stringResource(Res.string.portal_access_google_remove_body, actions.name, holder.portalGoogleEmail)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        busy = true
                        scope.launch {
                            if (!actions.removeGoogle()) errorCode = "SAVE_FAILED"
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** "Send a registration form" for a new permanent guest: portal, guest space and public site ready. */
@Composable
fun rememberGuestSignupLinkAvailable(viewModel: EventManagerViewModel?): Boolean {
    val portalOn = rememberVolunteerPortalEnabled(viewModel)
    val fallback = remember { MutableStateFlow(false) }
    val guestSpaceOn by (viewModel?.guestPortalEnabled ?: fallback).collectAsState()
    return portalOn && guestSpaceOn && viewModel?.isGuestSignupLinkAvailable() == true
}
