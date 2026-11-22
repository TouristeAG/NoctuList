package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.eventmanager.app.portal.GuestListGrant
import com.eventmanager.app.portal.GuestListGrants
import com.eventmanager.app.portal.PortalHolder
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.guest_list_access_add
import com.eventmanager.app.resources.guest_list_access_all_venues
import com.eventmanager.app.resources.guest_list_access_current
import com.eventmanager.app.resources.guest_list_access_given_by
import com.eventmanager.app.resources.guest_list_access_history
import com.eventmanager.app.resources.guest_list_access_none
import com.eventmanager.app.resources.guest_list_access_revoke
import com.eventmanager.app.resources.guest_list_access_revoked_on
import com.eventmanager.app.resources.guest_list_access_title
import com.eventmanager.app.resources.portal_invites_venue
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun day(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        .format(Instant.ofEpochMilli(millis).atZone(com.eventmanager.app.ui.utils.GuestListDefaultZoneId).toLocalDate())

private fun dateTime(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(millis).atZone(com.eventmanager.app.ui.utils.GuestListDefaultZoneId))

/**
 * "Manage guest list access" (spec guest list access §6.1): current grants with Revoke, then the
 * history apart, and a new grant for a date and an optional venue.
 */
@Composable
fun GuestListGrantsDialog(viewModel: EventManagerViewModel, holder: PortalHolder, onDismiss: () -> Unit) {
    val volunteers by viewModel.volunteers.collectAsState()
    val guests by viewModel.guests.collectAsState()
    val raw = remember(volunteers, guests, holder) { viewModel.guestListGrantsOf(holder) }
    val now = System.currentTimeMillis()
    val offset = remember { viewModel.dateChangeOffsetHours() }
    val current = remember(raw) { GuestListGrants.active(raw, now, offset) }
    val history = remember(raw) { GuestListGrants.history(raw, now, offset) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.ListAlt, contentDescription = null) },
        title = { Text(stringResource(Res.string.guest_list_access_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.guest_list_access_current), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (current.isEmpty()) {
                    Text(stringResource(Res.string.guest_list_access_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current.forEach { grant ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GrantText(grant, Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { viewModel.revokeGuestListAccess(holder, grant.id) } }) {
                            Text(stringResource(Res.string.guest_list_access_revoke), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.guest_list_access_add)) }
                if (history.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(Res.string.guest_list_access_history), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    history.forEach { grant -> GrantText(grant, Modifier.fillMaxWidth(), past = true) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )

    if (adding) {
        AddGrantDialog(
            viewModel = viewModel,
            onDismiss = { adding = false },
            onAdd = { date, venue ->
                adding = false
                scope.launch { viewModel.grantGuestListAccess(holder, date, venue) }
            },
        )
    }
}

@Composable
private fun GrantText(grant: GuestListGrant, modifier: Modifier, past: Boolean = false) {
    Column(modifier) {
        Text(
            day(grant.eventDateMillis) + " · " + grant.venueName.ifBlank { stringResource(Res.string.guest_list_access_all_venues) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (grant.grantedAt > 0) {
            Text(
                stringResource(Res.string.guest_list_access_given_by, grant.grantedBy.ifBlank { "-" }, dateTime(grant.grantedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (grant.revokedAt > 0) {
            Text(
                stringResource(Res.string.guest_list_access_revoked_on, dateTime(grant.revokedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun AddGrantDialog(viewModel: EventManagerViewModel, onDismiss: () -> Unit, onAdd: (Long, String) -> Unit) {
    var date by remember { mutableStateOf<Long?>(null) }
    var venue by remember { mutableStateOf("") }
    val venues by viewModel.venues.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.guest_list_access_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EventDatePickerField(selectedDateMillis = date, onDateSelected = { date = it })
                Text(stringResource(Res.string.portal_invites_venue), style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = venue.isBlank(), onClick = { venue = "" })
                    Text(stringResource(Res.string.guest_list_access_all_venues))
                }
                venues.filter { it.isActive }.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = venue == v.name, onClick = { venue = v.name })
                        Text(v.name)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { date?.let { onAdd(it, venue) } }, enabled = date != null) { Text(stringResource(Res.string.guest_list_access_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
