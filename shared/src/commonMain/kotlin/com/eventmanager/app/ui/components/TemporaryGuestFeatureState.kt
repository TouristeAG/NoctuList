package com.eventmanager.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel

/**
 * Everything the temporary guest surfaces need to decide what to show. All of it collapses to the
 * disabled state on the Sheets backend, where these fields do not exist.
 */
data class TemporaryGuestFeatureState(
    val enabled: Boolean = false,
    val venueAccesses: List<VenueAccess> = emptyList(),
    val creditsEnabled: Boolean = false,
)

@Composable
fun rememberTemporaryGuestFeatures(viewModel: EventManagerViewModel?): TemporaryGuestFeatureState {
    if (viewModel == null) return TemporaryGuestFeatureState()
    val accesses by viewModel.temporaryGuestVenueAccesses.collectAsState()
    val credits by viewModel.temporaryGuestCreditsEnabled.collectAsState()
    return TemporaryGuestFeatureState(
        enabled = viewModel.isTemporaryGuestFeaturesEnabled(),
        venueAccesses = accesses,
        creditsEnabled = credits,
    )
}
