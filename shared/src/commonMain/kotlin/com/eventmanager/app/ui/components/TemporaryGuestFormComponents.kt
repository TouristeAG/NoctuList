package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Grants a single temporary guest their special accesses at the selected venue. A lone access is a
 * plain switch; several become checkable chips, because a friend of the artist may need backstage
 * while the next one does not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemporaryGuestAccessSelector(
    venueAccesses: List<VenueAccess>,
    selectedAccessIds: Set<String>,
    onToggleAccess: (accessId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (venueAccesses.isEmpty()) return

    if (venueAccesses.size == 1) {
        val access = venueAccesses.first()
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = access.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = selectedAccessIds.contains(access.id),
                onCheckedChange = { onToggleAccess(access.id) },
            )
        }
        return
    }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        venueAccesses.forEach { access ->
            val selected = selectedAccessIds.contains(access.id)
            FilterChip(
                selected = selected,
                onClick = { onToggleAccess(access.id) },
                label = { Text(access.name) },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * One person on the temporary guest form: their name, a delete affordance for extra rows, and the
 * accesses they personally get.
 */
@Composable
fun TemporaryGuestNameCard(
    name: String,
    onNameChange: (String) -> Unit,
    label: String,
    venueAccesses: List<VenueAccess>,
    selectedAccessIds: Set<String>,
    onToggleAccess: (accessId: String) -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val hasAccesses = venueAccesses.isNotEmpty()
    val nameRow = @Composable {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            onRemove?.let { remove ->
                IconButton(onClick = remove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Without accesses the card only wraps one field and its border clashes with the TextField.
    if (!hasAccesses) {
        Column(modifier = modifier.fillMaxWidth()) {
            nameRow()
        }
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            nameRow()
            Text(
                text = stringResource(Res.string.temp_guest_access_person_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TemporaryGuestAccessSelector(
                venueAccesses = venueAccesses,
                selectedAccessIds = selectedAccessIds,
                onToggleAccess = onToggleAccess,
            )
        }
    }
}

/**
 * Venue a temporary batch is allowed into. Unlike the permanent guest dropdown there is no "all
 * venues" option: accesses belong to one venue, so the batch must name it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemporaryGuestVenueDropdown(
    venues: List<VenueEntity>,
    selectedVenueName: String?,
    onVenueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedVenueName.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.temp_guest_venue_label)) },
            placeholder = { Text(stringResource(Res.string.temp_guest_venue_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            activeVenues.forEach { venue ->
                DropdownMenuItem(
                    text = { Text(venue.name) },
                    onClick = {
                        onVenueSelected(venue.name)
                        expanded = false
                    },
                )
            }
        }
    }
}
