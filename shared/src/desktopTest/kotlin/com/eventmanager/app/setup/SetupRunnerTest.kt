package com.eventmanager.app.setup

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
    override suspend fun databases(projectId: String) = databases.also { calls += "databases" }
    override suspend fun firestoreLocations(projectId: String) = listOf("nam5", "eur3", "europe-west6")
    override suspend fun createDatabase(projectId: String, location: String, allowed: List<String>, onLine: (String) -> Unit) { calls += "createDatabase $location" }
    override suspend fun webApps(projectId: String) = apps
    override suspend fun createWebApp(projectId: String, onLine: (String) -> Unit): String { calls += "createWebApp"; return "1:1:web:ab" }
    override suspend fun sdkConfig(projectId: String, appId: String) = WebConfig("AIzaKey", appId, projectId).also { calls += "sdkConfig" }
    override suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit) {
        calls += "deploy $only"
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
    private val runner = SetupRunner(scope, FakeTools(), { cli }, workspace, settings, sleep = { sleeps += it })

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

    @Test
    fun serverRunsInTheSafeOrder(): Unit = runBlocking {
        ready()
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "abcd efgh ijkl mnop".toCharArray()))
        awaitState(SetupStepId.SERVER) { it == StepState.Done }
        assertEquals(
            listOf("databases", "setSecret GMAIL_APP_PASSWORD", "npmInstall functions", "deploy hosting,functions", "setpolicy europe-west6"),
            cli.calls,
        )
        assertEquals(
            "GMAIL_USER=info@example.org\nREGION=europe-west6\n",
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
        val r = SetupRunner(scope, FakeTools(), { probingCli }, workspace, settings, sleep = {}, secrets = secrets)
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        r.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "abcd efgh".toCharArray()))
        withTimeout(5_000) { r.states.first { it[SetupStepId.SERVER] == StepState.Done } }
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
        val r = SetupRunner(scope, FakeTools(), { failing }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(), { failing }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(), { probing }, workspace, settings, sleep = {}, secrets = secrets)
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
        awaitState(SetupStepId.SERVER) { it == StepState.Done }
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun eventarcIsRetriedThenSucceeds(): Unit = runBlocking {
        ready()
        cli.deployFailures += listOf("Permission denied while using the Eventarc Service Agent.", "Retry the deployment in a few minutes. Eventarc Service Agent")
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "x".toCharArray()))
        awaitState(SetupStepId.SERVER) { it == StepState.Done }
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(StepState.Done, r.states.value[SetupStepId.TOOLS])
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
    }

    @Test
    fun refreshUnticksToolsWhenBroken(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        val r = SetupRunner(scope, FakeTools(ToolsStatus.Broken("node does not start")), { cli }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.TOOLS])
        assertTrue(SetupStepId.TOOLS !in r.progress.value.done)
        assertEquals(ToolsState.BROKEN, r.info.value.tools)
    }

    @Test
    fun refreshReportsReadyAndOutdatedTools(): Unit = runBlocking {
        val ready = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
        ready.refresh()
        assertEquals(ToolsState.READY, ready.info.value.tools)
        val old = SetupRunner(scope, FakeTools(ToolsStatus.Outdated("v22.1.0", "15.0.0")), { cli }, workspace, settings, sleep = {})
        old.refresh()
        assertEquals(ToolsState.OUTDATED, old.info.value.tools)
        assertEquals(StepState.Todo, old.states.value[SetupStepId.TOOLS])
    }

    @Test
    fun reinstallRemovesThenInstalls(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Broken("x"))
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
        r.reinstallTools()
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] is StepState.Error } }
        assertEquals(ToolsState.BROKEN, r.info.value.tools)
    }

    @Test
    fun installThatDoesNotAnswerIsBroken(): Unit = runBlocking {
        val tools = FakeTools(ToolsStatus.Missing).apply { installWorks = false }
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, tools, { cli }, workspace, settings, sleep = {})
        r.run(SetupStepId.TOOLS)
        withTimeout(5_000) { r.states.first { it[SetupStepId.TOOLS] == StepState.Done } }
        assertEquals(listOf("remove", "install"), tools.calls)
    }

    @Test
    fun stepsWorkWithoutRunningToolsAgainWhenToolsExist(): Unit = runBlocking {
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
        r.run(SetupStepId.LOGIN)
        withTimeout(5_000) { r.states.first { it[SetupStepId.LOGIN] == StepState.Done } }
    }

    @Test
    fun refreshListsLocationsWhenTheDatabaseIsMissing(): Unit = runBlocking {
        cli.databases = emptyList()
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val again = SetupRunner(scope, FakeTools(), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
        r.refresh()
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertEquals("", r.info.value.account)
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
    }

    @Test
    fun switchAccountAfterFailedLogoutKeepsError(): Unit = runBlocking {
        settings.progressJson = SetupProgress(projectId = "demo-project", done = setOf(SetupStepId.TOOLS, SetupStepId.LOGIN)).encode()
        cli.logoutFails = true
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
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
        val r = SetupRunner(scope, FakeTools(installed = true), { cli }, workspace, settings, sleep = {})
        r.refresh()
        kotlinx.coroutines.coroutineScope {
            launch { r.refresh() }
            launch { r.signOut() }
        }
        assertEquals("", r.info.value.account)
        assertEquals(StepState.Todo, r.states.value[SetupStepId.LOGIN])
        assertTrue(SetupStepId.LOGIN !in r.progress.value.done)
    }
}
