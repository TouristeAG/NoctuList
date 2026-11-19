package com.eventmanager.app.setup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class GoogleApiError(val status: Int, val reason: String, val message: String, val service: String)

class GoogleOpsException(val error: GoogleApiError) : Exception(error.message)

data class BillingAccount(val name: String, val displayName: String)
data class BillingStatus(val enabled: Boolean, val accounts: List<BillingAccount>)
data class FunctionState(val name: String, val region: String, val state: String, val messages: List<String>)
data class MailServiceAccount(val email: String, val clientId: String)

/** Google API calls the Firebase CLI cannot make, through the bundled helper script. */
interface GoogleOps {
    suspend fun checkAuth(): String
    suspend fun projectNumber(projectId: String): String
    suspend fun missingPermissions(projectId: String, permissions: List<String>): List<String>
    suspend fun enableApis(projectId: String, apis: List<String>)
    suspend fun ensureRole(projectId: String, member: String, role: String): Boolean
    suspend fun billing(projectId: String): BillingStatus
    suspend fun linkBilling(projectId: String, account: String)
    suspend fun functions(projectId: String, codebase: String): List<FunctionState>
    suspend fun storageBucket(projectId: String): String?
    suspend fun rulesReleased(projectId: String): Pair<Boolean, Boolean>
    suspend fun hostingLive(projectId: String): Boolean
    suspend fun ensureMailServiceAccount(projectId: String): MailServiceAccount
}

/**
 * Runs `node <helper> <firebase-tools lib dir> <command> --project <id> ...`. Arguments are
 * validated here before any process starts; the helper's output is the final JSON line.
 */
class HelperGoogleOps(
    private val runner: ProcessRunner,
    private val tools: ToolPaths,
    private val helper: File,
    private val env: SetupEnvironment,
    private val logs: SetupLogFiles,
    private val redactor: OutputRedactor,
) : GoogleOps {
    /** Step the helper's output is logged under; the runner sets it before each call. */
    @Volatile
    var step: SetupStepId = SetupStepId.TOOLS

    override suspend fun checkAuth(): String =
        call("check-auth", null, emptyList()).str("email").orEmpty()

    override suspend fun projectNumber(projectId: String): String =
        call("project-number", projectId, emptyList()).str("number").orEmpty()

    override suspend fun missingPermissions(projectId: String, permissions: List<String>): List<String> {
        require(permissions.isNotEmpty() && permissions.all { PERMISSION.matches(it) }) { "invalid permission" }
        return call("test-permissions", projectId, listOf("--permissions", permissions.joinToString(",")))
            .strings("missing")
    }

    override suspend fun enableApis(projectId: String, apis: List<String>) {
        require(apis.isNotEmpty() && apis.all { API.matches(it) }) { "invalid api" }
        call("enable-apis", projectId, listOf("--apis", apis.joinToString(",")), LONG)
    }

    override suspend fun ensureRole(projectId: String, member: String, role: String): Boolean {
        require(MEMBER.matches(member)) { "invalid member" }
        require(ROLE.matches(role)) { "invalid role" }
        return call("ensure-role", projectId, listOf("--member", member, "--role", role)).bool("changed")
    }

    override suspend fun billing(projectId: String): BillingStatus {
        val d = call("billing-status", projectId, emptyList())
        val accounts = (d["accounts"] as? JsonArray).orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            BillingAccount(o.str("name") ?: return@mapNotNull null, o.str("displayName").orEmpty())
        }
        return BillingStatus(d.bool("enabled"), accounts)
    }

    override suspend fun linkBilling(projectId: String, account: String) {
        require(BILLING.matches(account)) { "invalid billing account" }
        call("link-billing", projectId, listOf("--billing-account", account))
    }

    override suspend fun functions(projectId: String, codebase: String): List<FunctionState> {
        require(CODEBASE.matches(codebase)) { "invalid codebase" }
        return (call("functions-status", projectId, listOf("--codebase", codebase))["functions"] as? JsonArray).orEmpty()
            .mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                FunctionState(o.str("name") ?: return@mapNotNull null, o.str("region").orEmpty(), o.str("state").orEmpty(), o.strings("messages"))
            }
    }

    override suspend fun storageBucket(projectId: String): String? =
        call("storage-status", projectId, emptyList()).str("bucket")

    override suspend fun rulesReleased(projectId: String): Pair<Boolean, Boolean> {
        val d = call("rules-status", projectId, emptyList())
        return d.bool("firestore") to d.bool("storage")
    }

    override suspend fun hostingLive(projectId: String): Boolean =
        call("hosting-status", projectId, emptyList()).bool("live")

    override suspend fun ensureMailServiceAccount(projectId: String): MailServiceAccount {
        val d = call("ensure-mail-sa", projectId, emptyList(), LONG)
        return MailServiceAccount(d.str("email").orEmpty(), d.str("clientId").orEmpty())
    }

    private suspend fun call(command: String, projectId: String?, extra: List<String>, limits: CommandLimits = CommandLimits.READ): JsonObject {
        if (projectId != null) require(SetupValidators.projectId(projectId)) { "invalid project id" }
        val libDir = tools.firebase.parentFile.parentFile
        val cmd = listOf(tools.node.path, helper.path, libDir.path, command) +
            (if (projectId != null) listOf("--project", projectId) else emptyList()) + extra
        val log = logs.sink(step)
        val lines = mutableListOf<String>()
        runner.run(cmd, env.workDir, env.childEnv(tools.binDir, System.getenv("PATH").orEmpty()), null, limits) {
            log(it)
            lines += it
        }
        return parse(lines)
    }

    private fun parse(lines: List<String>): JsonObject {
        val json = lines.lastOrNull { it.trimStart().startsWith("{") }
            ?.let { runCatching { Json.parseToJsonElement(it.trim()).jsonObject }.getOrNull() }
        if (json == null || json["ok"]?.jsonPrimitive?.booleanOrNull == null) {
            val last = lines.map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
            throw GoogleOpsException(GoogleApiError(0, "HELPER_CRASHED", redactor.redact(last), ""))
        }
        if (json["ok"]?.jsonPrimitive?.booleanOrNull == true) return json["data"] as? JsonObject ?: JsonObject(emptyMap())
        val e = json["error"] as? JsonObject
        throw GoogleOpsException(
            GoogleApiError(
                e?.get("status")?.jsonPrimitive?.intOrNull ?: 0,
                e?.str("reason").orEmpty(),
                redactor.redact(e?.str("message").orEmpty()),
                e?.str("service").orEmpty(),
            ),
        )
    }

    private fun JsonObject.str(k: String): String? = (get(k) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean = (get(k) as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.strings(k: String): List<String> =
        (get(k) as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

    private companion object {
        // Above the helper's own five minute polling.
        val LONG = CommandLimits(420_000, 420_000)
        val MEMBER = Regex("^(serviceAccount|user|group):[^\\s;]+$")
        val ROLE = Regex("^roles/[A-Za-z.]+$")
        val API = Regex("^[a-z0-9.-]+\\.googleapis\\.com$")
        val BILLING = Regex("^billingAccounts/[A-Z0-9-]+$")
        val PERMISSION = Regex("^[A-Za-z0-9._]+$")
        val CODEBASE = Regex("^[A-Za-z0-9_-]{1,63}$")
    }
}
