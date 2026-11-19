package com.eventmanager.app.setup

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Removes secrets (such as the Gmail app password) from command output shown on screen or written
 * to logs. Also hides their base64 and base64url forms, which tools put into request bodies, and
 * Google tokens (access, refresh and ID tokens) wherever they appear, even when nobody listed them.
 */
@OptIn(ExperimentalEncodingApi::class)
class OutputRedactor(private val secrets: () -> List<String>) {
    fun redact(line: String): String =
        TOKENS.fold(variants().fold(line) { acc, s -> acc.replace(s, MASK) }) { acc, re -> acc.replace(re, MASK) }

    private fun variants(): List<String> =
        secrets().filter { it.isNotBlank() }.flatMap { secret ->
            val bytes = secret.encodeToByteArray()
            val std = Base64.Default.encode(bytes)
            val url = Base64.UrlSafe.encode(bytes)
            listOf(secret, std, std.trimEnd('='), url, url.trimEnd('='))
        }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }

    private companion object {
        const val MASK = "••••"
        val TOKENS = listOf(
            Regex("""ya29\.[\w.-]+"""), // access token
            Regex("""1//[\w.-]+"""), // refresh token
            Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+"""), // ID token (JWT)
        )
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
