package com.eventmanager.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel

@Composable
expect fun SettingsScreen(
    viewModel: EventManagerViewModel,
    onNavigateToJobTypeManagement: () -> Unit = {},
    onNavigateToVenueManagement: () -> Unit = {},
    onNavigateToSalesSheetItemManagement: () -> Unit = {},
    onNavigateToGuestFormManagement: () -> Unit = {},
    variant: SettingsScreenVariant = SettingsScreenVariant.Full,
    modifier: Modifier = Modifier,
    onDesktopAdminNavLayoutChanged: () -> Unit = {},
    /** Called after a successful factory reset so the host can show the setup wizard. */
    onFactoryResetComplete: () -> Unit = {},
)
