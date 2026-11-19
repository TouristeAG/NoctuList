package com.eventmanager.app.setup

import com.eventmanager.app.email.SiteMailOutcome
import com.eventmanager.app.email.SiteMailResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeSettings : SetupSettings {
    override var projectId = ""
    var apiKey = ""
    var appId = ""
    var clientId = ""
    var clientSecret = ""
    var progressJson = ""
    var photosEnabled = false
    override var mailTestOk = false
    override fun setWebConfig(apiKey: String, appId: String) { this.apiKey = apiKey; this.appId = appId }
    override fun setWebClient(id: String, secret: String) { clientId = id; clientSecret = secret }
    override fun hasWebClient() = clientId.isNotBlank() && clientSecret.isNotBlank()
    override fun loadProgress() = progressJson
    override fun saveProgress(json: String) { progressJson = json }
    override fun enableProfilePhotos() { photosEnabled = true }
}

private class FakeCli : SetupCli {
    val calls = mutableListOf<String>()
    var deployFailures = mutableListOf<String>()
    var databases = listOf(DatabaseInfo("(default)", "europe-west6"))
    var apps = emptyList<String>()
    var blockDeploy: CompletableDeferred<Unit>? = null
    var events: MutableList<String>? = null
    var cleanupMs = 0L
    var passwordSeen: String? = null
    var order: MutableList<String>? = null

    private fun fail(text: String, step: SetupStepId): Nothing = throw CliException(ErrorCatalog.classify(text, step))

    var accounts = listOf("admin@example.org")
    override suspend fun loginStart(): LoginChallenge { calls += "loginStart"; return LoginChallenge("AB12C", "https://auth.firebase.tools/login?x") }
    override suspend fun loginFinish(code: String) { calls += "loginFinish $code"; accounts = listOf("admin@example.org") }
    override suspend fun loginList() = accounts
    var logoutFails = false
    override suspend fun logout() {
        calls += "logout"
        if (logoutFails) throw CliException(SetupError(SetupErrorId.UNKNOWN, SetupStepId.LOGIN, RetryPolicy.NONE, null, null, "boom", "boom"))
        accounts = emptyList()
    }
    var projects = listOf(ProjectInfo("demo-project", "Demo"), ProjectInfo("other-project", "Other"))
    override suspend fun projectsList() = projects
    override suspend fun projectsCreate(id: String, displayName: String, onLine: (String) -> Unit) { calls += "create $id" }
    var databasesError: Exception? = null
    override suspend fun databases(projectId: String): List<DatabaseInfo> {
        calls += "databases"
        databasesError?.let { throw it }
        return databases
    }
    override suspend fun firestoreLocations(projectId: String) = listOf("nam5", "eur3", "europe-west6")
    override suspend fun createDatabase(projectId: String, location: String, allowed: List<String>, onLine: (String) -> Unit) { calls += "createDatabase $location" }
    override suspend fun webApps(projectId: String) = apps
    override suspend fun createWebApp(projectId: String, onLine: (String) -> Unit): String { calls += "createWebApp"; return "1:1:web:ab" }
    override suspend fun sdkConfig(projectId: String, appId: String) = WebConfig("AIzaKey", appId, projectId).also { calls += "sdkConfig" }
    override suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit) {
        calls += "deploy $only"
        order?.add("deploy $only")
        try {
            blockDeploy?.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            events?.add("deploy cancelled")
            if (cleanupMs > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(cleanupMs) }
            events?.add("deploy cleaned up")
            throw e
        }
        if (deployFailures.isNotEmpty()) fail(deployFailures.removeAt(0), SetupStepId.SERVER)
        if ("hosting" in only) onLine("\u001B[1mHosting URL:\u001B[22m https://site-for-$projectId.web.app")
    }
    override suspend fun createHostingSite(projectId: String, onLine: (String) -> Unit) { calls += "createHostingSite" }
    override suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
        calls += "setSecret $name"; passwordSeen = String(value)
    }
    override suspend fun npmInstall(dir: File, onLine: (String) -> Unit) { calls += "npmInstall ${dir.name}" }
    override suspend fun functionsList(projectId: String) = emptyList<FunctionInfo>()
    override suspend fun setArtifactPolicy(projectId: String, region: String, onLine: (String) -> Unit) { calls += "setpolicy $region" }
    override suspend fun deleteFunctions(projectId: String, names: List<String>, region: String, onLine: (String) -> Unit) {
        val call = "deleteFunctions ${names.joinToString(",")} $region"
        calls += call
        order?.add(call)
    }
}

/** Answers like a healthy Blaze project unless a test says otherwise. */
private class FakeOps : GoogleOps {
    override var step: SetupStepId = SetupStepId.TOOLS
    val calls = mutableListOf<String>()
    val steps = mutableListOf<SetupStepId>()
    var order: MutableList<String>? = null
    var enabledApis: List<String>? = null
    var gate: CompletableDeferred<Unit>? = null
    var billing = BillingStatus(true, emptyList())
    var billingError: Exception? = null
    var functions = emptyList<FunctionState>()
    var bucket: String? = "demo-project.appspot.com"
    var roleChanged = false
    var missing = emptyList<String>()

    private fun rec(call: String) {
        calls += call
        steps += step
        order?.add(call)
    }

    var authError: Exception? = null
    var rules: Pair<Boolean, Boolean> = true to true
    var hosting = true
    var rulesError: Exception? = null
    var hostingError: Exception? = null
    var functionsError: Exception? = null
    override suspend fun checkAuth(): String {
        rec("checkAuth")
        authError?.let { throw it }
        return "admin@example.org"
    }
    var number = "123"
    override suspend fun projectNumber(projectId: String) = number.also { rec("projectNumber") }
    override suspend fun missingPermissions(projectId: String, permissions: List<String>) =
        missing.also { rec("missingPermissions ${permissions.joinToString(",")}") }
    override suspend fun enableApis(projectId: String, apis: List<String>) {
        rec("enableApis")
        enabledApis = apis
        gate?.await()
    }
    override suspend fun ensureRole(projectId: String, member: String, role: String) = roleChanged.also { rec("ensureRole $member $role") }
    override suspend fun billing(projectId: String): BillingStatus {
        rec("billing")
        billingError?.let { throw it }
        return billing
    }
    override suspend fun linkBilling(projectId: String, account: String) {
        rec("linkBilling $account")
        billing = billing.copy(enabled = true)
    }
    override suspend fun functions(projectId: String, codebase: String): List<FunctionState> {
        rec("functions $codebase")
        functionsError?.let { throw it }
        return functions
    }
    override suspend fun storageBucket(projectId: String) = bucket.also { rec("storageBucket") }
    override suspend fun rulesReleased(projectId: String): Pair<Boolean, Boolean> {
        rec("rulesReleased")
        rulesError?.let { throw it }
        return rules
    }
    override suspend fun hostingLive(projectId: String): Boolean {
        rec("hostingLive")
        hostingError?.let { throw it }
        return hosting
    }
    override suspend fun ensureMailServiceAccount(projectId: String): MailServiceAccount {
        rec("ensureMailServiceAccount")
        return MailServiceAccount("noctulist-mail@$projectId.iam.gserviceaccount.com", "1234567890")
    }
}

/** Answers like a site whose server sent the test mail unless a test says otherwise. */
private class FakeMailTester : MailTester {
    var outcome = SiteMailOutcome(SiteMailResult.Sent, "")
    val sent = mutableListOf<String>()
    override suspend fun sendTest(to: String): SiteMailOutcome {
        sent += to
        return outcome
    }
}

private class FakeTools(var status: ToolsStatus = ToolsStatus.Missing, val calls: MutableList<String> = mutableListOf()) : SetupTools {
    var gate: CompletableDeferred<Unit>? = null
    var installWorks = true
    var statusCalls = 0
    var failWith: Exception? = null
    constructor(installed: Boolean) : this(if (installed) ToolsStatus.Ready(FAKE_PATHS) else ToolsStatus.Missing)

    override suspend fun status(): ToolsStatus { statusCalls++; return status }
    override suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths {
        gate?.await()
        failWith?.let { throw it }
        calls += "install"
        if (installWorks) status = ToolsStatus.Ready(FAKE_PATHS)
        return FAKE_PATHS
    }
    override suspend fun reinstall(onProgress: (Float, String) -> Unit): ToolPaths {
        gate?.await()
        failWith?.let { throw it }
        calls += "remove"
        return install(onProgress)
    }

    companion object {
        val FAKE_PATHS = ToolPaths(File("/n"), File("/npm"), File("/fb"), File("/bin"))
    }
}

class SetupRunnerTest {
    private val base: File = Files.createTempDirectory("runner ws").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = FakeSettings().apply { projectId = "demo-project" }
    private val cli = FakeCli()
    private val ops = FakeOps()
    private val workspace = SiteWorkspace(
        root = base,
        siteFiles = listOf("index.html", "firebase.json"),
        readResource = { path ->
            when (path) {
                "files/webform/firebase.json" -> """{"hosting":{"public":"."},"functions":[{"source":"functions"}]}"""
                else -> "x"
            }.encodeToByteArray()
        },
    )
    private val sleeps = mutableListOf<Long>()
    private val mailer = FakeMailTester()
    private val runner = SetupRunner(scope, FakeTools(), { cli }, { ops }, workspace, settings, sleep = { sleeps += it }, mailTester = mailer)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        base.deleteRecursively()
    }

    private suspend fun awaitState(step: SetupStepId, predicate: (StepState) -> Boolean): StepState =
        withTimeout(5_000) { runner.states.first { it[step]?.let(predicate) == true } }.getValue(step)

    private suspend fun ready() {
        runner.run(SetupStepId.TOOLS)
        awaitState(SetupStepId.TOOLS) { it == StepState.Done }
    }

    /** The server step ends with the functions deployed and the step waiting for its test mail. */
    private suspend fun awaitServerDeployed(r: SetupRunner = runner) {
        withTimeout(5_000) { r.info.first { it.serverDeployed } }
        assertEquals(StepState.Todo, r.states.value[SetupStepId.SERVER])
    }

    @Test
    fun serverRunsInTheSafeOrder(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "abcd efgh ijkl mnop".toCharArray()))
        awaitServerDeployed()
        assertEquals(
            listOf("databases", "setSecret GMAIL_APP_PASSWORD", "npmInstall functions", "deploy hosting,functions", "setpolicy europe-west6"),
            cli.calls,
        )
        assertEquals(
            "GMAIL_USER=info@example.org\nREGION=europe-west6\nMAIL_MODE=app_password\nMAIL_FROM=\nMAIL_SA=\n",
            base.resolve("demo-project/functions/.env.demo-project").readText(),
        )
        assertEquals("abcd efgh ijkl mnop", cli.passwordSeen)
    }

    @Test
    fun passwordIsMaskedOnlyWhileTheServerStepRuns(): Unit = runBlocking {
        val secrets = ActiveSecrets()
        var seenDuringStep = emptyList<String>()
        val probingCli = object : SetupCli by cli {
            override suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
                seenDuringStep = secrets.current()
            }
        }
        val r = SetupRunner(scope, FakeTools(), { probingCli }, { ops }, workspace, settings, sleep = {}, secrets = secrets)
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        r.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "abcd efgh".toCharArray()))
        awaitServerDeployed(r)
        assertEquals(listOf("abcd efgh"), seenDuringStep)
        assertEquals(emptyList(), secrets.current())
    }

    @Test
    fun errorStateAndReportKeepNoSecretAfterAFailedStep(): Unit = runBlocking {
        val failing = object : SetupCli by cli {
            override suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
                throw IllegalStateException("refused for abcd efgh")
            }
        }
        val r = SetupRunner(scope, FakeTools(), { failing }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        r.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "abcd efgh".toCharArray()))
        val state = withTimeout(5_000) { r.states.first { it[SetupStepId.SERVER] is StepState.Error } }
        val error = (state.getValue(SetupStepId.SERVER) as StepState.Error).error
        assertFalse("abcd" in error.details || "abcd" in error.headline)
        val report = r.report(SetupStepId.SERVER)
        assertTrue("Step 9" in report && "refused for" in report)
        assertFalse("abcd" in report)
    }

    @Test
    fun cliErrorDetailsAreRedactedWhenTheStepFails(): Unit = runBlocking {
        val failing = object : SetupCli by cli {
            override suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
                throw CliException(ErrorCatalog.classify("Error: quota hit for abcd efgh", SetupStepId.SERVER))
            }
        }
        val r = SetupRunner(scope, FakeTools(), { failing }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        r.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "abcd efgh".toCharArray()))
        val state = withTimeout(5_000) { r.states.first { it[SetupStepId.SERVER] is StepState.Error } }
        val error = (state.getValue(SetupStepId.SERVER) as StepState.Error).error
        assertFalse("abcd" in error.details || "abcd" in error.headline)
    }

    @Test
    fun webClientSecretIsMaskedWhileConnectRuns(): Unit = runBlocking {
        val secrets = ActiveSecrets()
        var seen = emptyList<String>()
        val probing = object : SetupCli by cli {
            override suspend fun sdkConfig(projectId: String, appId: String): WebConfig {
                seen = secrets.current()
                return WebConfig("k", appId, projectId)
            }
        }
        val r = SetupRunner(scope, FakeTools(), { probing }, { ops }, workspace, settings, sleep = {}, secrets = secrets)
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        r.run(SetupStepId.CONNECT, StepInput(webClientId = "id", webClientSecret = "GOCSPX-secret"))
        withTimeout(5_000) { r.states.first { it[SetupStepId.CONNECT] == StepState.Done } }
        assertEquals(listOf("GOCSPX-secret"), seen)
        assertEquals(emptyList(), secrets.current())
    }

    @Test
    fun passwordArrayIsWipedAfterTheStep(): Unit = runBlocking {
        ready()
        val password = "abcd efgh".toCharArray()
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = password))
        awaitServerDeployed()
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun eventarcIsRetriedThenSucceeds(): Unit = runBlocking {
        ready()
        cli.deployFailures += listOf("Permission denied while using the Eventarc Service Agent.", "Retry the deployment in a few minutes. Eventarc Service Agent")
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "x".toCharArray()))
        awaitServerDeployed()
        assertEquals(3, cli.calls.count { it == "deploy hosting,functions" })
        assertEquals(2 * 120, sleeps.size)
    }

    @Test
    fun givesUpAfterFiveRetries(): Unit = runBlocking {
        ready()
        repeat(6) { cli.deployFailures += "Permission denied while using the Eventarc Service Agent." }
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "x".toCharArray()))
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.EVENTARC_NOT_READY, (state as StepState.Error).error.id)
        assertEquals(6, cli.calls.count { it == "deploy hosting,functions" })
    }

    @Test
    fun billingStopsAtOnce(): Unit = runBlocking {
        ready()
        cli.deployFailures += "Your project must be on the Blaze (pay-as-you-go) plan."
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "x".toCharArray()))
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.BILLING_REQUIRED, (state as StepState.Error).error.id)
        assertEquals(1, cli.calls.count { it.startsWith("deploy") })
    }

    @Test
    fun cancelDuringDeployGoesBackToTodo(): Unit = runBlocking {
        ready()
        cli.blockDeploy = CompletableDeferred()
        runner.run(SetupStepId.SITE)
        awaitState(SetupStepId.SITE) { it is StepState.Running }
        withTimeout(5_000) { while (cli.calls.none { it.startsWith("deploy") }) kotlinx.coroutines.delay(10) }
        runner.cancel()
        awaitState(SetupStepId.SITE) { it == StepState.Todo }
    }

    @Test
    fun cancelStopsTheRunningStepAndDropsQueuedOnes(): Unit = runBlocking {
        ready()
        cli.blockDeploy = CompletableDeferred()
        runner.run(SetupStepId.SITE)
        withTimeout(5_000) { while (cli.calls.none { it == "deploy hosting" }) kotlinx.coroutines.delay(10) }
        runner.run(SetupStepId.RULES)
        runner.cancel()
        awaitState(SetupStepId.SITE) { it == StepState.Todo }
        kotlinx.coroutines.delay(200)
        assertTrue(cli.calls.none { it == "deploy firestore:rules" })
        assertEquals(StepState.Todo, runner.states.value[SetupStepId.RULES])
    }

    @Test
    fun loginShowsAChallengeThenFinishesWithTheCode(): Unit = runBlocking {
        ready()
        cli.accounts = emptyList()
        runner.run(SetupStepId.LOGIN)
        withTimeout(5_000) { runner.info.first { it.loginChallenge != null } }
        awaitState(SetupStepId.LOGIN) { it == StepState.Todo }
        assertEquals("AB12C", runner.info.value.loginChallenge?.sessionId)
        runner.run(SetupStepId.LOGIN, StepInput(authCode = "4/0AVMBsJ_code12345"))
        awaitState(SetupStepId.LOGIN) { it == StepState.Done }
        assertTrue("loginFinish 4/0AVMBsJ_code12345" in cli.calls)
        assertEquals(null, runner.info.value.loginChallenge)
        assertEquals("admin@example.org", runner.info.value.account)
    }

    @Test
    fun loginIsDoneAtOnceWhenAlreadySignedIn(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.LOGIN)
        awaitState(SetupStepId.LOGIN) { it == StepState.Done }
        assertTrue(cli.calls.none { it.startsWith("login") })
    }

    @Test
    fun switchingProjectClearsTheOtherProjectsTicks(): Unit = runBlocking {
        settings.progressJson = SetupProgress(
            projectId = "demo-project",
            done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN, SetupStepId.PROJECT, SetupStepId.DATABASE, SetupStepId.RULES, SetupStepId.SITE),
            confirmed = setOf(SetupStepId.PROVIDERS),
        ).encode()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.PROJECT, StepInput(projectId = "other-project"))
        withTimeout(5_000) { r.states.first { it[SetupStepId.PROJECT] == StepState.Done } }
        val p = r.progress.value
        assertEquals("other-project", p.projectId)
        assertEquals(setOf(SetupStepId.TOOLS, SetupStepId.LOGIN, SetupStepId.PROJECT), p.done)
        assertTrue(p.confirmed.isEmpty())
        assertEquals(StepState.Todo, r.states.value[SetupStepId.DATABASE])
        assertEquals(StepState.Todo, r.states.value[SetupStepId.SITE])
    }

    @Test
    fun refreshRemovesTicksThatAreNoLongerTrue(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        cli.accounts = emptyList()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(StepState.Done, r.states.value[SetupStepId.TOOLS])
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
    }

    @Test
    fun refreshUnticksToolsWhenBroken(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        val r = SetupRunner(scope, FakeTools(ToolsStatus.Broken("node does not start")), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.TOOLS])
        assertTrue(SetupStepId.TOOLS !in r.progress.value.done)
        assertEquals(ToolsState.BROKEN, r.info.value.tools)
    }

    @Test
    fun refreshReportsReadyAndOutdatedTools(): Unit = runBlocking {
        val ready = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        ready.refresh()
        assertEquals(ToolsState.READY, ready.info.value.tools)
        val old = SetupRunner(scope, FakeTools(ToolsStatus.Outdated("v22.1.0", "15.0.0")), { cli }, { ops }, workspace, settings, sleep = {})
        old.refresh()
        assertEquals(ToolsState.OUTDATED, old.info.value.tools)
        assertEquals(StepState.Todo, old.states.value[SetupStepId.TOOLS])
    }

    @Test
    fun reinstallRemovesThenInstalls(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Broken("x"))
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.reinstallTools()
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        assertEquals(listOf("remove", "install"), tools.calls)
        assertEquals(ToolsState.READY, r.info.value.tools)
    }

    @Test
    fun reinstallCancelsRunningStepFirst(): Unit = runBlocking {
        val events = mutableListOf<String>()
        cli.events = events
        cli.blockDeploy = CompletableDeferred()
        val tools = FakeTools(ToolsStatus.Ready(FakeTools.FAKE_PATHS), events)
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.RULES)
        withTimeout(5_000) { while ("deploy firestore:rules" !in cli.calls) kotlinx.coroutines.delay(10) }
        r.reinstallTools()
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        assertEquals(StepState.Todo, r.states.value[SetupStepId.RULES])
        assertEquals(listOf("deploy cancelled", "deploy cleaned up", "remove", "install"), events)
    }

    @Test
    fun refreshDuringInstallKeepsToolsState(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Broken("x")).apply { gate = CompletableDeferred() }
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] is StepState.Running } }
        r.refresh()
        assertEquals(ToolsState.MISSING, r.info.value.tools)
        assertTrue(r.states.value[SetupStepId.TOOLS] is StepState.Running)
        tools.gate!!.complete(Unit)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
    }

    @Test
    fun refreshDuringReinstallDoesNothing(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Ready(FakeTools.FAKE_PATHS)).apply { gate = CompletableDeferred() }
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.reinstallTools()
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] is StepState.Running } }
        val before = tools.statusCalls
        r.refresh()
        assertEquals(before, tools.statusCalls)
        assertTrue(cli.calls.isEmpty())
        assertTrue(r.states.value[SetupStepId.TOOLS] is StepState.Running)
        tools.gate!!.complete(Unit)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
    }

    @Test
    fun reinstallWaitsForCancelledMiddleJob(): Unit = runBlocking {
        val events = mutableListOf<String>()
        cli.events = events
        cli.cleanupMs = 300
        cli.blockDeploy = CompletableDeferred()
        val tools = FakeTools(ToolsStatus.Ready(FakeTools.FAKE_PATHS), events)
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.RULES)
        withTimeout(5_000) { while ("deploy firestore:rules" !in cli.calls) kotlinx.coroutines.delay(10) }
        r.run(SetupStepId.SITE) // queued behind the running deploy
        r.reinstallTools()
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        assertEquals(listOf("deploy cancelled", "deploy cleaned up", "remove", "install"), events)
    }

    @Test
    fun failedReinstallReadsTheToolsAgain(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Broken("x")).apply { failWith = java.io.IOException("disk full") }
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.reinstallTools()
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] is StepState.Error } }
        assertEquals(ToolsState.BROKEN, r.info.value.tools)
    }

    @Test
    fun installThatDoesNotAnswerIsBroken(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Missing).apply { installWorks = false }
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] is StepState.Error } }
        assertEquals(ToolsState.MISSING, r.info.value.tools)
        tools.status = ToolsStatus.Broken("x")
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] is StepState.Error } }
        assertEquals(ToolsState.BROKEN, r.info.value.tools)
    }

    @Test
    fun runningToolsOnBrokenToolsReinstalls(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Broken("x"))
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        assertEquals(listOf("remove", "install"), tools.calls)
    }

    @Test
    fun stepsWorkWithoutRunningToolsAgainWhenToolsExist(): Unit = runBlocking {
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.LOGIN)
        withTimeout(5_000) { r.states.first { it[SetupStepId.LOGIN] == StepState.Done } }
    }

    @Test
    fun refreshListsLocationsWhenTheDatabaseIsMissing(): Unit = runBlocking {
        cli.databases = emptyList()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(listOf("europe-west6", "eur3", "nam5"), r.info.value.locations)
    }

    @Test
    fun missingHostingSiteIsCreatedThenDeployedAndUrlRead(): Unit = runBlocking {
        ready()
        cli.deployFailures += "Error: Unable to deploy to Hosting as there is no Hosting site. Use firebase hosting:sites:create to create a site."
        runner.run(SetupStepId.SITE)
        awaitState(SetupStepId.SITE) { it == StepState.Done }
        assertEquals(listOf("deploy hosting", "createHostingSite", "deploy hosting"), cli.calls)
        assertEquals("https://site-for-demo-project.web.app", runner.info.value.siteUrl)
    }

    @Test
    fun aDoneStepCanRunAgain(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.RULES)
        awaitState(SetupStepId.RULES) { it == StepState.Done }
        runner.run(SetupStepId.RULES)
        withTimeout(5_000) { while (cli.calls.count { it == "deploy firestore:rules" } < 2) kotlinx.coroutines.delay(10) }
        awaitState(SetupStepId.RULES) { it == StepState.Done }
    }

    @Test
    fun connectCreatesAWebAppAndFillsSettings(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.CONNECT, StepInput(webClientId = "123.apps.googleusercontent.com", webClientSecret = "GOCSPX-s"))
        awaitState(SetupStepId.CONNECT) { it == StepState.Done }
        assertEquals(listOf("createWebApp", "sdkConfig"), cli.calls)
        assertEquals("AIzaKey", settings.apiKey)
        assertEquals("1:1:web:ab", settings.appId)
        assertEquals("123.apps.googleusercontent.com", settings.clientId)
    }

    @Test
    fun connectWithoutClientFieldsStaysOpen(): Unit = runBlocking {
        ready()
        cli.apps = listOf("1:1:web:cd")
        runner.run(SetupStepId.CONNECT)
        awaitState(SetupStepId.CONNECT) { it == StepState.Todo }
        assertEquals("1:1:web:cd", settings.appId)
    }

    @Test
    fun databaseIsNotCreatedTwice(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.DATABASE, StepInput(location = "eur3"))
        awaitState(SetupStepId.DATABASE) { it == StepState.Done }
        assertTrue(cli.calls.none { it.startsWith("createDatabase") })
        cli.databases = emptyList()
        runner.run(SetupStepId.DATABASE, StepInput(location = "eur3"))
        withTimeout(5_000) { while (cli.calls.none { it.startsWith("createDatabase") }) kotlinx.coroutines.delay(10) }
        assertTrue("createDatabase eur3" in cli.calls)
    }

    @Test
    fun progressIsSavedAndRestored(): Unit = runBlocking {
        ready()
        runner.confirm(SetupStepId.PROVIDERS)
        val restored = SetupProgress.decode(settings.progressJson)
        assertTrue(SetupStepId.TOOLS in restored.done)
        assertTrue(SetupStepId.PROVIDERS in restored.confirmed)
        val again = SetupRunner(scope, FakeTools(), { cli }, { ops }, workspace, settings, sleep = {})
        assertEquals(StepState.Done, again.states.value[SetupStepId.PROVIDERS])
    }

    @Test
    fun photosDeploysStorageAndTurnsTheSettingOn(): Unit = runBlocking {
        ready()
        runner.confirm(SetupStepId.PHOTOS)
        runner.run(SetupStepId.PHOTOS)
        awaitState(SetupStepId.PHOTOS) { it == StepState.Done }
        assertTrue("deploy storage" in cli.calls)
        assertTrue(settings.photosEnabled)
        assertTrue(base.resolve("demo-project/storage.rules").isFile)
    }

    @Test
    fun signOutClearsAccountAndUnticksLogin(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN, SetupStepId.PROJECT)).encode()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals("admin@example.org", r.info.value.account)
        r.signOut()
        assertTrue("logout" in cli.calls)
        assertEquals("", r.info.value.account)
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
        assertTrue(SetupStepId.PROJECT in r.progress.value.done)
        assertTrue(r.info.value.projects.isEmpty())
    }

    @Test
    fun signOutStillClearsLocalStateWhenLogoutFails(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        cli.logoutFails = true
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        r.signOut()
        assertEquals("", r.info.value.account)
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
        assertTrue(r.states.value[SetupStepId.LOGIN] is StepState.Error)
    }

    @Test
    fun signOutCancelsRunningSteps(): Unit = runBlocking {
        cli.blockDeploy = CompletableDeferred()
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        r.run(SetupStepId.RULES)
        awaitStateOf(r, SetupStepId.RULES) { it is StepState.Running }
        r.signOut()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.RULES])
        assertTrue("logout" in cli.calls)
    }

    private suspend fun awaitStateOf(r: SetupRunner, step: SetupStepId, predicate: (StepState) -> Boolean) =
        withTimeout(5_000) { r.states.first { it[step]?.let(predicate) == true } }

    @Test
    fun switchAccountStartsNewSignIn(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        r.switchAccount()
        awaitStateOf(r, SetupStepId.LOGIN) { it == StepState.Todo }
        withTimeout(5_000) { r.info.first { it.loginChallenge != null } }
        assertEquals("AB12C", r.info.value.loginChallenge?.sessionId)
    }

    @Test
    fun oldSavedProgressWithoutSignInShowsSignedOut(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN, SetupStepId.PROJECT)).encode()
        cli.accounts = emptyList()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertEquals("", r.info.value.account)
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
    }

    @Test
    fun switchAccountAfterFailedLogoutKeepsError(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        cli.logoutFails = true
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        r.switchAccount()
        kotlinx.coroutines.delay(200)
        assertTrue(r.states.value[SetupStepId.LOGIN] is StepState.Error)
        assertEquals("", r.info.value.account)
        assertTrue("loginStart" !in cli.calls)
    }

    @Test
    fun refreshCannotRetickLoginAfterSignOut(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
        r.refresh()
        kotlinx.coroutines.coroutineScope {
            launch { r.refresh() }
            launch { r.signOut() }
        }
        assertEquals("", r.info.value.account)
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
    }

    private fun activeFunctions(state: String = "ACTIVE") =
        listOf("onSignupWritten", "onPortalRequestWritten", "onMailOutboxWritten").map { FunctionState(it, "europe-west6", state, emptyList()) }

    private fun refreshRunner(vararg done: SetupStepId): SetupRunner {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = done.toSet()).encode()
        return SetupRunner(scope, FakeTools(installed = true), { cli }, { ops }, workspace, settings, sleep = {})
    }

    @Test
    fun refreshUnticksServerWhenAFunctionIsFailed(): Unit = runBlocking {
        ops.functions = activeFunctions().let { it.dropLast(1) + it.last().copy(state = "FAILED") }
        val r = refreshRunner(SetupStepId.SERVER)
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.SERVER])
        assertTrue(SetupStepId.SERVER !in r.progress.value.done)
    }

    @Test
    fun refreshTicksServerWhenAllFunctionsAreActive(): Unit = runBlocking {
        ops.functions = activeFunctions()
        settings.mailTestOk = true
        val r = refreshRunner()
        r.refresh()
        assertEquals(StepState.Done, r.states.value[SetupStepId.SERVER])
        assertTrue(r.info.value.mailTestOk)
    }

    @Test
    fun refreshNeedsTheTestMailToTickServer(): Unit = runBlocking {
        ops.functions = activeFunctions()
        val r = refreshRunner(SetupStepId.SERVER)
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.SERVER])
        assertTrue(SetupStepId.SERVER !in r.progress.value.done)
        assertTrue(r.info.value.serverDeployed)
    }

    @Test
    fun refreshUnticksSiteWhenNoLiveVersion(): Unit = runBlocking {
        ops.hosting = false
        val r = refreshRunner(SetupStepId.SITE)
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.SITE])
        assertTrue(SetupStepId.SITE !in r.progress.value.done)
    }

    @Test
    fun refreshTicksRulesWhenReleased(): Unit = runBlocking {
        val r = refreshRunner()
        r.refresh()
        assertEquals(StepState.Done, r.states.value[SetupStepId.RULES])
        assertEquals(StepState.Done, r.states.value[SetupStepId.SITE])
        assertEquals(StepState.Done, r.states.value[SetupStepId.PHOTOS])
    }

    @Test
    fun refreshUnticksPhotosWithoutBucketOrStorageRules(): Unit = runBlocking {
        ops.bucket = null
        val r = refreshRunner(SetupStepId.PHOTOS)
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.PHOTOS])
        ops.bucket = "demo-project.appspot.com"
        ops.rules = true to false
        refreshRunner(SetupStepId.PHOTOS).also { it.refresh(); assertEquals(StepState.Todo, it.states.value[SetupStepId.PHOTOS]) }
    }

    @Test
    fun refreshKeepsTickWhenCheckErrors(): Unit = runBlocking {
        val failure = GoogleOpsException(GoogleApiError(503, "UNAVAILABLE", "try later", ""))
        ops.rulesError = failure
        ops.hostingError = GoogleOpsException(GoogleApiError(0, "NETWORK", "fetch failed", ""))
        ops.functionsError = failure
        val r = refreshRunner(SetupStepId.RULES, SetupStepId.SITE, SetupStepId.SERVER, SetupStepId.PHOTOS)
        r.refresh()
        for (s in listOf(SetupStepId.RULES, SetupStepId.SITE, SetupStepId.SERVER, SetupStepId.PHOTOS)) {
            assertEquals(StepState.Done, r.states.value[s], s.name)
            assertTrue(s in r.progress.value.done)
        }
        assertEquals(StepState.Done, r.states.value[SetupStepId.LOGIN])
    }

    @Test
    fun refreshShowsExpiredSignIn(): Unit = runBlocking {
        ops.authError = GoogleOpsException(GoogleApiError(401, "NOT_LOGGED_IN", "Not signed in", ""))
        val r = refreshRunner(SetupStepId.LOGIN, SetupStepId.SITE)
        r.refresh()
        val login = r.states.value[SetupStepId.LOGIN]
        assertTrue(login is StepState.Error && login.error.id == SetupErrorId.LOGIN_EXPIRED)
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
        assertEquals(listOf("checkAuth"), ops.calls)
        assertEquals(StepState.Done, r.states.value[SetupStepId.SITE])
    }

    @Test
    fun refreshKeepsSignInOnNetworkErrorFromCheckAuth(): Unit = runBlocking {
        ops.authError = GoogleOpsException(GoogleApiError(0, "NETWORK", "fetch failed", ""))
        val r = refreshRunner(SetupStepId.LOGIN, SetupStepId.SITE)
        r.refresh()
        assertEquals(StepState.Done, r.states.value[SetupStepId.LOGIN])
        assertEquals(StepState.Done, r.states.value[SetupStepId.SITE])
    }

    @Test
    fun refreshDoesNotCallGoogleWithoutAProject(): Unit = runBlocking {
        settings.projectId = ""
        val r = refreshRunner(SetupStepId.SITE)
        settings.projectId = ""
        r.refresh()
        assertTrue(ops.calls.none { it != "checkAuth" })
    }

    @Test
    fun refreshSkipsProjectChecksWithoutDatabase(): Unit = runBlocking {
        cli.databases = emptyList()
        val r = refreshRunner(SetupStepId.RULES)
        r.refresh()
        assertTrue("rulesReleased" !in ops.calls)
    }

    @Test
    fun refreshResetsCheckingFlag(): Unit = runBlocking {
        val r = refreshRunner()
        assertEquals(false, r.info.value.checking)
        ops.authError = kotlinx.coroutines.CancellationException("cancelled")
        val failure = runCatching { r.refresh() }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(false, r.info.value.checking)
    }

    @Test
    fun refreshKeepsTicksWhenDatabaseListFails(): Unit = runBlocking {
        cli.databasesError = java.io.IOException("network down")
        val r = refreshRunner(SetupStepId.DATABASE, SetupStepId.RULES, SetupStepId.PHOTOS)
        r.refresh()
        for (s in listOf(SetupStepId.DATABASE, SetupStepId.RULES, SetupStepId.PHOTOS)) {
            assertEquals(StepState.Done, r.states.value[s], s.name)
            assertTrue(s in r.progress.value.done)
        }
        assertTrue("rulesReleased" !in ops.calls)
    }

    @Test
    fun refreshDoesNotTickARunningDatabaseStep(): Unit = runBlocking {
        ops.gate = CompletableDeferred()
        val r = refreshRunner(SetupStepId.TOOLS, SetupStepId.LOGIN, SetupStepId.PROJECT)
        r.refresh()
        r.run(SetupStepId.DATABASE, StepInput(location = "eur3"))
        awaitStateOf(r, SetupStepId.DATABASE) { it is StepState.Running }
        r.refresh()
        assertTrue(r.states.value[SetupStepId.DATABASE] is StepState.Running)
        ops.gate!!.complete(Unit)
    }

    @Test
    fun refreshDoesNotTickARunningStep(): Unit = runBlocking {
        cli.blockDeploy = CompletableDeferred()
        val r = refreshRunner(SetupStepId.TOOLS, SetupStepId.LOGIN)
        r.refresh()
        r.run(SetupStepId.RULES)
        awaitStateOf(r, SetupStepId.RULES) { it is StepState.Running }
        r.refresh()
        assertTrue(r.states.value[SetupStepId.RULES] is StepState.Running)
        cli.blockDeploy!!.complete(Unit)
    }

    @Test
    fun refreshUnticksRulesAndPhotosWithoutDatabase(): Unit = runBlocking {
        cli.databases = emptyList()
        val r = refreshRunner(SetupStepId.RULES, SetupStepId.PHOTOS)
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.RULES])
        assertEquals(StepState.Todo, r.states.value[SetupStepId.PHOTOS])
    }

    @Test
    fun refreshChecksAuthFirst(): Unit = runBlocking {
        val r = refreshRunner()
        r.refresh()
        assertEquals("checkAuth", ops.calls.first())
    }

    private fun serverInput() = StepInput(gmailUser = "info@example.org", appPassword = "x".toCharArray())

    @Test
    fun databaseEnablesApisFirst(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.DATABASE, StepInput(location = "eur3"))
        awaitState(SetupStepId.DATABASE) { it == StepState.Done }
        assertEquals("enableApis", ops.calls.first())
        assertEquals(REQUIRED_APIS, ops.enabledApis)
        assertEquals(16, REQUIRED_APIS.size)
        assertEquals(listOf(RepairNote(Repair.ENABLE_APIS, "16 API")), runner.info.value.repairs[SetupStepId.DATABASE])
        assertEquals(listOf(SetupStepId.DATABASE), ops.steps)
    }

    @Test
    fun serverGrantsBuildRoleBeforeDeploy(): Unit = runBlocking {
        val order = mutableListOf<String>()
        ops.order = order
        cli.order = order
        ops.roleChanged = true
        ready()
        runner.run(SetupStepId.SERVER, serverInput())
        awaitServerDeployed()
        val number = order.indexOf("projectNumber")
        val role = order.indexOf("ensureRole serviceAccount:123-compute@developer.gserviceaccount.com roles/cloudbuild.builds.builder")
        val deploy = order.indexOf("deploy hosting,functions")
        assertTrue(number >= 0 && number < role && role < deploy, order.toString())
        assertTrue(runner.info.value.repairs[SetupStepId.SERVER].orEmpty().any { it.repair == Repair.GRANT_BUILD_ROLE })
        assertTrue(ops.steps.all { it == SetupStepId.SERVER })
    }

    @Test
    fun serverDeletesFailedFunctionsBeforeDeploy(): Unit = runBlocking {
        ops.functions = listOf(
            FunctionState("onSignupWritten", "europe-west6", "FAILED", emptyList()),
            FunctionState("onMailOutboxWritten", "europe-west6", "ACTIVE", emptyList()),
        )
        ready()
        runner.run(SetupStepId.SERVER, serverInput())
        awaitServerDeployed()
        assertEquals(listOf("deleteFunctions onSignupWritten europe-west6"), cli.calls.filter { it.startsWith("deleteFunctions") })
        assertTrue(cli.calls.indexOf("deleteFunctions onSignupWritten europe-west6") < cli.calls.indexOf("deploy hosting,functions"))
        assertTrue("functions noctulist" in ops.calls)
        assertTrue(runner.info.value.repairs[SetupStepId.SERVER].orEmpty().any { it.repair == Repair.DELETE_FAILED_FUNCTIONS })
    }

    @Test
    fun httpsToBackgroundErrorRepairsThenRetriesOnce(): Unit = runBlocking {
        ops.functions = listOf(FunctionState("onMailOutboxWritten", "europe-west6", "ACTIVE", emptyList()))
        ready()
        cli.deployFailures += SetupErrorFixtures.HTTPS_TO_BACKGROUND
        runner.run(SetupStepId.SERVER, serverInput())
        awaitServerDeployed()
        assertTrue("deleteFunctions onMailOutboxWritten europe-west6" in cli.calls, cli.calls.toString())
        assertEquals(2, cli.calls.count { it == "deploy hosting,functions" })
    }

    @Test
    fun secondFailureAfterRepairIsShown(): Unit = runBlocking {
        ops.functions = listOf(FunctionState("onMailOutboxWritten", "europe-west6", "ACTIVE", emptyList()))
        ready()
        cli.deployFailures += listOf(SetupErrorFixtures.HTTPS_TO_BACKGROUND, SetupErrorFixtures.HTTPS_TO_BACKGROUND)
        runner.run(SetupStepId.SERVER, serverInput())
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.FUNCTIONS_FAILED_STATE, (state as StepState.Error).error.id)
        assertEquals(2, cli.calls.count { it == "deploy hosting,functions" })
    }

    @Test
    fun apiNotEnabledIsRepairedThenRetried(): Unit = runBlocking {
        ready()
        cli.deployFailures += "Error: Cloud Run API has not been used in project 1 before or it is disabled."
        runner.run(SetupStepId.RULES)
        awaitState(SetupStepId.RULES) { it == StepState.Done }
        assertEquals(REQUIRED_APIS, ops.enabledApis)
        assertEquals(2, cli.calls.count { it == "deploy firestore:rules" })
        assertTrue(runner.info.value.repairs[SetupStepId.RULES].orEmpty().any { it.repair == Repair.ENABLE_APIS })
    }

    @Test
    fun billingNeverLinkedWithoutConfirmation(): Unit = runBlocking {
        ops.billing = BillingStatus(false, listOf(BillingAccount("billingAccounts/1", "Main")))
        ready()
        runner.run(SetupStepId.SERVER, serverInput())
        val state = awaitState(SetupStepId.SERVER) { it is StepState.NeedsBilling }
        assertEquals(listOf(BillingAccountInfo("billingAccounts/1", "Main")), (state as StepState.NeedsBilling).accounts)
        assertTrue(ops.calls.none { it.startsWith("linkBilling") })
        assertTrue(cli.calls.none { it.startsWith("deploy") })
        runner.confirmBilling(SetupStepId.SERVER, "billingAccounts/1", serverInput())
        awaitServerDeployed()
        assertEquals(1, ops.calls.count { it == "linkBilling billingAccounts/1" })
        assertTrue("deploy hosting,functions" in cli.calls)
    }

    @Test
    fun confirmBillingIgnoresAccountsNotOffered(): Unit = runBlocking {
        ops.billing = BillingStatus(false, listOf(BillingAccount("billingAccounts/1", "Main")))
        ready()
        runner.confirm(SetupStepId.PHOTOS)
        runner.run(SetupStepId.PHOTOS)
        awaitState(SetupStepId.PHOTOS) { it is StepState.NeedsBilling }
        runner.confirmBilling(SetupStepId.PHOTOS, "billingAccounts/2")
        runner.confirmBilling(SetupStepId.SERVER, "billingAccounts/1")
        kotlinx.coroutines.delay(200)
        assertTrue(ops.calls.none { it.startsWith("linkBilling") })
        assertTrue(runner.states.value[SetupStepId.PHOTOS] is StepState.NeedsBilling)
    }

    @Test
    fun photosWithoutBucketIsStorageNotReady(): Unit = runBlocking {
        ops.bucket = null
        ready()
        runner.confirm(SetupStepId.PHOTOS)
        runner.run(SetupStepId.PHOTOS)
        val state = awaitState(SetupStepId.PHOTOS) { it is StepState.Error }
        assertEquals(SetupErrorId.STORAGE_NOT_READY, (state as StepState.Error).error.id)
        assertEquals("https://console.firebase.google.com/project/demo-project/storage", state.error.link)
        assertTrue(cli.calls.none { it.startsWith("deploy") })
    }

    @Test
    fun timeoutBecomesTimeoutError(): Unit = runBlocking {
        val slow = object : SetupCli by cli {
            override suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit) {
                throw CommandTimeoutException(true)
            }
        }
        val r = SetupRunner(scope, FakeTools(installed = true), { slow }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.RULES)
        val state = awaitStateOf(r, SetupStepId.RULES) { it is StepState.Error }.getValue(SetupStepId.RULES)
        assertEquals(SetupErrorId.TIMEOUT, (state as StepState.Error).error.id)
    }

    @Test
    fun googleSignInErrorIsLoginExpired(): Unit = runBlocking {
        ops.billingError = GoogleOpsException(GoogleApiError(401, "NOT_LOGGED_IN", "Not signed in", ""))
        ready()
        runner.run(SetupStepId.SERVER, serverInput())
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.LOGIN_EXPIRED, (state as StepState.Error).error.id)
        assertEquals(1, ops.calls.count { it == "billing" })
    }

    @Test
    fun cancelDuringRepairReturnsToTodo(): Unit = runBlocking {
        ops.gate = CompletableDeferred()
        ready()
        runner.run(SetupStepId.DATABASE, StepInput(location = "eur3"))
        withTimeout(5_000) { while ("enableApis" !in ops.calls) kotlinx.coroutines.delay(10) }
        runner.cancel()
        awaitState(SetupStepId.DATABASE) { it == StepState.Todo }
        kotlinx.coroutines.delay(200)
        assertEquals(listOf("enableApis"), ops.calls)
        assertTrue(cli.calls.none { it == "databases" })
    }

    @Test
    fun diskSpaceBecomesDiskFull(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Missing).apply { failWith = DiskSpaceException(600L * 1024 * 1024) }
        val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        val state = awaitStateOf(r, SetupStepId.TOOLS) { it is StepState.Error }.getValue(SetupStepId.TOOLS)
        assertEquals(SetupErrorId.DISK_FULL, (state as StepState.Error).error.id)
        assertTrue("600 MB" in state.error.headline)
    }

    @Test
    fun toolsInstallErrorsHaveTheirCause(): Unit = runBlocking {
        val cases = listOf(
            FileLockedException(File("/t/node")) to SetupErrorId.FILE_LOCKED,
            java.io.IOException("HTTP 503") to SetupErrorId.DOWNLOAD_FAILED,
        )
        for ((thrown, id) in cases) {
            val tools = FakeTools(ToolsStatus.Missing).apply { failWith = thrown }
            val r = SetupRunner(scope, tools, { cli }, { ops }, workspace, settings, sleep = {})
            r.run(SetupStepId.TOOLS)
            val state = awaitStateOf(r, SetupStepId.TOOLS) { it is StepState.Error }.getValue(SetupStepId.TOOLS)
            assertEquals(id, (state as StepState.Error).error.id)
        }
    }

    @Test
    fun permissionDeniedNamesMissingPermission(): Unit = runBlocking {
        ops.missing = listOf("firebaserules.releases.create")
        ready()
        cli.deployFailures += "Error: HTTP Error: 403, The caller does not have permission"
        runner.run(SetupStepId.RULES)
        val state = awaitState(SetupStepId.RULES) { it is StepState.Error }
        val error = (state as StepState.Error).error
        assertEquals(SetupErrorId.PERMISSION_DENIED, error.id)
        assertTrue("firebaserules.releases.create" in error.headline, error.headline)
        assertTrue("missingPermissions firebaserules.releases.create,firebasehosting.sites.update,firebasestorage.buckets.get" in ops.calls)
    }

    @Test
    fun typeChangeOfAnotherCodebaseDeletesNothing(): Unit = runBlocking {
        ops.functions = listOf(FunctionState("onMailOutboxWritten", "europe-west6", "ACTIVE", emptyList()))
        ready()
        cli.deployFailures += "Error: [other:onFoo(europe-west6)] Changing from an HTTPS function to a background triggered function is not allowed."
        runner.run(SetupStepId.SERVER, serverInput())
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.FUNCTIONS_FAILED_STATE, (state as StepState.Error).error.id)
        assertTrue(cli.calls.none { it.startsWith("deleteFunctions") })
        assertEquals(1, cli.calls.count { it == "deploy hosting,functions" })
    }

    @Test
    fun typeChangeOfAFunctionGoogleDoesNotListDeletesNothing(): Unit = runBlocking {
        ready()
        cli.deployFailures += SetupErrorFixtures.HTTPS_TO_BACKGROUND
        runner.run(SetupStepId.SERVER, serverInput())
        awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertTrue(cli.calls.none { it.startsWith("deleteFunctions") })
    }

    @Test
    fun functionWithoutRegionIsSkipped(): Unit = runBlocking {
        ops.functions = listOf(
            FunctionState("onSignupWritten", "", "FAILED", emptyList()),
            FunctionState("onMailOutboxWritten", "europe-west6", "FAILED", emptyList()),
        )
        ready()
        runner.run(SetupStepId.SERVER, serverInput())
        awaitServerDeployed()
        assertEquals(listOf("deleteFunctions onMailOutboxWritten europe-west6"), cli.calls.filter { it.startsWith("deleteFunctions") })
    }

    @Test
    fun invalidProjectNumberGrantsNothing(): Unit = runBlocking {
        ops.number = "12 3;x"
        ready()
        runner.run(SetupStepId.SERVER, serverInput())
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        val error = (state as StepState.Error).error
        assertEquals(SetupErrorId.UNKNOWN, error.id)
        assertTrue("project number" in error.headline, error.headline)
        assertTrue(ops.calls.none { it.startsWith("ensureRole") })
        assertTrue(cli.calls.none { it.startsWith("deploy") })
    }

    private fun workspaceInput(from: String = "no-reply@collectif-nocturne.ch") =
        StepInput(gmailUser = "info@collectif-nocturne.ch", mailMode = MailMode.WORKSPACE, mailFrom = from)

    private suspend fun signedIn() {
        ready()
        runner.run(SetupStepId.LOGIN)
        awaitState(SetupStepId.LOGIN) { it == StepState.Done }
    }

    @Test
    fun workspaceModeWritesUnusedSecretAndParams(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.SERVER, workspaceInput())
        awaitServerDeployed()
        assertEquals("unused", cli.passwordSeen)
        val env = base.resolve("demo-project/functions/.env.demo-project").readText().lines()
        assertTrue("GMAIL_USER=info@collectif-nocturne.ch" in env, env.toString())
        assertTrue("MAIL_MODE=workspace" in env, env.toString())
        assertTrue("MAIL_FROM=no-reply@collectif-nocturne.ch" in env, env.toString())
        assertTrue("MAIL_SA=noctulist-mail@demo-project.iam.gserviceaccount.com" in env, env.toString())
        assertEquals(
            MailServiceAccountInfo("noctulist-mail@demo-project.iam.gserviceaccount.com", "1234567890"),
            runner.info.value.mailServiceAccount,
        )
        assertEquals(listOf("gmail.googleapis.com"), ops.enabledApis)
        assertTrue("ensureMailServiceAccount" in ops.calls)
    }

    @Test
    fun workspaceModeWithoutFromSendsAsTheAccount(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.SERVER, workspaceInput(from = " "))
        awaitServerDeployed()
        val env = base.resolve("demo-project/functions/.env.demo-project").readText().lines()
        assertTrue("MAIL_FROM=info@collectif-nocturne.ch" in env, env.toString())
    }

    @Test
    fun appPasswordModeKeepsPassword(): Unit = runBlocking {
        ready()
        runner.run(
            SetupStepId.SERVER,
            StepInput(gmailUser = "info@example.org", appPassword = "abcd".toCharArray(), mailMode = MailMode.APP_PASSWORD, mailFrom = "x@example.org"),
        )
        awaitServerDeployed()
        assertEquals("abcd", cli.passwordSeen)
        val env = base.resolve("demo-project/functions/.env.demo-project").readText().lines()
        assertTrue("MAIL_MODE=app_password" in env, env.toString())
        assertTrue("MAIL_SA=" in env, env.toString())
        assertTrue("ensureMailServiceAccount" !in ops.calls)
        assertEquals(null, runner.info.value.mailServiceAccount)
    }

    @Test
    fun serverNotDoneUntilTestMailSent(): Unit = runBlocking {
        settings.mailTestOk = true // from an earlier deploy: a new deploy needs a new test
        signedIn()
        runner.run(SetupStepId.SERVER, workspaceInput())
        awaitServerDeployed()
        assertFalse(settings.mailTestOk)
        assertFalse(runner.info.value.mailTestOk)
        assertTrue(SetupStepId.SERVER !in runner.progress.value.done)
        assertTrue(mailer.sent.isEmpty())
        runner.sendTestMail()
        awaitState(SetupStepId.SERVER) { it == StepState.Done }
        assertEquals(listOf("admin@example.org"), mailer.sent)
        assertTrue(settings.mailTestOk)
        assertTrue(runner.info.value.mailTestOk)
        assertTrue(SetupStepId.SERVER in runner.progress.value.done)
    }

    @Test
    fun delegationMissingKeepsStepOpen(): Unit = runBlocking {
        mailer.outcome = SiteMailOutcome(SiteMailResult.Rejected, "delegation:unauthorized_client")
        signedIn()
        runner.run(SetupStepId.SERVER, workspaceInput())
        awaitServerDeployed()
        runner.sendTestMail()
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        val error = (state as StepState.Error).error
        assertEquals(SetupErrorId.MAIL_DELEGATION_MISSING, error.id)
        assertTrue("delegation:unauthorized_client" in error.headline, error.headline)
        assertTrue("delegation:unauthorized_client" in runner.report(SetupStepId.SERVER))
        assertEquals(1, cli.calls.count { it.startsWith("setSecret") })
        assertFalse(settings.mailTestOk)
        assertTrue(SetupStepId.SERVER !in runner.progress.value.done)
    }

    @Test
    fun testMailWithoutOrganizationIsUnavailable(): Unit = runBlocking {
        mailer.outcome = SiteMailOutcome(SiteMailResult.Unavailable, "")
        signedIn()
        runner.sendTestMail()
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.MAIL_TEST_UNAVAILABLE, (state as StepState.Error).error.id)
    }

    @Test
    fun refusedAppPasswordIsNamed(): Unit = runBlocking {
        mailer.outcome = SiteMailOutcome(SiteMailResult.Rejected, "smtp:535")
        signedIn()
        runner.sendTestMail()
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(SetupErrorId.MAIL_AUTH_REFUSED, (state as StepState.Error).error.id)
    }
}
