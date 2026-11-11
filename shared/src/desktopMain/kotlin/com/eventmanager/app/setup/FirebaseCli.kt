package com.eventmanager.app.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class ProjectInfo(val id: String, val displayName: String)
data class DatabaseInfo(val name: String, val locationId: String)
data class WebConfig(val apiKey: String, val appId: String, val projectId: String)
data class FunctionInfo(val name: String, val region: String)

/**
 * The only way the assistant talks to Firebase: fixed command templates, validated arguments,
 * secrets on stdin, output passed through the redactor before anyone sees it.
 */
class FirebaseCli(
    private val runner: ProcessRunner,
    private val tools: ToolPaths,
    private val redactor: OutputRedactor,
) {
    suspend fun login(onLine: (String) -> Unit) {
        exec(listOf("login"), step = SetupStepId.LOGIN, onLine = onLine)
    }

    /** E-mails of the signed-in accounts. The raw output holds tokens: it is never shown. */
    suspend fun loginList(): List<String> =
        result(readJson(listOf("login:list", "--json"), SetupStepId.LOGIN, show = false)).arr()
            .mapNotNull { it.obj()?.get("user")?.obj()?.str("email") }

    suspend fun projectsList(): List<ProjectInfo> =
        result(readJson(listOf("projects:list", "--json"), SetupStepId.PROJECT)).arr().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            ProjectInfo(o.str("projectId") ?: return@mapNotNull null, o.str("displayName").orEmpty())
        }

    suspend fun projectsCreate(id: String, displayName: String, onLine: (String) -> Unit) {
        require(SetupValidators.projectId(id)) { "invalid project id" }
        require(SetupValidators.displayName(displayName)) { "invalid display name" }
        exec(listOf("projects:create", id, "--display-name", displayName), SetupStepId.PROJECT, onLine = onLine)
    }

    suspend fun databases(projectId: String): List<DatabaseInfo> =
        result(readJson(listOf("firestore:databases:list") + project(projectId) + "--json", SetupStepId.DATABASE)).arr()
            .mapNotNull {
                val o = it.obj() ?: return@mapNotNull null
                DatabaseInfo(o.str("name")?.substringAfterLast('/') ?: return@mapNotNull null, o.str("locationId").orEmpty())
            }

    suspend fun firestoreLocations(projectId: String): List<String> =
        result(readJson(listOf("firestore:locations") + project(projectId) + "--json", SetupStepId.DATABASE)).arr()
            .mapNotNull { it.obj()?.str("locationId") }

    suspend fun createDatabase(projectId: String, location: String, allowed: List<String>, onLine: (String) -> Unit) {
        require(SetupValidators.location(location, allowed)) { "invalid location" }
        exec(
            listOf("firestore:databases:create", "(default)", "--location", location) + project(projectId),
            SetupStepId.DATABASE,
            onLine = onLine,
        )
    }

    suspend fun webApps(projectId: String): List<String> =
        result(readJson(listOf("apps:list", "WEB") + project(projectId) + "--json", SetupStepId.CONNECT)).arr()
            .mapNotNull { it.obj()?.str("appId") }

    suspend fun createWebApp(projectId: String, onLine: (String) -> Unit): String {
        val out = readJson(listOf("apps:create", "WEB", "NoctuList") + project(projectId) + "--json", SetupStepId.CONNECT, onLine)
        return result(out).obj()?.str("appId") ?: throw CliException(CliErrorClassifier.classify(out.raw, SetupStepId.CONNECT))
    }

    suspend fun sdkConfig(projectId: String, appId: String): WebConfig {
        require(SetupValidators.webAppId(appId)) { "invalid app id" }
        val out = readJson(listOf("apps:sdkconfig", "WEB", appId) + project(projectId) + "--json", SetupStepId.CONNECT)
        val cfg = result(out).obj()?.get("sdkConfig")?.obj()
            ?: throw CliException(CliErrorClassifier.classify(out.raw, SetupStepId.CONNECT))
        return WebConfig(cfg.str("apiKey").orEmpty(), cfg.str("appId").orEmpty(), cfg.str("projectId").orEmpty())
    }

    suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit) {
        require(ONLY.matches(only)) { "invalid deploy target" }
        exec(
            listOf("deploy", "--only", only, "--non-interactive", "--force") + project(projectId),
            stepFor(only),
            workDir = dir,
            onLine = onLine,
        )
    }

    suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
        require(SECRET_NAME.matches(name)) { "invalid secret name" }
        val bytes = String(value).encodeToByteArray()
        try {
            exec(
                listOf("functions:secrets:set", name, "--data-file", "-", "--force") + project(projectId),
                SetupStepId.SERVER,
                workDir = dir,
                stdin = bytes,
                onLine = onLine,
            )
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun npmInstall(dir: File, onLine: (String) -> Unit) {
        val lines = StringBuilder()
        val code = runner.run(listOf(tools.npm.path, "install", "--no-audit", "--no-fund"), dir, env(), null) {
            val safe = redactor.redact(it)
            lines.appendLine(safe)
            onLine(safe)
        }
        if (code != 0) throw CliException(CliErrorClassifier.classify(lines.toString(), SetupStepId.SERVER))
    }

    suspend fun functionsList(projectId: String): List<FunctionInfo> =
        result(readJson(listOf("functions:list") + project(projectId) + "--json", SetupStepId.SERVER)).arr()
            .mapNotNull {
                val o = it.obj() ?: return@mapNotNull null
                FunctionInfo(o.str("id") ?: return@mapNotNull null, o.str("region").orEmpty())
            }

    suspend fun setArtifactPolicy(projectId: String, region: String, onLine: (String) -> Unit) {
        require(REGION.matches(region)) { "invalid region" }
        exec(
            listOf("functions:artifacts:setpolicy", "--location", region, "--force") + project(projectId),
            SetupStepId.SERVER,
            onLine = onLine,
        )
    }

    private class Output(val raw: String, val json: JsonElement?)

    private fun project(id: String): List<String> {
        require(SetupValidators.projectId(id)) { "invalid project id" }
        return listOf("--project", id)
    }

    private fun env(): Map<String, String> {
        val current = System.getenv("PATH").orEmpty()
        return mapOf("PATH" to listOf(tools.binDir.path, current).filter { it.isNotBlank() }.joinToString(File.pathSeparator))
    }

    private suspend fun exec(
        args: List<String>,
        step: SetupStepId,
        workDir: File? = null,
        stdin: ByteArray? = null,
        show: Boolean = true,
        onLine: (String) -> Unit = {},
    ): String {
        val lines = StringBuilder()
        val code = runner.run(listOf(tools.firebase.path) + args, workDir, env(), stdin) {
            val safe = redactor.redact(it)
            lines.appendLine(safe)
            if (show) onLine(safe)
        }
        val text = lines.toString()
        if (code != 0) {
            throw CliException(CliErrorClassifier.classify(if (show) text else "exit $code", step))
        }
        return text
    }

    private suspend fun readJson(
        args: List<String>,
        step: SetupStepId,
        onLine: (String) -> Unit = {},
        show: Boolean = true,
    ): Output {
        val raw = exec(args, step, show = show, onLine = if (show) onLine else { _ -> })
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val json = if (start in 0 until end) runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull() else null
        return Output(if (show) raw else "", json)
    }

    private fun result(out: Output): JsonElement? = out.json?.obj()?.get("result")

    private fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.str(key: String): String? = runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun stepFor(only: String): SetupStepId = when {
        "functions" in only -> SetupStepId.SERVER
        "storage" in only -> SetupStepId.PHOTOS
        "hosting" in only -> SetupStepId.SITE
        else -> SetupStepId.RULES
    }

    private companion object {
        val ONLY = Regex("^(hosting|functions|firestore:rules|storage)(,(hosting|functions|firestore:rules|storage))*$")
        val SECRET_NAME = Regex("^[A-Z][A-Z0-9_]{1,63}$")
        val REGION = Regex("^[a-z]+-[a-z]+[0-9]+$")
    }
}
