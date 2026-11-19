package com.eventmanager.app.setup

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One log file per step and day, with secrets removed before anything is written. */
class SetupLogFiles(private val dir: File, private val redactor: OutputRedactor, private val now: () -> Long) {
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    private fun fileFor(step: SetupStepId): File =
        dir.resolve("${day.format(Instant.ofEpochMilli(now()))}-${step.name.lowercase()}.log")

    fun sink(step: SetupStepId): (String) -> Unit = { line ->
        val safe = redactor.redact(line).replace(MASK, "***")
        runCatching {
            dir.mkdirs()
            fileFor(step).appendText(safe + "\n", Charsets.UTF_8)
        }
    }

    /** Most recently written log of this step. */
    fun latest(step: SetupStepId): File? =
        dir.listFiles { f -> f.isFile && f.name.endsWith("-${step.name.lowercase()}.log") }
            ?.maxByOrNull { it.lastModified() }

    /** Deletes logs not touched for a week. */
    fun prune() {
        val limit = now() - RETENTION_MS
        dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < limit) it.delete() }
    }

    private companion object {
        const val MASK = "••••"
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}
