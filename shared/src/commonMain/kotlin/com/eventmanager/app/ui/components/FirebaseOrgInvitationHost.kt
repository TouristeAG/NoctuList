package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirebaseOrgInvitation
import com.eventmanager.app.data.remote.FirebaseOrgInvitationActions.JoinResult
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.sync_org_invite_body
import com.eventmanager.app.resources.sync_org_invite_decline
import com.eventmanager.app.resources.sync_org_invite_error_denied
import com.eventmanager.app.resources.sync_org_invite_error_other
import com.eventmanager.app.resources.sync_org_invite_error_sign_in
import com.eventmanager.app.resources.sync_org_invite_error_unavailable
import com.eventmanager.app.resources.sync_org_invite_join
import com.eventmanager.app.resources.sync_org_invite_joining
import com.eventmanager.app.resources.sync_org_invite_message_from
import com.eventmanager.app.resources.sync_org_invite_message_from_admin
import com.eventmanager.app.resources.sync_org_invite_title
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How often a device looks for a new org invitation (local read; a server check only when one is new). */
private const val INVITATION_CHECK_INTERVAL_MS = 30_000L

/**
 * Asks this device whether to join an org an admin added for every device of its org. Placed
 * once at the app root on desktop and Android.
 */
@Composable
fun FirebaseOrgInvitationHost(viewModel: EventManagerViewModel) {
    LaunchedEffect(viewModel) {
        while (true) {
            runCatching { viewModel.refreshPendingOrgInvitation() }
            delay(INVITATION_CHECK_INTERVAL_MS)
        }
    }
    val invitation by viewModel.pendingOrgInvitation.collectAsState()
    invitation?.let { current ->
        FirebaseOrgInvitationDialog(
            invitation = current,
            sourceLabel = viewModel.firebaseOrgLabel(current.sourceOrgId),
            onJoin = { viewModel.joinOrgFromInvitation(current) },
            onDecline = { viewModel.declineOrgInvitation(current) },
        )
    }
}

@Composable
fun FirebaseOrgInvitationDialog(
    invitation: FirebaseOrgInvitation,
    sourceLabel: String,
    onJoin: suspend () -> JoinResult,
    onDecline: () -> Unit,
    onJoined: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var busy by remember(invitation.key) { mutableStateOf(false) }
    var error by remember(invitation.key) { mutableStateOf<String?>(null) }
    val label = invitation.label
    val signInText = stringResource(Res.string.sync_org_invite_error_sign_in)
    val deniedText = stringResource(Res.string.sync_org_invite_error_denied, label)
    val unavailableText = stringResource(Res.string.sync_org_invite_error_unavailable)
    val otherText = stringResource(Res.string.sync_org_invite_error_other, label, "%1\$s")

    AlertDialog(
        onDismissRequest = { if (!busy) onDecline() },
        title = { Text(stringResource(Res.string.sync_org_invite_title, label)) },
        text = {
            Column(
                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.sync_org_invite_body, sourceLabel, label))
                if (invitation.message.isNotBlank()) {
                    Text(
                        if (invitation.sentBy.isNotBlank()) {
                            stringResource(Res.string.sync_org_invite_message_from, invitation.sentBy)
                        } else {
                            stringResource(Res.string.sync_org_invite_message_from_admin)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(invitation.message, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        when (val result = onJoin()) {
                            JoinResult.Joined -> onJoined()
                            JoinResult.NotSignedIn -> error = signInText
                            JoinResult.Denied -> error = deniedText
                            JoinResult.Unavailable -> error = unavailableText
                            is JoinResult.Failed -> error = otherText.replace("%1\$s", result.message)
                        }
                        busy = false
                    }
                },
            ) {
                Text(stringResource(if (busy) Res.string.sync_org_invite_joining else Res.string.sync_org_invite_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, enabled = !busy) {
                Text(stringResource(Res.string.sync_org_invite_decline))
            }
        },
    )
}
