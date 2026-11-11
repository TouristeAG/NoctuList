package com.eventmanager.app.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    var passwordSeen: String? = null

    private fun fail(text: String, step: SetupStepId): Nothing = throw CliException(CliErrorClassifier.classify(text, step))

    var accounts = listOf("admin@example.org")
    override suspend fun loginStart(): LoginChallenge { calls += "loginStart"; return LoginChallenge("AB12C", "https://auth.firebase.tools/login?x") }
    override suspend fun loginFinish(code: String) { calls += "loginFinish $code"; accounts = listOf("admin@example.org") }
    override suspend fun loginList() = accounts
    override suspend fun projectsList() = listOf(ProjectInfo("demo-project", "Demo"))
    override suspend fun projectsCreate(id: String, displayName: String, onLine: (String) -> Unit) { calls += "create $id" }
    override suspend fun databases(projectId: String) = databases.also { calls += "databases" }
    override suspend fun firestoreLocations(projectId: String) = listOf("nam5", "eur3", "europe-west6")
    override suspend fun createDatabase(projectId: String, location: String, allowed: List<String>, onLine: (String) -> Unit) { calls += "createDatabase $location" }
    override suspend fun webApps(projectId: String) = apps
    override suspend fun createWebApp(projectId: String, onLine: (String) -> Unit): String { calls += "createWebApp"; return "1:1:web:ab" }
    override suspend fun sdkConfig(projectId: String, appId: String) = WebConfig("AIzaKey", appId, projectId).also { calls += "sdkConfig" }
    override suspend fun deploy(projectId: String, dir: File, only: String, onLine: (String) -> Unit) {
        calls += "deploy $only"
        blockDeploy?.await()
        if (deployFailures.isNotEmpty()) fail(deployFailures.removeAt(0), SetupStepId.SERVER)
    }
    override suspend fun setSecret(projectId: String, dir: File, name: String, value: CharArray, onLine: (String) -> Unit) {
        calls += "setSecret $name"; passwordSeen = String(value)
    }
    override suspend fun npmInstall(dir: File, onLine: (String) -> Unit) { calls += "npmInstall ${dir.name}" }
    override suspend fun functionsList(projectId: String) = emptyList<FunctionInfo>()
    override suspend fun setArtifactPolicy(projectId: String, region: String, onLine: (String) -> Unit) { calls += "setpolicy $region" }
}

private class FakeTools : SetupTools {
    override suspend fun detect(): ToolPaths? = null
    override suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths =
        ToolPaths(File("/n"), File("/npm"), File("/fb"), File("/bin"))
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
        assertEquals(CliErrorKind.EVENTARC_PROPAGATION, (state as StepState.Error).error.kind)
        assertEquals(6, cli.calls.count { it == "deploy hosting,functions" })
    }

    @Test
    fun billingStopsAtOnce(): Unit = runBlocking {
        ready()
        cli.deployFailures += "Your project must be on the Blaze (pay-as-you-go) plan."
        runner.run(SetupStepId.SERVER, StepInput(gmailUser = "info@example.org", appPassword = "x".toCharArray()))
        val state = awaitState(SetupStepId.SERVER) { it is StepState.Error }
        assertEquals(CliErrorKind.BILLING_REQUIRED, (state as StepState.Error).error.kind)
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
}
