package com.eventmanager.app.setup

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records every command and answers with canned output. */
class FakeProcessRunner(
    private val onRun: (File?) -> Unit = {},
    private val answer: (List<String>) -> Pair<Int, String> = { 0 to "" },
) : ProcessRunner {
    val calls = mutableListOf<List<String>>()
    val stdins = mutableListOf<ByteArray?>()
    val stdinBuffers = mutableListOf<ByteArray?>()
    val envs = mutableListOf<Map<String, String>>()
    val workDirs = mutableListOf<File?>()
    val limitsSeen = mutableListOf<CommandLimits>()

    override suspend fun run(
        cmd: List<String>,
        workDir: File?,
        env: Map<String, String>,
        stdin: ByteArray?,
        limits: CommandLimits,
        onLine: (String) -> Unit,
    ): Int {
        calls += cmd
        workDirs += workDir
        onRun(workDir)
        limitsSeen += limits
        stdins += stdin?.copyOf() // the CLI wipes its buffer afterwards
        stdinBuffers += stdin
        envs += env
        val (code, out) = answer(cmd)
        out.lines().forEach(onLine)
        return code
    }
}

class FirebaseCliTest {
    private val tools = ToolPaths(
        node = File("/t/node/bin/node"),
        npm = File("/t/node/lib/node_modules/npm/bin/npm-cli.js"),
        firebase = File("/t/npm-global/lib/node_modules/firebase-tools/lib/bin/firebase.js"),
        binDir = File("/t/node/bin"),
    )
    private val fb = tools.node.path
    private val js = tools.firebase.path
    private val pid = "noctuliste---lethal-tempo"

    private val appData = java.nio.file.Files.createTempDirectory("cli test").toFile()
    private val env = SetupEnvironment(appData, null)
    private val emptyList = """{"status":"success","result":[]}"""

    private var logs = SetupLogFiles(env.logDir, OutputRedactor { emptyList() }) { 0L }

    private fun cli(runner: FakeProcessRunner, secrets: () -> List<String> = { emptyList() }): FirebaseCli {
        val redactor = OutputRedactor(secrets)
        logs = SetupLogFiles(env.logDir, redactor) { 0L }
        return FirebaseCli(runner, tools, redactor, env, logs)
    }

    @Test
    fun firebaseCommandsRunInWorkDirByDefault() = runBlocking {
        val runner = FakeProcessRunner { 0 to emptyList }
        cli(runner).projectsList()
        assertEquals(env.workDir, runner.workDirs.single())
        val site = File("/a b/site")
        cli(runner).deploy(pid, site, "hosting") {}
        assertEquals(site, runner.workDirs.last())
        assertEquals(CommandLimits.DEPLOY, runner.limitsSeen.last())
    }

    @Test
    fun failedCommandCopiesDebugLogIntoStepLog() = runBlocking {
        val runner = FakeProcessRunner(onRun = { dir ->
            dir!!.resolve("firebase-debug.log").writeText("debug line with secret-value\n")
        }) { 1 to "boom" }
        val c = cli(runner) { listOf("secret-value") }
        val shown = mutableListOf<String>()
        val e = assertFailsWith<CliException> { c.createHostingSite(pid) { shown += it } }
        val log = logs.latest(SetupStepId.SITE)!!.readText()
        assertTrue("debug line with" in log)
        assertFalse("secret-value" in log)
        assertFalse(env.workDir.resolve("firebase-debug.log").exists())
        assertFalse(shown.any { "debug line" in it } || e.error.toString().contains("debug line"))
    }

    @Test
    fun setSecretSendsPasswordOnStdinOnly() = runBlocking {
        val runner = FakeProcessRunner { 0 to "✔ Created a new secret version" }
        val password = "p@\$\$ \"w o\""
        val log = mutableListOf<String>()
        cli(runner) { listOf(password) }.setSecret(pid, File("/w s"), "GMAIL_APP_PASSWORD", password.toCharArray()) { log += it }
        assertEquals(
            listOf(fb, js, "functions:secrets:set", "GMAIL_APP_PASSWORD", "--data-file", "-", "--force", "--project", pid),
            runner.calls.single(),
        )
        assertContentEquals(password.encodeToByteArray(), runner.stdins.single())
        assertTrue(runner.stdinBuffers.single()!!.all { it == 0.toByte() }, "password bytes wiped after use")
        assertFalse(runner.calls.single().any { it.contains("w o") })
        assertFalse(log.any { it.contains(password) })
    }

    @Test
    fun redactsSecretEchoedByTheTool() = runBlocking {
        val password = "abcd efgh"
        val runner = FakeProcessRunner { 0 to "echo abcd efgh done" }
        val log = mutableListOf<String>()
        cli(runner) { listOf(password) }.setSecret(pid, File("/w"), "GMAIL_APP_PASSWORD", password.toCharArray()) { log += it }
        assertTrue(log.any { it.contains("••••") })
        assertFalse(log.any { it.contains(password) })
    }

    @Test
    fun deployArguments() = runBlocking {
        val runner = FakeProcessRunner()
        cli(runner).deploy(pid, File("/a b/site"), "hosting,functions") {}
        assertEquals(
            listOf(fb, js, "deploy", "--only", "hosting,functions", "--non-interactive", "--force", "--project", pid),
            runner.calls.single(),
        )
        assertTrue(runner.envs.single().getValue("PATH").startsWith("/t/node/bin"))
    }

    @Test
    fun invalidProjectIdNeverRuns() = runBlocking {
        val runner = FakeProcessRunner()
        assertFailsWith<IllegalArgumentException> { cli(runner).projectsCreate("Bad;rm", "Name") {} }
        assertFailsWith<IllegalArgumentException> { cli(runner).deploy("x y", File("/w"), "hosting") {} }
        assertTrue(runner.calls.isEmpty())
    }

    private val remoteLoginOutput = listOf(
        "",
        "To sign in to the Firebase CLI:",
        "",
        "1. Take note of your session ID:",
        "",
        "   \u001B[1mAB12C\u001B[22m",
        "",
        "2. Visit the URL below on any device and follow the instructions to get your code:",
        "",
        "   https://auth.firebase.tools/login?code_challenge=xyz&session=abc-123&attest=q",
        "",
        "3. Complete the login by running:",
        "",
        "   \u001B[1mfirebase login <authorizationCode>\u001B[22m",
    ).joinToString("\n")

    @Test
    fun loginStartParsesSessionAndUrl(): Unit = runBlocking {
        val runner = FakeProcessRunner { 0 to remoteLoginOutput }
        val challenge = cli(runner).loginStart()
        assertEquals("AB12C", challenge.sessionId)
        assertEquals("https://auth.firebase.tools/login?code_challenge=xyz&session=abc-123&attest=q", challenge.url)
        assertEquals(listOf(fb, js, "login"), runner.calls.single())
    }

    @Test
    fun loginFinishPassesTheCodeAsOneArgument(): Unit = runBlocking {
        val runner = FakeProcessRunner { 0 to "Success! Logged in as a@example.org" }
        cli(runner).loginFinish("4/0AVMBsJ_x-y.z~Q12345")
        assertEquals(listOf(fb, js, "login", "4/0AVMBsJ_x-y.z~Q12345"), runner.calls.single())
        assertFailsWith<IllegalArgumentException> { cli(runner).loginFinish("code; rm -rf /") }
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun createHostingSiteUsesTheProjectIdAsSiteId(): Unit = runBlocking {
        val runner = FakeProcessRunner()
        cli(runner).createHostingSite(pid) {}
        assertEquals(listOf(fb, js, "hosting:sites:create", pid, "--non-interactive", "--project", pid), runner.calls.single())
    }

    @Test
    fun loginListReturnsEmailsAndNeverLogsTokens() = runBlocking {
        val json = """{"status":"success","result":[{"user":{"email":"a@example.org"},"tokens":{"refresh_token":"SECRET_REFRESH"}}]}"""
        val runner = FakeProcessRunner { 0 to json }
        assertEquals(listOf("a@example.org"), cli(runner).loginList())
        assertEquals(listOf(fb, js, "login:list", "--json"), runner.calls.single())
    }

    @Test
    fun parsesProjectsDatabasesAppsFunctions() = runBlocking {
        val runner = FakeProcessRunner { cmd ->
            0 to when (cmd[2]) {
                "projects:list" -> """{"status":"success","result":[{"projectId":"$pid","displayName":"NoctuListe"}]}"""
                "firestore:databases:list" -> """{"status":"success","result":[{"name":"projects/$pid/databases/(default)","locationId":"europe-west6"}]}"""
                "firestore:locations" -> """{"status":"success","result":[{"locationId":"nam5"},{"locationId":"europe-west6"}]}"""
                "apps:list" -> """{"status":"success","result":[{"appId":"1:568:web:bbc6","displayName":"Web"}]}"""
                "apps:sdkconfig" -> """{"status":"success","result":{"fileName":"x","sdkConfig":{"projectId":"$pid","appId":"1:568:web:bbc6","apiKey":"AIzaTest"}}}"""
                "functions:list" -> """{"status":"success","result":[{"id":"onSignupWritten","region":"europe-west6"}]}"""
                else -> error("unexpected ${cmd[2]}")
            }
        }
        val c = cli(runner)
        assertEquals(listOf(ProjectInfo(pid, "NoctuListe")), c.projectsList())
        assertEquals(listOf(DatabaseInfo("(default)", "europe-west6")), c.databases(pid))
        assertEquals(listOf("nam5", "europe-west6"), c.firestoreLocations(pid))
        assertEquals(listOf("1:568:web:bbc6"), c.webApps(pid))
        assertEquals(WebConfig("AIzaTest", "1:568:web:bbc6", pid), c.sdkConfig(pid, "1:568:web:bbc6"))
        assertEquals(listOf(FunctionInfo("onSignupWritten", "europe-west6")), c.functionsList(pid))
    }

    @Test
    fun jsonAfterWarningLinesStillParses() = runBlocking {
        val runner = FakeProcessRunner { 0 to "⚠  update available\n{\"status\":\"success\",\"result\":[]}" }
        assertEquals(emptyList(), cli(runner).projectsList())
    }

    @Test
    fun failureIsClassified() = runBlocking {
        val runner = FakeProcessRunner { 1 to "Permission denied while using the Eventarc Service Agent." }
        val e = assertFailsWith<CliException> { cli(runner).deploy(pid, File("/w"), "functions") {} }
        assertEquals(CliErrorKind.EVENTARC_PROPAGATION, e.error.kind)
    }

    @Test
    fun npmInstallUsesBundledNpm() = runBlocking {
        val runner = FakeProcessRunner()
        cli(runner).npmInstall(File("/a b/site/functions")) {}
        assertEquals(listOf(tools.node.path, tools.npm.path, "install", "--no-audit", "--no-fund"), runner.calls.single())
    }
}

class ToolPathsTest {
    @Test
    fun windowsRunsNodeWithScriptsNotCmdShims() {
        val p = ToolPathsResolver.bundled(File("C:/Users/Jean Dupont/.noctulist/tools"), "Windows 11")
        assertTrue(p.node.path.endsWith("node.exe"))
        assertEquals(File("C:/Users/Jean Dupont/.noctulist/tools/node/node_modules/npm/bin/npm-cli.js"), p.npm)
        assertEquals(File("C:/Users/Jean Dupont/.noctulist/tools/npm-global/node_modules/firebase-tools/lib/bin/firebase.js"), p.firebase)
        assertTrue(listOf(p.node, p.npm, p.firebase).none { it.name.endsWith(".cmd") })
    }

    @Test
    fun unixBundledLayout() {
        val p = ToolPathsResolver.bundled(File("/a b/tools"), "Mac OS X")
        assertEquals(File("/a b/tools/node/bin/node"), p.node)
        assertEquals(File("/a b/tools/node/lib/node_modules/npm/bin/npm-cli.js"), p.npm)
        assertEquals(File("/a b/tools/npm-global/lib/node_modules/firebase-tools/lib/bin/firebase.js"), p.firebase)
    }

    @Test
    fun fromPathFollowsLinksToTheScripts() {
        val root = java.nio.file.Files.createTempDirectory("path tools").toFile()
        try {
            val bin = root.resolve("bin").apply { mkdirs() }
            val fbJs = root.resolve("lib/node_modules/firebase-tools/lib/bin/firebase.js").apply { parentFile.mkdirs(); writeText("") }
            val npmJs = root.resolve("lib/node_modules/npm/bin/npm-cli.js").apply { parentFile.mkdirs(); writeText("") }
            bin.resolve("node").writeText("")
            java.nio.file.Files.createSymbolicLink(bin.resolve("firebase").toPath(), fbJs.toPath())
            java.nio.file.Files.createSymbolicLink(bin.resolve("npm").toPath(), npmJs.toPath())
            val p = ToolPathsResolver.fromPath(bin.path, "Mac OS X") { it.exists() }
            assertEquals(fbJs.canonicalFile, p?.firebase)
            assertEquals(npmJs.canonicalFile, p?.npm)
            bin.resolve("firebase").delete()
            bin.resolve("firebase").writeText("#!/bin/sh")
            assertNull(ToolPathsResolver.fromPath(bin.path, "Mac OS X") { it.exists() })
        } finally {
            root.deleteRecursively()
        }
    }
}
