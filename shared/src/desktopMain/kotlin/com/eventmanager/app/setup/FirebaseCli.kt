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
    private val env: SetupEnvironment,
    private val logs: SetupLogFiles,
) : SetupCli {
    /**
     * Without a terminal the CLI uses its remote sign-in: it prints a session ID and a URL, and
     * the admin brings back the code that page shows.
     */
    override suspend fun loginStart(): LoginChallenge {
        val lines = exec(listOf("login"), step = SetupStepId.LOGIN).lines().map { it.replace(ANSI, "").trim() }
        val url = lines.firstOrNull { it.startsWith("https://") }
        val sessionLine = lines.indexOfFirst { it.contains("session ID", ignoreCase = true) }
        val session = lines.drop(sessionLine + 1).firstOrNull { it.isNotBlank() }
        if (url == null || sessionLine < 0 || session == null) {
            throw CliException(ErrorCatalog.classify(lines.joinToString("\n"), SetupStepId.LOGIN))
        }
        return LoginChallenge(session, url)
    }

    override suspend fun loginFinish(code: String) {
        require(SetupValidators.authCode(code)) { "invalid authorization code" }
        exec(listOf("login", code), step = SetupStepId.LOGIN)
    }

    /** E-mails of the signed-in accounts. The raw output holds tokens: it is never shown. */
    override suspend fun loginList(): List<String> =
        result(readJson(listOf("login:list", "--json"), SetupStepId.LOGIN, show = false)).arr()
            .mapNotNull { it.obj()?.get("user")?.obj()?.str("email") }

    override suspend fun logout() {
        exec(listOf("logout"), step = SetupStepId.LOGIN)
    }

    override suspend fun projectsList(): List<ProjectInfo> =
        result(readJson(listOf("projects:list", "--json"), SetupStepId.PROJECT)).arr().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            ProjectInfo(o.str("projectId") ?: return@mapNotNull null, o.str("displayName").orEmpty())
        }

    override suspend fun projectsCreate(id: String, displayName: String, onLine: (String) -> Unit) {
        require(SetupValidators.projectId(id)) { "invalid project id" }
        require(SetupValidators.displayName(displayName)) { "invalid display name" }
        exec(listOf("projects:create", id, "--display-name", displayName), SetupStepId.PROJECT, onLine = onLine, projectId = id)
    }

    override suspend fun databases(projectId: String): List<DatabaseInfo> =
        result(readJson(listOf("firestore:databases:list") + project(projectId) + "--json", SetupStepId.DATABASE, projectId = projectId)).arr()
            .mapNotNull {
                val o = it.obj() ?: return@mapNotNull null
                DatabaseInfo(o.str("name")?.substringAfterLast('/') ?: return@mapNotNull null, o.str("locationId").orEmpty())
            }

    override suspend fun firestoreLocations(projectId: String): List<String> =
        result(readJson(listOf("firestore:locations") + project(projectId) + "--json", SetupStepId.DATABASE, projectId = projectId)).arr()
            .mapNotNull { it.obj()?.str("locationId") }

    override suspend fun createDatabase(projectId: String, location: String, allowed: List<String>, onLine: (String) -> Unit) {
        require(SetupValidators.location(location, allowed)) { "invalid location" }
        exec(
            listOf("firestore:databases:create", "(default)", "--location", location) + project(projectId),
            SetupStepId.DATABASE,
            onLine = onLine,
            projectId = projectId,
        )
    }

    override suspend fun webApps(projectId: String): List<String> =
        result(readJson(listOf("apps:list", "WEB") + project(projectId) + "--json", SetupStepId.CONNECT, projectId = projectId)).arr()
            .mapNotNull { it.obj()?.str("appId") }

    override suspend fun createWebApp(projectId: String, onLine: (String) -> Unit): String {
        val out = readJson(listOf("apps:create", "WEB", "NoctuList") + project(projectId) + "--json", SetupStepId.CONNECT, onLine, projectId = projectId)
        return result(out).obj()?.str("appId") ?: throw CliException(ErrorCatalog.classify(out.raw, SetupStepId.CONNECT, projectId))
    }

    override suspend fun sdkConfig(projectId: String, appId: String): WebConfig {
        require(SetupValidators.webAppId(appId)) { "invalid app id" }
        val out = readJson(listOf("apps:sdkconfig", "WEB", appId) + project(projectId) + "--json", SetupStepId.CONNECT, projectId = projectId)
        val cfg = result(out).obj()?.get("sdkConfig")?.obj()
            ?: throw CliException(ErrorCatalog.classify(out.raw, SetupStepId.CONNECT, projectId))
        return WebConfig(cfg.str("apiKey").orEmpty(), cfg.str("appId").orEmpty(), cfg.str("projectId").orEmpty())
    }

    override suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit) {
        require(ONLY.matches(only)) { "invalid deploy target" }
        exec(
            listOf("deploy", "--only", only, "--non-interactive", "--force") + project(projectId),
            stepFor(only),
            workDir = dir,
            onLine = onLine,
            projectId = projectId,
        )
    }

    /** Default Hosting site named after the project (a project made in the console may have none). */
    override suspend fun createHostingSite(projectId: String, onLine: (String) -> Unit) {
        exec(listOf("hosting:sites:create", projectId, "--non-interactive") + project(projectId), SetupStepId.SITE, onLine = onLine, projectId = projectId)
    }

    override suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
        require(SECRET_NAME.matches(name)) { "invalid secret name" }
        val bytes = String(value).encodeToByteArray()
        try {
            exec(
                listOf("functions:secrets:set", name, "--data-file", "-", "--force") + project(projectId),
                SetupStepId.SERVER,
                workDir = dir,
                stdin = bytes,
                onLine = onLine,
                projectId = projectId,
            )
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun npmInstall(dir: File, onLine: (String) -> Unit) {
        val lines = StringBuilder()
        val log = logs.sink(SetupStepId.SERVER)
        val code = runner.run(tools.npmCommand() + listOf("install", "--no-audit", "--no-fund"), dir, env(), null, CommandLimits.NPM) {
            log(it)
            val safe = redactor.redact(it)
            lines.appendLine(safe)
            onLine(safe)
        }
        if (code != 0) throw CliException(ErrorCatalog.classify(lines.toString(), SetupStepId.SERVER))
    }

    override suspend fun functionsList(projectId: String): List<FunctionInfo> =
        result(readJson(listOf("functions:list") + project(projectId) + "--json", SetupStepId.SERVER, projectId = projectId)).arr()
            .mapNotNull {
                val o = it.obj() ?: return@mapNotNull null
                FunctionInfo(o.str("id") ?: return@mapNotNull null, o.str("region").orEmpty())
            }

    override suspend fun setArtifactPolicy(projectId: String, region: String, onLine: (String) -> Unit) {
        require(REGION.matches(region)) { "invalid region" }
        exec(
            listOf("functions:artifacts:setpolicy", "--location", region, "--force") + project(projectId),
            SetupStepId.SERVER,
            onLine = onLine,
            projectId = projectId,
        )
    }

    override suspend fun deleteFunctions(projectId: String, names: List<String>, region: String, onLine: (String) -> Unit) {
        require(names.isNotEmpty() && names.all { FUNCTION_NAME.matches(it) }) { "invalid function name" }
        require(REGION.matches(region)) { "invalid region" }
        exec(
            listOf("functions:delete") + names + listOf("--region", region, "--force") + project(projectId),
            SetupStepId.SERVER,
            onLine = onLine,
            projectId = projectId,
        )
    }

    private class Output(val raw: String, val json: JsonElement?)

    private fun project(id: String): List<String> {
        require(SetupValidators.projectId(id)) { "invalid project id" }
        return listOf("--project", id)
    }

    private fun env(): Map<String, String> = env.childEnv(tools.binDir, System.getenv("PATH").orEmpty())

    private fun limitsFor(args: List<String>): CommandLimits = when (args.firstOrNull()) {
        "deploy", "functions:delete", "projects:create", "apps:create", "firestore:databases:create", "hosting:sites:create" -> CommandLimits.DEPLOY
        "login", "logout" -> CommandLimits.LOGIN
        else -> CommandLimits.READ
    }

    private suspend fun exec(
        args: List<String>,
        step: SetupStepId,
        workDir: File? = null,
        stdin: ByteArray? = null,
        show: Boolean = true,
        onLine: (String) -> Unit = {},
        projectId: String = "",
    ): String {
        val lines = StringBuilder()
        val log = logs.sink(step)
        val cwd = workDir ?: env.workDir
        val code = try {
            runner.run(tools.firebaseCommand() + args, cwd, env(), stdin, limitsFor(args)) {
                log(it)
                val safe = redactor.redact(it)
                lines.appendLine(safe)
                if (show) onLine(safe)
            }
        } catch (e: Throwable) {
            // Timeout or cancellation: the debug log is the best clue to what happened.
            keepDebugLog(cwd, true, log)
            throw e
        }
        keepDebugLog(cwd, code != 0, log)
        val text = lines.toString()
        if (code != 0) {
            throw CliException(ErrorCatalog.classify(if (show) text else "exit $code", step, projectId))
        }
        return text
    }

    /** The CLI's own debug log holds the real server replies: on failure it goes to our log file only, never to the screen. */
    private fun keepDebugLog(dir: File, failed: Boolean, log: (String) -> Unit) {
        val file = dir.resolve("firebase-debug.log")
        if (!file.isFile) return
        if (failed) runCatching { file.readLines(Charsets.UTF_8).forEach(log) }
        file.delete()
    }

    private suspend fun readJson(
        args: List<String>,
        step: SetupStepId,
        onLine: (String) -> Unit = {},
        show: Boolean = true,
        projectId: String = "",
    ): Output {
        val raw = exec(args, step, show = show, onLine = if (show) onLine else { _ -> }, projectId = projectId)
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
        val FUNCTION_NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{0,62}$")
        val ANSI = Regex("\u001B\\[[0-9;]*m")
    }
}
