package com.eventmanager.app.setup

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import kotlinx.coroutines.flow.StateFlow

/** What a step needs from the admin. Only the fields of the step being run are read. */
class StepInput(
    val projectId: String? = null,
    val newProjectName: String? = null,
    val location: String? = null,
    val gmailUser: String? = null,
    /** Wiped by the runner once the step finishes. */
    val appPassword: CharArray? = null,
    val webClientId: String? = null,
    val webClientSecret: String? = null,
)

/** The Automatic tab's engine. Unavailable on phones, where only the Manual tab is shown. */
interface SetupAutomation {
    val available: Boolean
    val states: StateFlow<Map<SetupStepId, StepState>>
    val progress: StateFlow<SetupProgress>

    /** Re-checks what is already done (read-only). */
    suspend fun refresh()
    fun run(step: SetupStepId, input: StepInput = StepInput())
    fun confirm(step: SetupStepId)
    fun setExtra(step: SetupStepId, enabled: Boolean)
    fun cancel()

    /** Values the UI shows, such as the signed-in account or the project list. */
    val info: StateFlow<SetupInfo>
}

data class SetupInfo(
    val account: String = "",
    val projects: List<ProjectInfo> = emptyList(),
    val locations: List<String> = emptyList(),
    val region: String = "",
    val siteUrl: String = "",
    val toolsProgress: Float? = null,
)

data class ProjectInfo(val id: String, val displayName: String)

expect fun createSetupAutomation(context: PlatformContext, settings: SettingsManager): SetupAutomation
