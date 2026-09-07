package com.eventmanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.VenueAccessCatalog
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Admin catalogue of the special accesses (stage, backstage, VIP...) a temporary guest can be
 * granted, one list per venue, plus the opt-in credit accounts toggle. Firebase-only: callers must
 * hide the whole section on the Sheets backend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemporaryGuestAccessSettingsSection(
    activeVenues: List<VenueEntity>,
    accesses: List<VenueAccess>,
    creditsEnabled: Boolean,
    canEdit: Boolean,
    onAddAccess: (venueName: String, name: String) -> Unit,
    onRemoveAccess: (accessId: String) -> Unit,
    onCreditsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var addingForVenue by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<VenueAccess?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.temp_guest_access_settings_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (activeVenues.isEmpty()) {
            Text(
                text = stringResource(Res.string.temp_guest_access_no_venue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        activeVenues.forEach { venue ->
            val venueAccesses = VenueAccessCatalog.forVenue(accesses, venue.name)
            GuidedStepCard(
                title = venue.name,
                body = if (venueAccesses.isEmpty()) {
                    stringResource(Res.string.temp_guest_access_venue_empty)
                } else {
                    stringResource(Res.string.temp_guest_access_venue_count, venueAccesses.size)
                },
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    venueAccesses.forEach { access ->
                        AssistChip(
                            onClick = { if (canEdit) pendingRemoval = access },
                            label = { Text(access.name) },
                            trailingIcon = if (canEdit) {
                                {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(Res.string.delete),
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { pendingRemoval = access },
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    if (canEdit && venueAccesses.size < VenueAccessCatalog.MAX_PER_VENUE) {
                        AssistChip(
                            onClick = { addingForVenue = venue.name },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = { Text(stringResource(Res.string.temp_guest_access_add)) },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.primary,
                                leadingIconContentColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }
        }

        SettingsToggleRow(
            title = stringResource(Res.string.temp_guest_credits_setting_title),
            description = stringResource(Res.string.temp_guest_credits_setting_description),
            checked = creditsEnabled,
            enabled = canEdit,
            onCheckedChange = onCreditsEnabledChange,
        )
    }

    addingForVenue?.let { venueName ->
        var draft by remember(venueName) { mutableStateOf("") }
        val normalized = VenueAccessCatalog.normalizeName(draft)
        val duplicate = VenueAccessCatalog.contains(accesses, venueName, normalized)
        AlertDialog(
            onDismissRequest = { addingForVenue = null },
            title = { Text(stringResource(Res.string.temp_guest_access_add_title, venueName)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(VenueAccessCatalog.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(Res.string.temp_guest_access_name_label)) },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text(stringResource(Res.string.temp_guest_access_duplicate)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddAccess(venueName, normalized)
                        addingForVenue = null
                    },
                    enabled = normalized.isNotEmpty() && !duplicate,
                ) { Text(stringResource(Res.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { addingForVenue = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    pendingRemoval?.let { access ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(Res.string.temp_guest_access_remove_title)) },
            text = { Text(stringResource(Res.string.temp_guest_access_remove_message, access.name)) },
            confirmButton = {
                Button(onClick = {
                    onRemoveAccess(access.id)
                    pendingRemoval = null
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}
