package com.eventmanager.app.setup

enum class SetupErrorId {
    WORKDIR_NOT_WRITABLE, TOOLS_BROKEN, DOWNLOAD_FAILED, FILE_LOCKED, DISK_FULL, LOGIN_CODE_INVALID, LOGIN_EXPIRED,
    PROJECT_ID_TAKEN, PROJECT_QUOTA, TERMS_NOT_ACCEPTED, NO_PROJECT_CREATOR, API_NOT_ENABLED, DATASTORE_MODE,
    LOCATION_INVALID, PERMISSION_DENIED, NO_HOSTING_SITE, STORAGE_NOT_READY, BILLING_REQUIRED, BUILD_SA_PERMISSION,
    EVENTARC_NOT_READY, TRANSIENT, FUNCTIONS_FAILED_STATE, NPM_INSTALL_FAILED, SECRET_REFUSED, MAIL_AUTH_REFUSED,
    MAIL_DELEGATION_MISSING, MAIL_TEST_FAILED, MAIL_TEST_UNAVAILABLE, TIMEOUT, QUOTA, NETWORK, UNKNOWN,
}

enum class RetryPolicy { NONE, WAIT_AND_RETRY, AFTER_REPAIR }

enum class Repair { ENABLE_APIS, GRANT_BUILD_ROLE, DELETE_FAILED_FUNCTIONS, CREATE_HOSTING_SITE, REINSTALL_TOOLS, RELOGIN }

data class SetupError(
    val id: SetupErrorId,
    val step: SetupStepId,
    val retry: RetryPolicy,
    val repair: Repair?,
    val link: String?,
    val headline: String,
    val details: String,
)

class CliException(val error: SetupError) : Exception(error.id.name)

/** What Google's API answered, when the failure came from a direct call rather than the CLI. */
data class GoogleApiStatus(val status: Int, val reason: String, val service: String)

/** Turns Firebase CLI output into a specific cause, says whether waiting or a repair helps, and links the fix. */
object ErrorCatalog {
    private val I = RegexOption.IGNORE_CASE

    private class Rule(
        val id: SetupErrorId,
        val retry: RetryPolicy,
        val repair: Repair?,
        val link: (String) -> String?,
        val matches: (output: String, step: SetupStepId, api: GoogleApiStatus?) -> Boolean,
    )

    private fun text(vararg patterns: Regex): (String, SetupStepId, GoogleApiStatus?) -> Boolean =
        { out, _, _ -> patterns.any { it.containsMatchIn(out) } }

    private fun inStep(step: SetupStepId, test: (String) -> Boolean): (String, SetupStepId, GoogleApiStatus?) -> Boolean =
        { out, s, _ -> s == step && test(out) }

    private val none: (String) -> String? = { null }
    private fun project(path: String): (String) -> String? =
        { id -> if (id.isBlank()) null else "https://console.firebase.google.com/project/$id/$path" }
    private val functionsConsole: (String) -> String? = { id ->
        "https://console.cloud.google.com/functions/list" + if (id.isBlank()) "" else "?project=$id"
    }
    private val iamPage: (String) -> String? = { id ->
        "https://console.cloud.google.com/iam-admin/iam" + if (id.isBlank()) "" else "?project=$id"
    }

    private val rules: List<Rule> = listOf(
        // A Google call without a valid sign-in, whatever its message says (it can read like a permission error).
        Rule(SetupErrorId.LOGIN_EXPIRED, RetryPolicy.NONE, Repair.RELOGIN, none) { _, _, api ->
            api != null && (api.status == 401 || api.reason == "NOT_LOGGED_IN")
        },
        Rule(SetupErrorId.WORKDIR_NOT_WRITABLE, RetryPolicy.NONE, null, none,
            text(Regex("Unable to obtain permissions for firebase-debug.log"))),
        Rule(SetupErrorId.FUNCTIONS_FAILED_STATE, RetryPolicy.AFTER_REPAIR, Repair.DELETE_FAILED_FUNCTIONS, functionsConsole,
            text(Regex("Changing from an HTTPS function to a background triggered function is not allowed"))),
        Rule(SetupErrorId.BUILD_SA_PERMISSION, RetryPolicy.AFTER_REPAIR, Repair.GRANT_BUILD_ROLE, iamPage,
            text(Regex("missing permission on the build service account"))),
        Rule(SetupErrorId.EVENTARC_NOT_READY, RetryPolicy.WAIT_AND_RETRY, null, none,
            text(Regex("Eventarc Service Agent", I), Regex("first time using 2nd gen functions", I))),
        Rule(SetupErrorId.BILLING_REQUIRED, RetryPolicy.NONE, null, project("usage/details"),
            text(Regex("Blaze", I), Regex("billing account", I), Regex("billing (is )?(not )?enabled", I))),
        Rule(SetupErrorId.API_NOT_ENABLED, RetryPolicy.AFTER_REPAIR, Repair.ENABLE_APIS, none) { out, _, api ->
            api?.reason == "SERVICE_DISABLED" ||
                listOf(
                    Regex("API has not been used in project", I),
                    Regex("SERVICE_DISABLED"),
                    Regex("is disabled\\. Enable it", I),
                ).any { it.containsMatchIn(out) }
        },
        Rule(SetupErrorId.STORAGE_NOT_READY, RetryPolicy.NONE, null, project("storage"),
            text(Regex("Firebase Storage has not been set up"))),
        Rule(SetupErrorId.NO_HOSTING_SITE, RetryPolicy.AFTER_REPAIR, Repair.CREATE_HOSTING_SITE, none,
            text(Regex("no Hosting site"))),
        Rule(SetupErrorId.TERMS_NOT_ACCEPTED, RetryPolicy.NONE, null, { "https://console.firebase.google.com/" },
            text(Regex("terms of service", I))),
        Rule(SetupErrorId.PROJECT_ID_TAKEN, RetryPolicy.NONE, null, none,
            inStep(SetupStepId.PROJECT) { "ALREADY_EXISTS" in it || "already exists" in it }),
        Rule(SetupErrorId.PROJECT_QUOTA, RetryPolicy.NONE, null,
            { "https://support.google.com/code/contact/project_quota_increase" },
            inStep(SetupStepId.PROJECT) { "quota" in it }),
        Rule(SetupErrorId.NO_PROJECT_CREATOR, RetryPolicy.NONE, null, none,
            inStep(SetupStepId.PROJECT) { "resourcemanager.projects.create" in it || "HTTP Error: 403" in it }),
        Rule(SetupErrorId.DATASTORE_MODE, RetryPolicy.NONE, null, project("firestore"),
            text(Regex("Datastore Mode", I))),
        Rule(SetupErrorId.LOCATION_INVALID, RetryPolicy.NONE, null, none,
            inStep(SetupStepId.DATABASE) {
                it.contains("location", ignoreCase = true) &&
                    listOf("invalid", "not supported", "already").any { w -> it.contains(w, ignoreCase = true) }
            }),
        Rule(SetupErrorId.LOGIN_EXPIRED, RetryPolicy.NONE, Repair.RELOGIN, none,
            text(
                Regex("credentials are no longer valid"),
                Regex("invalid_grant"),
                Regex("Failed to authenticate", I),
                Regex("not logged in", I),
                Regex("firebase login", I),
            )),
        Rule(SetupErrorId.LOGIN_CODE_INVALID, RetryPolicy.NONE, Repair.RELOGIN, none,
            inStep(SetupStepId.LOGIN) { "Unable to authenticate using the provided code" in it }),
        Rule(SetupErrorId.SECRET_REFUSED, RetryPolicy.AFTER_REPAIR, Repair.ENABLE_APIS, none) { out, _, _ ->
            "secretmanager" in out && ("403" in out || "PERMISSION_DENIED" in out)
        },
        Rule(SetupErrorId.NPM_INSTALL_FAILED, RetryPolicy.WAIT_AND_RETRY, null, none,
            text(Regex("npm ERR!"), Regex("npm error"))),
        Rule(SetupErrorId.TRANSIENT, RetryPolicy.WAIT_AND_RETRY, null, none,
            text(Regex("Failed to list functions"), Regex("HTTP Error: 5\\d\\d"), Regex("unable to reach the following regions"))),
        Rule(SetupErrorId.QUOTA, RetryPolicy.WAIT_AND_RETRY, null, none,
            text(Regex("quota", I), Regex("HTTP Error: 429"))),
        Rule(SetupErrorId.PERMISSION_DENIED, RetryPolicy.NONE, null, iamPage,
            text(Regex("HTTP Error: 403"), Regex("permission denied", I), Regex("PERMISSION_DENIED"))),
        Rule(SetupErrorId.NETWORK, RetryPolicy.WAIT_AND_RETRY, null, none) { out, _, api ->
            api?.reason == "NETWORK" ||
                Regex("ENOTFOUND|ECONNRESET|ETIMEDOUT|EAI_AGAIN|socket hang up", I).containsMatchIn(out)
        },
    )

    fun classify(output: String, step: SetupStepId, projectId: String = "", api: GoogleApiStatus? = null): SetupError {
        val rule = rules.firstOrNull { it.matches(output, step, api) }
        val headline = headline(output)
        return if (rule == null) {
            SetupError(SetupErrorId.UNKNOWN, step, RetryPolicy.NONE, null, null, headline, output)
        } else {
            SetupError(rule.id, step, rule.retry, rule.repair, rule.link(projectId), headline, output)
        }
    }

    private val errorLine = Regex("""^\s*Error: (.*)$""")
    private val messageValue = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /** The real Google or CLI error line: the last `Error: ` line, else the last JSON message, else the last line. */
    fun headline(output: String): String {
        val lines = output.lines()
        val found = lines.lastOrNull { errorLine.containsMatchIn(it) }?.let { errorLine.find(it)!!.groupValues[1] }
            ?: messageValue.findAll(output).lastOrNull()?.groupValues?.get(1)
            ?: lines.lastOrNull { it.isNotBlank() }.orEmpty()
        return found.trim().take(300)
    }
}
