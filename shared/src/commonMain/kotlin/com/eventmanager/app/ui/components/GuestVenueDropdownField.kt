package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.guest_free_entry_venues
import com.eventmanager.app.resources.venue_all
import com.eventmanager.app.ui.utils.getVenueDisplayString
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestVenueDropdownField(
    venues: List<VenueEntity>,
    selectedVenueName: String?,
    onVenueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    var expanded by remember { mutableStateOf(false) }
    val displayValue = getVenueDisplayString(selectedVenueName, venues).ifBlank {
        stringResource(Res.string.guest_free_entry_venues)
    }
    val allOptionText = stringResource(Res.string.venue_all)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.guest_free_entry_venues)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(allOptionText) },
                onClick = {
                    onVenueSelected("BOTH")
                    expanded = false
                },
            )
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
