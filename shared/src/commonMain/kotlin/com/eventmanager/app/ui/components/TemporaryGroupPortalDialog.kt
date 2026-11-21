package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.email.PortalAccountBlock
import com.eventmanager.app.email.PortalAccountLabels
import com.eventmanager.app.portal.TemporaryGroupAccessResult
import com.eventmanager.app.portal.temporaryGroupMailAccount
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.temp_group_portal_joined
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.portal_mail_account_address
import com.eventmanager.app.resources.portal_mail_account_login
import com.eventmanager.app.resources.portal_mail_account_password
import com.eventmanager.app.resources.portal_mail_account_title
import com.eventmanager.app.resources.portal_mail_account_usual_password
import com.eventmanager.app.resources.portal_working
import com.eventmanager.app.resources.temp_group_portal_close
import com.eventmanager.app.resources.temp_group_portal_close_confirm
import com.eventmanager.app.resources.temp_group_portal_close_failed
import com.eventmanager.app.resources.temp_group_portal_existing
import com.eventmanager.app.resources.temp_group_portal_failed
import com.eventmanager.app.resources.temp_group_portal_no_email
import com.eventmanager.app.resources.temp_group_portal_open
import com.eventmanager.app.resources.temp_group_portal_opened_for
import com.eventmanager.app.resources.temp_group_portal_password_body
import com.eventmanager.app.resources.temp_group_portal_reopen
import com.eventmanager.app.resources.temp_group_portal_reused
import com.eventmanager.app.resources.temp_group_portal_send_mail
import com.eventmanager.app.resources.temp_group_portal_title
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Wording of the account block in the group mail, in the app language. */
@Composable
fun rememberPortalAccountLabels(): PortalAccountLabels = PortalAccountLabels(
    title = stringResource(Res.string.portal_mail_account_title),
    address = stringResource(Res.string.portal_mail_account_address),
    login = stringResource(Res.string.portal_mail_account_login),
    password = stringResource(Res.string.portal_mail_account_password),
    usualPassword = stringResource(Res.string.portal_mail_account_usual_password),
)

/** Group spaces are offered: portal on and temporary guest space on (Firebase only). */
@Composable
fun rememberTemporaryGroupPortalEnabled(viewModel: EventManagerViewModel?): Boolean {
    val fallback = remember { MutableStateFlow(false) }
    val spaceOn by (viewModel?.temporaryGuestPortalEnabled ?: fallback).collectAsState()
    return rememberVolunteerPortalEnabled(viewModel) && spaceOn
}

/** The artist address of the guest's group, as the space would be opened for it. */
private fun groupContactEmail(viewModel: EventManagerViewModel, guest: Guest): String =
    viewModel.temporaryGuestBatch(guest).map { it.temporaryContactEmail.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

/** What [TemporaryGroupPortalDialog] hands to the group mail: the address it opened and the password, if any. */
data class GroupMailHandover(val loginEmail: String, val password: String?)

/**
 * Account block for a group mail sent from the guest panel: only for the whole group, while its
 * space is open, to the address it was opened for. [handover] comes from [TemporaryGroupPortalDialog].
 */
fun EventManagerViewModel.groupMailPortalAccount(guest: Guest, recipient: String, handover: GroupMailHandover?): PortalAccountBlock? =
    temporaryGroupMailAccount(
        batch = temporaryGuestBatch(guest),
        recipient = recipient,
        portalUrl = volunteerPortalUrl(),
        password = handover?.password,
        spaceOn = isVolunteerPortalFeatureEnabled() && temporaryGuestPortalEnabled.value,
        knownLogin = handover?.loginEmail,
    )

/**
 * "Group space" block on a temporary guest: where the space stands and the admin's actions.
 * Nothing here sends an e-mail on its own.
 */
@Composable
fun TemporaryGroupPortalSection(
    viewModel: EventManagerViewModel,
    guest: Guest,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val openedFor = viewModel.temporaryGroupPortalEmail(guest)
    val contact = groupContactEmail(viewModel, guest)
    val openForContact = openedFor != null && openedFor.equals(contact, ignoreCase = true)
    var busy by remember(guest.nanoId) { mutableStateOf(false) }
    var closeFailed by remember(guest.nanoId) { mutableStateOf(false) }
    var confirmClose by remember(guest.nanoId) { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.temp_group_portal_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (openedFor != null) {
                Text(stringResource(Res.string.temp_group_portal_opened_for, openedFor), style = MaterialTheme.typography.bodyMedium)
            }
            if (!openForContact) {
                if (contact.isBlank()) {
                    Text(
                        stringResource(Res.string.temp_group_portal_no_email),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onOpen, enabled = !busy && contact.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.temp_group_portal_open))
                }
            }
            if (openedFor != null) {
                if (openForContact) {
                    PortalResetLinkButton(
                        email = openedFor,
                        send = { viewModel.sendTemporaryGroupPasswordReset(guest, portalMailLanguage(viewModel)) },
                        enabled = !busy,
                        onBusyChange = { busy = it },
                    )
                }
                OutlinedButton(
                    onClick = { confirmClose = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.temp_group_portal_close))
                }
            }
            if (openForContact && guest.portalUid.isNotBlank() && rememberGuestFormsEnabled(viewModel)) {
                HorizontalDivider()
                PortalInvitesPanel(viewModel, contact, guest.portalUid, guest.temporaryContactPhone)
            }
            if (closeFailed) {
                Text(
                    stringResource(Res.string.temp_group_portal_close_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(Res.string.temp_group_portal_close_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClose = false
                        busy = true
                        closeFailed = false
                        scope.launch {
                            closeFailed = !viewModel.closeTemporaryGroupPortal(guest)
                            busy = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.temp_group_portal_close)) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/**
 * Opens the group's web space and shows the outcome: the default password once, with Copy, and two
 * optional actions, "Send the group e-mail" (handed back through [onSendGroupMail] with the
 * opened address and the password, which is kept nowhere else) and a reset link. When the space is open for another
 * address than the group's current one, it offers to close it and open it again for the new one.
 */
@Composable
fun TemporaryGroupPortalDialog(
    viewModel: EventManagerViewModel,
    guest: Guest,
    onSendGroupMail: (GroupMailHandover) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val contact = remember(guest.nanoId) { groupContactEmail(viewModel, guest) }
    val openedFor = remember(guest.nanoId) { viewModel.temporaryGroupPortalEmail(guest) }
    val mismatch = openedFor != null && !openedFor.equals(contact, ignoreCase = true)
    var result by remember { mutableStateOf<TemporaryGroupAccessResult?>(null) }
    var working by remember { mutableStateOf(!mismatch) }

    LaunchedEffect(Unit) {
        if (mismatch) return@LaunchedEffect
        result = viewModel.openTemporaryGroupPortal(guest)
        working = false
    }

    val password = when (val r = result) {
        is TemporaryGroupAccessResult.Created -> r.password
        is TemporaryGroupAccessResult.Failed -> r.password
        else -> null
    }

    AlertDialog(
        // A password on screen is shown once: only the Close button dismisses it.
        onDismissRequest = { if (password == null && !working) onDismiss() },
        icon = { Icon(Icons.Default.Groups, contentDescription = null) },
        title = { Text(stringResource(Res.string.temp_group_portal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val current = result
                when {
                    working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(Res.string.portal_working), style = MaterialTheme.typography.bodySmall)
                    }
                    current == null && openedFor != null -> {
                        Text(stringResource(Res.string.temp_group_portal_opened_for, openedFor))
                        Button(
                            onClick = {
                                working = true
                                scope.launch {
                                    result = if (viewModel.closeTemporaryGroupPortal(guest)) {
                                        viewModel.openTemporaryGroupPortal(guest)
                                    } else {
                                        TemporaryGroupAccessResult.Failed("CLOSE_FAILED")
                                    }
                                    working = false
                                }
                            },
                            enabled = contact.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(Res.string.temp_group_portal_reopen, contact)) }
                    }
                    current != null -> GroupAccessOutcome(viewModel, guest, contact, current, onSendGroupMail)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(Res.string.close)) }
        },
    )
}

@Composable
private fun GroupAccessOutcome(
    viewModel: EventManagerViewModel,
    guest: Guest,
    contact: String,
    result: TemporaryGroupAccessResult,
    onSendGroupMail: (GroupMailHandover) -> Unit,
) {
    when (result) {
        is TemporaryGroupAccessResult.Created -> {
            Text(stringResource(Res.string.temp_group_portal_password_body, contact))
            OneTimePasswordBox(result.password)
        }
        is TemporaryGroupAccessResult.Reused -> Text(stringResource(Res.string.temp_group_portal_reused, contact))
        TemporaryGroupAccessResult.ExistingAccount -> Text(stringResource(Res.string.temp_group_portal_existing, contact))
        is TemporaryGroupAccessResult.JoinedAccount -> Text(stringResource(Res.string.temp_group_portal_joined))
        is TemporaryGroupAccessResult.Failed -> {
            Text(stringResource(Res.string.temp_group_portal_failed, result.code), color = MaterialTheme.colorScheme.error)
            // The account was created even though the group could not be saved: its password is needed once.
            result.password?.let {
                Text(stringResource(Res.string.temp_group_portal_password_body, contact))
                OneTimePasswordBox(it)
            }
        }
    }
    if (result is TemporaryGroupAccessResult.Failed) return
    Button(
        onClick = {
            // Same address as the one the space was opened for (TemporaryGuestPortalAccess cleans it the same way).
            onSendGroupMail(GroupMailHandover(contact.trim().lowercase(), (result as? TemporaryGroupAccessResult.Created)?.password))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.temp_group_portal_send_mail))
    }
    PortalResetLinkButton(
        email = contact,
        send = { viewModel.sendTemporaryGroupPasswordReset(guest, portalMailLanguage(viewModel)) },
        once = true,
    )
    PortalSignInAtLine(viewModel.volunteerPortalUrl())
}
