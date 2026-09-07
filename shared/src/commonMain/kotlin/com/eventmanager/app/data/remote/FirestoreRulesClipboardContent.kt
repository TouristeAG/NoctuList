package com.eventmanager.app.data.remote

import com.eventmanager.app.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Loads the deployable Firestore rules for in-app clipboard copy.
 * Source of truth: [composeResources/files/firestore.rules] (keep in sync with
 * repo-root [firebase/firestore.rules]).
 */
object FirestoreRulesClipboardContent {
    /**
     * Exact UTF-8 text of firestore.rules, trimmed, without BOM.
     * Safe to paste into Firebase Console → Firestore → Rules (not Realtime Database).
     */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): String {
        val raw = Res.readBytes("files/firestore.rules").decodeToString()
        val sanitized = sanitizeForFirebaseConsole(raw)
        require(sanitized.contains("service cloud.firestore")) {
            "files/firestore.rules is not Firestore rules"
        }
        return sanitized
    }

    fun sanitizeForFirebaseConsole(raw: String): String {
        var text = raw
        if (text.startsWith("\uFEFF")) text = text.removePrefix("\uFEFF")
        text = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        // Strip markdown fences if user/app somehow wrapped the file
        if (text.startsWith("```")) {
            text = text.removePrefix("```").removePrefix("rules").trimStart('\n')
            val fence = text.lastIndexOf("```")
            if (fence >= 0) text = text.substring(0, fence).trim()
        }
        // Strip editor/chat line-number prefixes: "   12|rules_version..."
        text = text.lineSequence().joinToString("\n") { line ->
            val stripped = LINE_NUMBER_PREFIX.matchEntire(line)?.groupValues?.getOrNull(1)
            stripped ?: line
        }.trim()
        // Guard against accidentally copying Kotlin source escapes
        text = text.replace("\${'\$'}", "$").replace("\\$", "$")
        return text
    }

    private val LINE_NUMBER_PREFIX = Regex("""^\s*\d+\|(.*)$""")
}
