package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.offeredAccesses
import com.eventmanager.app.data.models.statusValue
import com.eventmanager.app.portal.PortalInviteAllowance
import com.eventmanager.app.portal.PortalInviteList
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.portal_invites_access_max
import com.eventmanager.app.resources.portal_invites_can_invite
import com.eventmanager.app.resources.portal_invites_edit_limits
import com.eventmanager.app.resources.portal_invites_list_name
import com.eventmanager.app.resources.portal_invites_manage
import com.eventmanager.app.resources.portal_invites_max_guests
import com.eventmanager.app.resources.portal_invites_none
import com.eventmanager.app.resources.portal_invites_other_event
import com.eventmanager.app.resources.portal_invites_places
import com.eventmanager.app.resources.portal_invites_venue
import com.eventmanager.app.resources.save
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Limits the admin sets for the invites of one event (spec 2026-11-21 §8.2). */
private data class InviteDraft(
    val eventDateMillis: Long?,
    val venueName: String,
    val artistName: String,
    val maxGuests: String,
    val accessMax: Map<String, String>,
)

private fun formatDay(millis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(millis).atZone(com.eventmanager.app.ui.utils.GuestListDefaultZoneId).toLocalDate())

/**
 * "Can invite" per upcoming list of [address], and invites for another event. Shown where the
 * account of [address] is known ([portalUid]): the temporary guest space and the access card.
 */
@Composable
fun PortalInvitesPanel(
    viewModel: EventManagerViewModel,
    address: String,
    portalUid: String,
    emergencyPhone: String = "",
) {
    val guests by viewModel.guests.collectAsState()
    val forms by viewModel.guestForms.collectAsState()
    val lists = remember(guests, forms, address) { viewModel.portalInviteLists(address) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Pair<PortalInviteList?, InviteDraft>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lists.isEmpty()) {
            Text(stringResource(Res.string.portal_invites_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lists.forEach { list ->
            val open = list.template?.statusValue == GuestFormStatus.OPEN
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(list.artistName.ifBlank { list.venueName }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOf(formatDay(list.eventDateMillis), list.venueName).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(stringResource(Res.string.portal_invites_can_invite), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = open,
                        onCheckedChange = { on ->
                            if (on) editing = list to draftOf(list)
                            else list.template?.let { viewModel.closePortalInvites(it) }
                        },
                    )
                }
                val template = list.template
                if (open && template != null) {
                    val allowance = remember(template, list.members, forms) {
                        PortalInviteAllowance.of(template, list.members, forms.filter { it.parentFormId == template.formId })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(Res.string.portal_invites_places, allowance.remaining, allowance.maxGuests),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { editing = list to draftOf(list) }) { Text(stringResource(Res.string.portal_invites_edit_limits)) }
                    }
                }
                HorizontalDivider()
            }
        }
        OutlinedButton(
            onClick = { editing = null to InviteDraft(null, "", "", "${GuestForm.DEFAULT_MAX_GUESTS}", emptyMap()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.portal_invites_other_event))
        }
    }

    editing?.let { (list, start) ->
        InviteLimitsDialog(
            viewModel = viewModel,
            fixedEvent = list != null,
            start = start,
            onDismiss = { editing = null },
            onSave = { d ->
                editing = null
                scope.launch {
                    viewModel.openPortalInvites(
                        contactEmail = address,
                        portalUid = portalUid,
                        eventDateMillis = d.eventDateMillis ?: return@launch,
                        venueName = d.venueName,
                        artistName = d.artistName.ifBlank { address },
                        maxGuests = d.maxGuests.toIntOrNull() ?: GuestForm.DEFAULT_MAX_GUESTS,
                        accessLimits = d.accessMax.mapValues { it.value.toIntOrNull() ?: 0 },
                        emergencyPhone = list?.members?.firstOrNull()?.temporaryContactPhone ?: emergencyPhone,
                    )
                }
            },
        )
    }
}

private fun draftOf(list: PortalInviteList): InviteDraft {
    val t = list.template
    return InviteDraft(
        eventDateMillis = list.eventDateMillis,
        venueName = list.venueName,
        artistName = list.artistName,
        maxGuests = "${t?.maxGuests ?: GuestForm.DEFAULT_MAX_GUESTS}",
        accessMax = t?.offeredAccesses().orEmpty().associate { it.id to if (it.maxRequests > 0) "${it.maxRequests}" else "" },
    )
}

@Composable
private fun InviteLimitsDialog(
    viewModel: EventManagerViewModel,
    fixedEvent: Boolean,
    start: InviteDraft,
    onDismiss: () -> Unit,
    onSave: (InviteDraft) -> Unit,
) {
    var draft by remember { mutableStateOf(start) }
    val venues by viewModel.venues.collectAsState()
    val accesses = remember(draft.venueName) { viewModel.portalInviteAccessesFor(draft.venueName) }
    val valid = draft.eventDateMillis != null && draft.venueName.isNotBlank() && (draft.maxGuests.toIntOrNull() ?: 0) > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
        title = { Text(if (fixedEvent) start.artistName.ifBlank { start.venueName } else stringResource(Res.string.portal_invites_other_event)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!fixedEvent) {
                    EventDatePickerField(selectedDateMillis = draft.eventDateMillis, onDateSelected = { draft = draft.copy(eventDateMillis = it) })
                    Text(stringResource(Res.string.portal_invites_venue), style = MaterialTheme.typography.labelMedium)
                    venues.filter { it.isActive }.forEach { venue ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(
                                selected = draft.venueName == venue.name,
                                onClick = { draft = draft.copy(venueName = venue.name, accessMax = emptyMap()) },
                            )
                            Text(venue.name)
                        }
                    }
                    OutlinedTextField(
                        value = draft.artistName,
                        onValueChange = { draft = draft.copy(artistName = it.take(80)) },
                        label = { Text(stringResource(Res.string.portal_invites_list_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = draft.maxGuests,
                    onValueChange = { v -> draft = draft.copy(maxGuests = v.filter { it.isDigit() }.take(3)) },
                    label = { Text(stringResource(Res.string.portal_invites_max_guests)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                accesses.forEach { access ->
                    val offered = access.id in draft.accessMax
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = offered,
                            onCheckedChange = { on ->
                                draft = draft.copy(accessMax = if (on) draft.accessMax + (access.id to "") else draft.accessMax - access.id)
                            },
                        )
                        Text(access.name, modifier = Modifier.weight(1f))
                        if (offered) {
                            OutlinedTextField(
                                value = draft.accessMax[access.id].orEmpty(),
                                onValueChange = { v -> draft = draft.copy(accessMax = draft.accessMax + (access.id to v.filter { it.isDigit() }.take(3))) },
                                label = { Text(stringResource(Res.string.portal_invites_access_max)) },
                                singleLine = true,
                                modifier = Modifier.width(110.dp).padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(draft) }, enabled = valid) { Text(stringResource(Res.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** "Manage guests" from a volunteer's or permanent guest's access card. */
@Composable
fun PortalInvitesDialog(
    viewModel: EventManagerViewModel,
    address: String,
    portalUid: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
        title = { Text(stringResource(Res.string.portal_invites_manage)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PortalInvitesPanel(viewModel, address, portalUid)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

/** Portal invites are guest forms: they exist only while guest forms are on. */
@Composable
fun rememberGuestFormsEnabled(viewModel: EventManagerViewModel): Boolean {
    val enabled by viewModel.guestFormsEnabled.collectAsState()
    return enabled
}
