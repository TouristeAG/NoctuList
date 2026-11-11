package com.eventmanager.app.portal

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs the same cases as webform/functions/test/validate.test.mjs against the Kotlin validator. */
class SharedValidationCasesTest {
    private fun casesFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "webform/functions/test/validation-cases.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("validation-cases.json not found above ${File("").absolutePath}")
    }

    private fun JsonElement.toKotlin(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> boolean
            else -> content.toDoubleOrNull()
        }
        is JsonArray -> map { it.toKotlin() }
        is JsonObject -> mapValues { it.value.toKotlin() }
    }

    private fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content

    @Test
    fun kotlinValidatorAgreesWithSharedCases() {
        val cases = Json.parseToJsonElement(casesFile().readText()).jsonArray
        assertTrue(cases.size >= 25)
        cases.forEachIndexed { index, case ->
            val fn = case.str("fn")
            val input = case.jsonObject.getValue("input")
            val expect = case.jsonObject.getValue("expect")
            val label = "#$index $fn $input"
            when (fn) {
                "cleanName", "cleanPhone", "cleanEmail" -> {
                    val raw = input.toKotlin()
                    val actual = when (fn) {
                        "cleanName" -> PortalInputValidator.cleanName(raw)
                        "cleanPhone" -> PortalInputValidator.cleanPhone(raw)
                        else -> PortalInputValidator.cleanEmail(raw)
                    }
                    val expected = (expect as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    assertEquals(expected, actual, label)
                }
                "parseSignup" -> {
                    @Suppress("UNCHECKED_CAST")
                    val data = input.jsonObject.getValue("data").toKotlin() as Map<String, Any?>
                    val actual = PortalInputValidator.parseSignup(input.str("formId"), input.str("orgId"), data)
                    val actualMap = actual?.let {
                        mapOf(
                            "formId" to it.formId, "uid" to it.uid, "email" to it.email,
                            "firstName" to it.firstName, "lastName" to it.lastName, "phone" to it.phone,
                            "dateOfBirth" to it.dateOfBirth, "gender" to it.gender.name,
                            "photoPath" to it.photoPath, "lang" to it.lang,
                        )
                    }
                    assertEquals(expect.toKotlin(), actualMap, label)
                }
                "parseRequest" -> {
                    @Suppress("UNCHECKED_CAST")
                    val data = input.jsonObject.getValue("data").toKotlin() as Map<String, Any?>
                    val actual = PortalInputValidator.parseRequest(input.str("requestId"), data)
                    val actualMap = actual?.let {
                        mapOf(
                            "requestId" to it.requestId, "uid" to it.uid, "type" to it.type.name,
                            "status" to it.status, "email" to it.email, "firstName" to it.firstName,
                            "lastName" to it.lastName, "phone" to it.phone, "photo" to it.photo,
                        )
                    }
                    assertEquals(expect.toKotlin(), actualMap, label)
                }
                else -> error("unknown fn $fn")
            }
        }
    }
}
