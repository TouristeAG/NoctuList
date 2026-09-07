package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.data.utils.DateTimeUtils
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** Formats the moment an entry was consumed, in the venue's timezone. */
fun temporaryEntryValidatedLabel(guest: Guest): String? =
    if (guest.temporaryEntryValidated) {
        DateTimeUtils.formatGenevaTimeOnly(guest.temporaryEntryValidatedAt)
    } else {
        null
    }

/**
 * Accesses a temporary guest holds at their venue, as contrasted chips. Renders nothing when the
 * venue has no access configured, so an unused catalogue never adds noise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemporaryGuestAccessChips(
    accesses: List<VenueAccess>,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    if (accesses.isEmpty()) return
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        accesses.forEach { access ->
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        access.name,
                        style = if (prominent) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.VpnKey,
                        contentDescription = null,
                        modifier = Modifier.size(if (prominent) 20.dp else 16.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
    }
}

/**
 * Accesses block with its own title, for the detail panel and the scanner result screen.
 */
@Composable
fun TemporaryGuestAccessBlock(
    accesses: List<VenueAccess>,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    if (accesses.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.temp_guest_accesses_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TemporaryGuestAccessChips(accesses = accesses, prominent = prominent)
    }
}

/**
 * Single-use entry validation for a temporary guest. Simpler than the volunteer equivalent: there
 * is nothing left to count once the one entry is used, so the block collapses to a confirmation.
 */
@Composable
fun TemporaryGuestEntrySection(
    guest: Guest,
    onValidate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val validatedAt = temporaryEntryValidatedLabel(guest)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (validatedAt != null) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (validatedAt != null) 0.dp else 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (validatedAt != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(Res.string.temp_guest_entry_validated_at, validatedAt),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            } else {
                Text(
                    text = stringResource(Res.string.temp_guest_validate_entry),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.temp_guest_entry_single_use_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EntryConfirmControl(onConfirm = onValidate)
            }
        }
    }
}
