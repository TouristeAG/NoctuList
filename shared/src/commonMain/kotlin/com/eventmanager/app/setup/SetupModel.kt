package com.eventmanager.app.setup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The setup steps, numbered the same way in the Automatic and Manual tabs. */
enum class SetupStepId(val number: Int, val optional: Boolean) {
    TOOLS(0, false),
    LOGIN(1, false),
    PROJECT(2, false),
    DATABASE(3, false),
    RULES(4, false),
    PROVIDERS(5, false),
    CONNECT(6, false),
    SITE(7, true),
    PHOTOS(8, true),
    SERVER(9, true),
}

enum class StepKind { RUN, OPEN, RUN_THEN_OPEN, OPEN_THEN_RUN }

val SetupStepId.kind: StepKind
    get() = when (this) {
        SetupStepId.PROVIDERS -> StepKind.OPEN
        SetupStepId.CONNECT -> StepKind.RUN_THEN_OPEN
        SetupStepId.PHOTOS, SetupStepId.SERVER -> StepKind.OPEN_THEN_RUN
        else -> StepKind.RUN
    }

sealed interface StepState {
    data object Todo : StepState
    data class Running(val log: List<String>) : StepState
    data class WaitingRetry(val attempt: Int, val secondsLeft: Int) : StepState
    data object Done : StepState
    data object Skipped : StepState
    data class Error(val error: CliError) : StepState
}

/** What the assistant remembers per project, so it resumes where it stopped. */
@Serializable
data class SetupProgress(
    val projectId: String = "",
    val done: Set<SetupStepId> = emptySet(),
    val confirmed: Set<SetupStepId> = emptySet(),
    val extras: Set<SetupStepId> = emptySet(),
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): SetupProgress =
            runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(SetupProgress())
    }
}
