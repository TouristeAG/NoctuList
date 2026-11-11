package com.eventmanager.app.setup

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Phones only show the Manual tab: setup runs on the computer app. */
actual fun createSetupAutomation(context: PlatformContext, settings: SettingsManager): SetupAutomation =
    object : SetupAutomation {
        override val available = false
        override val states: StateFlow<Map<SetupStepId, StepState>> = MutableStateFlow(emptyMap())
        override val progress: StateFlow<SetupProgress> = MutableStateFlow(SetupProgress())
        override val info: StateFlow<SetupInfo> = MutableStateFlow(SetupInfo())
        override suspend fun refresh() = Unit
        override fun run(step: SetupStepId, input: StepInput) = Unit
        override fun confirm(step: SetupStepId) = Unit
        override fun setExtra(step: SetupStepId, enabled: Boolean) = Unit
        override fun cancel() = Unit
    }
