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
    /** Code shown by the Google sign-in page for the Firebase CLI. */
    val authCode: String? = null,
)

/** The Automatic tab's engine. Unavailable on phones, where only the Manual tab is shown. */
interface SetupAutomation {
    val available: Boolean
    val states: StateFlow<Map<SetupStepId, StepState>>
    val progress: StateFlow<SetupProgress>

    /** Re-checks what is already done (read-only). */
    suspend fun refresh()
    fun run(step: SetupStepId, input: StepInput = StepInput())

    /** Stops running steps, signs the Firebase CLI out and forgets the account. */
    suspend fun signOut()

    /** [signOut], then starts a new sign-in. */
    suspend fun switchAccount()

    /** Removes the app's Node.js and Firebase tools and installs them again. */
    fun reinstallTools()
    fun confirm(step: SetupStepId)

    /**
     * Links the project to [account], one of those a [StepState.NeedsBilling] offered for [step], then runs
     * [step] again with [input] (the step's form as it is now). The only way the app turns Blaze on.
     */
    fun confirmBilling(step: SetupStepId, account: String, input: StepInput = StepInput())
    fun setExtra(step: SetupStepId, enabled: Boolean)
    fun cancel()

    /** Stops everything; called when the setup page closes. */
    fun close()

    /** Text of the failed [step] for the admin to copy: no secret in it. Empty where there is no setup engine. */
    fun report(step: SetupStepId): String

    /** Values the UI shows, such as the signed-in account or the project list. */
    val info: StateFlow<SetupInfo>
}

/** What a check by running the app's tools found. */
enum class ToolsState { READY, MISSING, BROKEN, OUTDATED }

data class SetupInfo(
    val account: String = "",
    val projects: List<ProjectInfo> = emptyList(),
    val locations: List<String> = emptyList(),
    val region: String = "",
    val siteUrl: String = "",
    val toolsProgress: Float? = null,
    val tools: ToolsState = ToolsState.MISSING,
    /** Set while the admin signs in: open [LoginChallenge.url], then paste the code. */
    val loginChallenge: LoginChallenge? = null,
    /** What the app fixed by itself before a step failed, shown in the error box. */
    val repairs: Map<SetupStepId, List<RepairNote>> = emptyMap(),
    /** True while [SetupAutomation.refresh] checks the steps against the project. */
    val checking: Boolean = false,
)

/** One repair the app ran for a step, with what it found. */
data class RepairNote(val repair: Repair, val detail: String)

data class LoginChallenge(val sessionId: String, val url: String)

data class ProjectInfo(val id: String, val displayName: String)

expect fun createSetupAutomation(context: PlatformContext, settings: SettingsManager): SetupAutomation
