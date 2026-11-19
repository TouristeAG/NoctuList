package com.eventmanager.app.setup

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Removes secrets (such as the Gmail app password) from command output shown on screen or written
 * to logs. Also hides their base64 and base64url forms, which tools put into request bodies.
 */
@OptIn(ExperimentalEncodingApi::class)
class OutputRedactor(private val secrets: () -> List<String>) {
    fun redact(line: String): String =
        variants().fold(line) { acc, s -> acc.replace(s, MASK) }

    private fun variants(): List<String> =
        secrets().filter { it.isNotBlank() }.flatMap { secret ->
            val bytes = secret.encodeToByteArray()
            val std = Base64.Default.encode(bytes)
            val url = Base64.UrlSafe.encode(bytes)
            listOf(secret, std, std.trimEnd('='), url, url.trimEnd('='))
        }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

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
