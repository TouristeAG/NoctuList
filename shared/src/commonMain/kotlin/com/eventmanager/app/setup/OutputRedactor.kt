package com.eventmanager.app.setup

/** Removes secrets (such as the Gmail app password) from command output shown on screen. */
class OutputRedactor(private val secrets: () -> List<String>) {
    fun redact(line: String): String =
        secrets().filter { it.isNotBlank() }.fold(line) { acc, s -> acc.replace(s, MASK) }

    private companion object {
        const val MASK = "••••"
    }
}
