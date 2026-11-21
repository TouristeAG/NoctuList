package com.eventmanager.app.ui.screens

import com.eventmanager.app.ui.components.KeepScreenAwakeForSpace
import com.eventmanager.app.ui.components.KeepAwakeSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.PlatformBackHandler
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.ui.components.AppBackgroundAnimation
import com.eventmanager.app.ui.platform.AppAppearanceState
import com.eventmanager.app.ui.transitions.DeferredUntilSpaceEntranceSettled
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel

fun performPosFlowExit(
    viewModel: EventManagerViewModel,
    onExit: () -> Unit,
) {
    viewModel.endPosSession()
    onExit()
}

@Composable
fun PosFlow(
    viewModel: EventManagerViewModel,
    salesItems: List<com.eventmanager.app.data.models.SalesSheetItem>,
    volunteers: List<Volunteer>,
    guests: List<Guest>,
    onBack: () -> Unit,
    onFactoryResetComplete: () -> Unit = {},
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val uiRefreshNonce by AppAppearanceState::refreshNonce
    val posBackgroundStyle = remember(uiRefreshNonce) { settingsManager.getPosBackgroundAnimationStyle() }
    val posBackgroundOpacity = remember(uiRefreshNonce) { settingsManager.getPosBackgroundAnimationOpacity() }
    var showSettings by remember { mutableStateOf(false) }

    val leavePos = {
        performPosFlowExit(viewModel, onBack)
    }

    PlatformBackHandler {
        if (showSettings) {
            showSettings = false
        } else {
            leavePos()
        }
    }

    DeferredUntilSpaceEntranceSettled {
        viewModel.bootstrapPosSession()
    }

    val venues by viewModel.venues.collectAsState()

    KeepScreenAwakeForSpace(KeepAwakeSpace.Pos)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AppBackgroundAnimation(
            style = posBackgroundStyle,
            opacity = posBackgroundOpacity,
            settingsManager = settingsManager,
            isDesktop = platformContext.isDesktop,
        )
        if (showSettings) {
            PosSettingsScreen(
                viewModel = viewModel,
                onBack = { showSettings = false },
                onFactoryResetComplete = onFactoryResetComplete,
            )
        } else {
            PosScreen(
                viewModel = viewModel,
                salesItems = salesItems,
                volunteers = volunteers,
                guests = guests,
                venues = venues,
                onBack = leavePos,
                onOpenSettings = { showSettings = true }
            )
        }
    }
}
