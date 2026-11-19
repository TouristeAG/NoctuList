package com.eventmanager.app.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The folder the app deploys from: the bundled form site, the functions and the rules, plus a
 * firebase.json that publishes them all. Replaces the zip the admin used to unzip by hand.
 */
class SiteWorkspace(
    private val root: File,
    private val siteFiles: List<String>,
    private val readResource: suspend (String) -> ByteArray,
) {
    suspend fun prepare(projectId: String, withStorage: Boolean): File {
        require(SetupValidators.projectId(projectId)) { "invalid project id" }
        val dir = root.resolve(projectId).apply { mkdirs() }
        for (name in siteFiles) {
            if (name == FIREBASE_JSON) continue
            val target = dir.resolve(name)
            target.parentFile.mkdirs()
            target.writeBytes(readResource("files/webform/$name"))
        }
        dir.resolve(FIRESTORE_RULES).writeBytes(readResource("files/$FIRESTORE_RULES"))
        val storageRules = dir.resolve(STORAGE_RULES)
        if (withStorage) storageRules.writeBytes(readResource("files/$STORAGE_RULES")) else storageRules.delete()

        val bundled = Json.parseToJsonElement(readResource("files/webform/$FIREBASE_JSON").decodeToString()).jsonObject
        val config = JsonObject(
            bundled + mapOf("hosting" to hostingWithoutRules(bundled["hosting"])) + buildMap {
                put("firestore", buildJsonObject { put("rules", FIRESTORE_RULES) })
                if (withStorage) put("storage", buildJsonObject { put("rules", STORAGE_RULES) })
            },
        )
        dir.resolve(FIREBASE_JSON).writeText(prettyJson.encodeToString(JsonObject.serializer(), config))
        val rc = buildJsonObject { put("projects", buildJsonObject { put("default", JsonPrimitive(projectId)) }) }
        dir.resolve(".firebaserc").writeText(prettyJson.encodeToString(JsonObject.serializer(), rc))
        return dir
    }

    /** The rules files sit in the public folder for the deploy; they must not be served. */
    private fun hostingWithoutRules(hosting: kotlinx.serialization.json.JsonElement?): JsonObject {
        val h = hosting?.jsonObject ?: JsonObject(emptyMap())
        val ignore = (h["ignore"] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
        val extra = listOf(FIRESTORE_RULES, STORAGE_RULES).map(::JsonPrimitive).filterNot { it in ignore }
        return JsonObject(h + ("ignore" to kotlinx.serialization.json.JsonArray(ignore + extra)))
    }

    /**
     * Non-secret function parameters, so the deploy never stops to ask. [from] and [sa] are only used in
     * [MailMode.WORKSPACE], where both are required; in [MailMode.APP_PASSWORD] they may be blank.
     */
    fun writeFunctionParams(dir: File, projectId: String, gmailUser: String, region: String, mode: MailMode, from: String, sa: String) {
        require(SetupValidators.projectId(projectId)) { "invalid project id" }
        require(SetupValidators.gmail(gmailUser)) { "invalid gmail address" }
        require(REGION.matches(region)) { "invalid region" }
        val workspace = mode == MailMode.WORKSPACE
        require(if (workspace) SetupValidators.gmail(from) else from.isEmpty() || SetupValidators.gmail(from)) { "invalid from address" }
        require(if (workspace) SetupValidators.gmail(sa) else sa.isEmpty() || SetupValidators.gmail(sa)) { "invalid service account" }
        val file = dir.resolve("functions/.env.$projectId")
        file.parentFile.mkdirs()
        file.writeText("GMAIL_USER=$gmailUser\nREGION=$region\nMAIL_MODE=${mode.param}\nMAIL_FROM=$from\nMAIL_SA=$sa\n")
    }

    private companion object {
        const val FIREBASE_JSON = "firebase.json"
        const val FIRESTORE_RULES = "firestore.rules"
        const val STORAGE_RULES = "storage.rules"
        val REGION = Regex("^[a-z]+-[a-z]+[0-9]+$")
        val prettyJson = Json { prettyPrint = true }
    }
}
