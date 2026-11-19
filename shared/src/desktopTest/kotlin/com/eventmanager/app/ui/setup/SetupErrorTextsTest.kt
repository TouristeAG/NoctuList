package com.eventmanager.app.ui.setup

import com.eventmanager.app.setup.SetupErrorId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupErrorTextsTest {
    private val keys = SetupErrorId.entries.flatMap { id ->
        listOf("title", "cause", "steps").map { "setup_err_${id.name.lowercase()}_$it" }
    }

    private fun values(folder: String): Map<String, String> {
        val text = File("src/commonMain/composeResources/$folder/strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun everyErrorIdHasThreeStringsInEnglishAndFrench() {
        for (folder in listOf("values", "values-fr")) {
            val v = values(folder)
            val missing = keys.filter { v[it].isNullOrBlank() }
            assertEquals(emptyList(), missing, "missing in $folder")
        }
    }

    @Test
    fun noErrorStringUsesEmDash() {
        for (folder in listOf("values", "values-fr")) {
            val v = values(folder)
            assertEquals(emptyList(), keys.filter { v[it]?.contains('—') == true }, folder)
        }
    }

    @Test
    fun stepsAreNumberedFromOne() {
        for (folder in listOf("values", "values-fr")) {
            val v = values(folder)
            for (id in SetupErrorId.entries) {
                val steps = v["setup_err_${id.name.lowercase()}_steps"].orEmpty()
                assertTrue(steps.startsWith("1. "), "$id in $folder")
            }
        }
    }
}
