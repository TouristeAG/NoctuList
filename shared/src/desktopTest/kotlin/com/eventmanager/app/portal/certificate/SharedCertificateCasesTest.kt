package com.eventmanager.app.portal.certificate

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the same cases as webform/functions/test/certificate.test.mjs against the Kotlin assembler. */
class SharedCertificateCasesTest {
    private fun casesFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "webform/functions/test/certificate-cases.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("certificate-cases.json not found above ${File("").absolutePath}")
    }

    private fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
    private fun JsonElement.int(key: String): Int = jsonObject.getValue(key).jsonPrimitive.int

    private fun text(paragraph: ResolvedParagraph) = paragraph.runs.joinToString("") { it.text }

    @Test
    fun kotlinAssemblerAgreesWithSharedCases() {
        val cases = Json.parseToJsonElement(casesFile().readText()).jsonArray
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val name = case.str("name")
            val association = case.str("association")
            val language = case.str("language")
            val override = case.jsonObject["template"]
            val template = if (override != null) {
                assertNotNull(CertificateTemplate.parse(override.toString()), "$name: template")
            } else {
                CertificateDefaults.template(language, association).copy(place = case.str("place"))
            }
            val personJson = case.jsonObject.getValue("person")
            val person = CertPerson(personJson.str("firstName"), personJson.str("lastName"), personJson.str("gender"))
            val types = case.jsonObject.getValue("types").jsonArray.map {
                CertJobType(it.str("name"), CertificateCategory.valueOf(it.str("category")), it.str("description"))
            }
            val jobs = case.jsonObject.getValue("jobs").jsonArray.map { CertJob(it.str("jobTypeName"), it.int("day")) }
            val sel = case.jsonObject.getValue("selection")
            val selection = CertSelection(
                sel.int("fromDay"),
                sel.int("toDay"),
                sel.int("todayDay"),
                sel.jsonObject.getValue("jobTypes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            )

            val actual = CertificateAssembler.assemble(template, association, person, types, jobs, selection)

            val expect = case.jsonObject.getValue("expect")
            if (expect is JsonNull) {
                assertNull(actual, name)
                continue
            }
            assertNotNull(actual, name)
            assertEquals(
                expect.jsonObject.getValue("body").jsonArray.map { it.jsonPrimitive.content },
                actual.body.map(::text),
                "$name: body",
            )
            assertEquals(
                expect.jsonObject.getValue("footer").jsonArray.map { it.jsonPrimitive.content },
                actual.footer.map(::text),
                "$name: footer",
            )
            val styles = expect.jsonObject["bodyRuns"]
            if (styles != null) {
                for ((index, runs) in styles.jsonObject) {
                    val expectedRuns = runs.jsonArray.map {
                        ResolvedRun(
                            it.str("text"),
                            it.jsonObject.getValue("b").jsonPrimitive.content.toBoolean(),
                            it.jsonObject.getValue("i").jsonPrimitive.content.toBoolean(),
                            it.jsonObject.getValue("u").jsonPrimitive.content.toBoolean(),
                        )
                    }
                    assertEquals(expectedRuns, actual.body[index.toInt()].runs, "$name: runs of paragraph $index")
                }
            }
        }
    }
}
