package com.eventmanager.app.setup

enum class CliErrorKind {
    NOT_LOGGED_IN,
    BILLING_REQUIRED,
    API_NOT_ENABLED,
    EVENTARC_PROPAGATION,
    PERMISSION_DENIED,
    NETWORK,
    QUOTA,
    UNKNOWN,
}

data class CliError(
    val kind: CliErrorKind,
    val retryable: Boolean,
    val manualAnchor: SetupStepId,
    val details: String,
)

class CliException(val error: CliError) : Exception(error.kind.name)

/** Turns Firebase CLI output into one plain error kind, and says whether waiting helps. */
object CliErrorClassifier {
    private val rules: List<Pair<CliErrorKind, List<Regex>>> = listOf(
        CliErrorKind.EVENTARC_PROPAGATION to listOf(
            Regex("Eventarc Service Agent", RegexOption.IGNORE_CASE),
            Regex("first time using 2nd gen functions", RegexOption.IGNORE_CASE),
        ),
        CliErrorKind.BILLING_REQUIRED to listOf(
            Regex("Blaze", RegexOption.IGNORE_CASE),
            Regex("billing account", RegexOption.IGNORE_CASE),
            Regex("billing (is )?(not )?enabled", RegexOption.IGNORE_CASE),
        ),
        CliErrorKind.API_NOT_ENABLED to listOf(
            Regex("API has not been used in project", RegexOption.IGNORE_CASE),
            Regex("SERVICE_DISABLED"),
            Regex("is disabled\\. Enable it", RegexOption.IGNORE_CASE),
        ),
        CliErrorKind.NOT_LOGGED_IN to listOf(
            Regex("Failed to authenticate", RegexOption.IGNORE_CASE),
            Regex("not logged in", RegexOption.IGNORE_CASE),
            Regex("firebase login", RegexOption.IGNORE_CASE),
        ),
        CliErrorKind.QUOTA to listOf(
            Regex("quota", RegexOption.IGNORE_CASE),
            Regex("HTTP Error: 429"),
        ),
        CliErrorKind.PERMISSION_DENIED to listOf(
            Regex("HTTP Error: 403"),
            Regex("permission denied", RegexOption.IGNORE_CASE),
        ),
        CliErrorKind.NETWORK to listOf(
            Regex("ENOTFOUND|ECONNRESET|ETIMEDOUT|EAI_AGAIN|socket hang up", RegexOption.IGNORE_CASE),
        ),
    )

    private val retryable = setOf(
        CliErrorKind.EVENTARC_PROPAGATION,
        CliErrorKind.API_NOT_ENABLED,
        CliErrorKind.NETWORK,
    )

    fun classify(output: String, step: SetupStepId): CliError {
        val kind = rules.firstOrNull { (_, patterns) -> patterns.any { it.containsMatchIn(output) } }?.first
            ?: CliErrorKind.UNKNOWN
        return CliError(kind, kind in retryable, step, output)
    }
}
