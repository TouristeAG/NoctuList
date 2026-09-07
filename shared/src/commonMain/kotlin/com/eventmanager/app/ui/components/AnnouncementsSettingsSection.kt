package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

enum class AnnouncementsSettingsMode {
    /** Reception, venues and validity. */
    Standard,
    /** Standard + billeterie send permission (admin only). */
    Admin,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnouncementsSettingsContent(
    settingsManager: SettingsManager,
    activeVenues: List<VenueEntity>,
    mode: AnnouncementsSettingsMode,
    billeterieSendEnabled: Boolean = settingsManager.isAnnouncementsNonAdminSendEnabled(),
    canEditBilleterieSend: Boolean = false,
    onBilleterieSendEnabledChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    var receptionEnabled by remember { mutableStateOf(settingsManager.isAnnouncementsReceptionEnabled()) }
    var trackedVenueIds by remember { mutableStateOf(settingsManager.getAnnouncementsTrackedVenueIds()) }
    var validityMinutes by remember { mutableStateOf(settingsManager.getAnnouncementsValidityMinutes()) }
    val allTracked = trackedVenueIds.isEmpty()

    val validityOptions = listOf(
        15 to stringResource(Res.string.announcements_validity_15min),
        30 to stringResource(Res.string.announcements_validity_30min),
        60 to stringResource(Res.string.announcements_validity_1h),
        120 to stringResource(Res.string.announcements_validity_2h),
        240 to stringResource(Res.string.announcements_validity_4h),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsToggleRow(
            title = stringResource(Res.string.announcements_reception_title),
            description = stringResource(Res.string.announcements_reception_description),
            checked = receptionEnabled,
            onCheckedChange = {
                receptionEnabled = it
                settingsManager.setAnnouncementsReceptionEnabled(it)
            },
        )

        GuidedStepCard(
            title = stringResource(Res.string.announcements_tracked_venues_title),
            body = stringResource(Res.string.announcements_tracked_venues_description),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = allTracked,
                    onClick = {
                        trackedVenueIds = emptySet()
                        settingsManager.setAnnouncementsTrackedVenueIds(emptySet())
                    },
                    label = { Text(stringResource(Res.string.announcements_tracked_venues_all)) },
                    leadingIcon = if (allTracked) {
                        { Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
                activeVenues.forEach { venue ->
                    val venueIdStr = venue.id.toString()
                    val isSelected = allTracked || trackedVenueIds.contains(venueIdStr)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            val newSet = when {
                                allTracked -> activeVenues.map { it.id.toString() }.toSet() - venueIdStr
                                trackedVenueIds.contains(venueIdStr) -> trackedVenueIds - venueIdStr
                                else -> trackedVenueIds + venueIdStr
                            }
                            val finalSet = if (newSet.size == activeVenues.size) emptySet() else newSet
                            trackedVenueIds = finalSet
                            settingsManager.setAnnouncementsTrackedVenueIds(finalSet)
                        },
                        label = { Text(venue.name) },
                    )
                }
            }
        }

        GuidedStepCard(
            title = stringResource(Res.string.announcements_validity_title),
            body = stringResource(Res.string.announcements_validity_description),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                validityOptions.forEach { (minutes, label) ->
                    FilterChip(
                        selected = validityMinutes == minutes,
                        onClick = {
                            validityMinutes = minutes
                            settingsManager.setAnnouncementsValidityMinutes(minutes)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (mode == AnnouncementsSettingsMode.Admin) {
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))
            SettingsToggleRow(
                title = stringResource(Res.string.announcements_non_admin_send_title),
                description = stringResource(Res.string.announcements_non_admin_send_description),
                checked = billeterieSendEnabled,
                enabled = canEditBilleterieSend,
                onCheckedChange = {
                    if (!canEditBilleterieSend) return@SettingsToggleRow
                    onBilleterieSendEnabledChange?.invoke(it)
                        ?: settingsManager.setAnnouncementsNonAdminSendEnabled(it)
                },
            )
            if (!canEditBilleterieSend) {
                Text(
                    text = stringResource(Res.string.institution_settings_firebase_admin_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
