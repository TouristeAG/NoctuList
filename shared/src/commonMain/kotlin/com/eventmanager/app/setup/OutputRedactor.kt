package com.eventmanager.app.setup

/** Removes secrets (such as the Gmail app password) from command output shown on screen. */
class OutputRedactor(private val secrets: () -> List<String>) {
    fun redact(line: String): String =
        secrets().filter { it.isNotBlank() }.fold(line) { acc, s -> acc.replace(s, MASK) }

    private companion object {
        const val MASK = "••••"
    }
}

/** Secrets in use right now (only while a step that handles one is running). */
class ActiveSecrets {
    private val values = mutableListOf<String>()

    @Synchronized
    fun current(): List<String> = values.toList()

    @Synchronized
    fun add(value: String) {
        values += value
    }

    @Synchronized
    fun clear() {
        values.clear()
    }
}
