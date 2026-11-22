package com.eventmanager.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android compiles regular expressions with ICU, which rejects a literal "}" that is not escaped,
 * while the desktop JVM accepts it. A pattern like `\{(\w+)}` therefore passes every desktop test
 * and crashes the Android app the first time it is used. This test reads every `Regex(...)`
 * literal shared with Android and fails on an unescaped "}" that does not close a `{n,m}` quantifier.
 */
class AndroidRegexPortabilityTest {

    private fun sourceRoots(): List<File> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val shared = File(dir, "shared/src")
            if (shared.isDirectory) return listOf(File(shared, "commonMain/kotlin"), File(shared, "androidMain/kotlin"))
            if (File(dir, "src/commonMain/kotlin").isDirectory) {
                return listOf(File(dir, "src/commonMain/kotlin"), File(dir, "src/androidMain/kotlin"))
            }
            dir = dir.parentFile
        }
        error("shared/src not found above ${File("").absolutePath}")
    }

    /** Pattern text of each `Regex("""...""")` and `Regex("...")` literal, with Kotlin escapes undone. */
    private fun patterns(source: String): List<String> {
        val out = mutableListOf<String>()
        Regex("Regex\\(\"\"\"(.*?)\"\"\"").findAll(source).forEach { out += it.groupValues[1] }
        Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source).forEach { m ->
            out += m.groupValues[1].replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")
        }
        return out
    }

    /** Index of an unescaped "}" outside a character class that does not close a quantifier, or -1. */
    internal fun badBrace(pattern: String): Int {
        var i = 0
        var inClass = false
        var quantifierOpen = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    // \p{L}, \P{L}, \x{41}, \N{name}: the braces belong to the escape.
                    val next = pattern.getOrNull(i + 1)
                    if (next in setOf('p', 'P', 'x', 'N') && pattern.getOrNull(i + 2) == '{') {
                        val close = pattern.indexOf('}', i + 3)
                        i = if (close < 0) pattern.length else close + 1
                    } else {
                        i += 2
                    }
                    continue
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> quantifierOpen = Regex("""\{\d+(,\d*)?\}""").matchesAt(pattern, i)
                c == '}' -> { if (!quantifierOpen) return i; quantifierOpen = false }
            }
            i++
        }
        return -1
    }

    @Test
    fun detectsTheKnownAndroidCrash() {
        assertTrue(badBrace("""\{(\w+)}""") >= 0)
        assertTrue(badBrace("""\{(\w+)\}""") < 0)
        assertTrue(badBrace("^[a-z0-9-]{4,62}$") < 0)
        assertTrue(badBrace("^[^<>{}\\\\]{1,60}$") < 0)
        assertTrue(badBrace("""(?<=\p{Ll})(?=\p{Lu})""") < 0)
    }

    @Test
    fun sharedRegexLiteralsCompileOnAndroid() {
        val offenders = sourceRoots().filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                patterns(file.readText()).filter { badBrace(it) >= 0 }.map { "${file.relativeTo(root)}: $it" }
            }
        }
        assertTrue(offenders.isEmpty(), "Escape \"}\" in these patterns (Android ICU rejects it):\n" + offenders.joinToString("\n"))
    }
}
