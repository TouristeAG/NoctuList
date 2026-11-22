package com.eventmanager.app.portal.certificate

import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the Kotlin defaults and the TypeScript defaults (webform/functions/src/certificate.ts) identical:
 * both are compared to the same snapshot of the French template.
 */
class CertificateSnapshotTest {
    private fun testDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "webform/functions/test")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("webform/functions/test not found above ${File("").absolutePath}")
    }

    @Test
    fun defaultsMatchSnapshot() {
        val file = File(testDir(), "certificate-default-fr.json")
        val actual = CertificateDefaults.template("fr", "X").toJson()
        if (!file.isFile) {
            file.writeText(actual + "\n")
            return
        }
        assertEquals(
            Json.parseToJsonElement(file.readText()),
            Json.parseToJsonElement(actual),
            "Defaults changed: update ${file.name} and certificate.ts together",
        )
    }
}
