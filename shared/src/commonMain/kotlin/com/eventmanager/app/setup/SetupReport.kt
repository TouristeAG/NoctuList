package com.eventmanager.app.setup

/** A plain text summary of a failed step that the admin can paste to whoever is helping. */
object SetupReport {
    private const val DETAIL_LINES = 40

    /** The caller passes the result through the redactor before it leaves the app. */
    fun build(
        step: SetupStepId,
        error: SetupError,
        repairs: List<RepairNote>,
        os: String,
        versions: String,
    ): String = buildString {
        appendLine("NoctuList setup report")
        appendLine("Step ${step.number} (${step.name})")
        appendLine("Error: ${error.id.name}")
        appendLine("Message: ${error.headline}")
        appendLine("System: $os")
        appendLine("Tools: $versions")
        if (repairs.isNotEmpty()) {
            appendLine("Done by the app:")
            repairs.forEach { appendLine("- ${it.repair.name}${it.detail.takeIf { d -> d.isNotBlank() }?.let { d -> ": $d" } ?: ""}") }
        }
        appendLine("Details (last $DETAIL_LINES lines):")
        append(error.details.lines().takeLast(DETAIL_LINES).joinToString("\n"))
    }
}
