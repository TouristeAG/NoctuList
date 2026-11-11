package com.eventmanager.app.setup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteWorkspaceTest {
    private val root: File = Files.createTempDirectory("site ws").toFile().resolve("Files/site 4")

    private val bundled = mapOf(
        "files/webform/index.html" to "<html></html>",
        "files/webform/functions/package.json" to "{}",
        "files/webform/functions/lib/index.js" to "export {}",
        "files/webform/firebase.json" to """
            {"hosting":{"public":".","ignore":["firebase.json","**/.*","functions/**"]},
             "functions":[{"source":"functions","codebase":"noctulist","runtime":"nodejs22"}]}
        """.trimIndent(),
        "files/firestore.rules" to "rules_version = '2';\nservice cloud.firestore {}",
        "files/storage.rules" to "rules_version = '2';\nservice firebase.storage {}",
    )

    private val workspace = SiteWorkspace(
        root = root,
        siteFiles = listOf("index.html", "functions/package.json", "functions/lib/index.js", "firebase.json"),
        readResource = { path -> bundled.getValue(path).encodeToByteArray() },
    )

    @AfterTest
    fun cleanUp() {
        root.parentFile.parentFile.deleteRecursively()
    }

    private fun config(dir: File): JsonObject = Json.parseToJsonElement(dir.resolve("firebase.json").readText()).jsonObject

    @Test
    fun firebaseJsonKeepsFunctionsOffHostingAndAddsRules() = runBlocking {
        val dir = workspace.prepare("demo-project", withStorage = false)
        val c = config(dir)
        val ignore = c.getValue("hosting").jsonObject.getValue("ignore").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("functions/**" in ignore)
        assertTrue("firestore.rules" in ignore, "rules must not be served on the public site")
        assertTrue("storage.rules" in ignore)
        assertEquals("firestore.rules", c.getValue("firestore").jsonObject.getValue("rules").jsonPrimitive.content)
        assertFalse("storage" in c)
        assertTrue(dir.resolve("firestore.rules").readText().contains("cloud.firestore"))
        assertTrue(dir.resolve("functions/lib/index.js").isFile)
        assertTrue(dir.absolutePath.contains("site 4"))
    }

    @Test
    fun storageOnlyWhenEnabled() = runBlocking {
        val dir = workspace.prepare("demo-project", withStorage = true)
        assertEquals("storage.rules", config(dir).getValue("storage").jsonObject.getValue("rules").jsonPrimitive.content)
        assertTrue(dir.resolve("storage.rules").isFile)
    }

    @Test
    fun firebasercPointsToProject() = runBlocking {
        val dir = workspace.prepare("demo-project", withStorage = false)
        val rc = Json.parseToJsonElement(dir.resolve(".firebaserc").readText()).jsonObject
        assertEquals("demo-project", rc.getValue("projects").jsonObject.getValue("default").jsonPrimitive.content)
    }

    @Test
    fun functionParamsFileHasExactlyTwoLines() = runBlocking {
        val dir = workspace.prepare("demo-project", withStorage = false)
        workspace.writeFunctionParams(dir, "demo-project", "info@example.org", "europe-west6")
        assertEquals(
            "GMAIL_USER=info@example.org\nREGION=europe-west6\n",
            dir.resolve("functions/.env.demo-project").readText(),
        )
    }

    @Test
    fun existingNodeModulesSurvive() = runBlocking {
        val dir = workspace.prepare("demo-project", withStorage = false)
        val kept = dir.resolve("functions/node_modules/x/index.js").apply { parentFile.mkdirs(); writeText("x") }
        workspace.prepare("demo-project", withStorage = false)
        assertTrue(kept.isFile)
    }

    @Test
    fun noSecretEverLandsOnDisk() = runBlocking {
        val dir = workspace.prepare("demo-project", withStorage = true)
        workspace.writeFunctionParams(dir, "demo-project", "info@example.org", "europe-west1")
        val secret = "abcd efgh ijkl mnop"
        assertFalse(dir.walkTopDown().filter { it.isFile }.any { it.readText().contains(secret) })
        assertTrue(config(dir).getValue("functions") is JsonArray)
    }
}
