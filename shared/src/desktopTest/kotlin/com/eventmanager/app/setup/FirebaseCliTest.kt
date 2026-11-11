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
class FakeProcessRunner(private val answer: (List<String>) -> Pair<Int, String> = { 0 to "" }) : ProcessRunner {
    val calls = mutableListOf<List<String>>()
    val stdins = mutableListOf<ByteArray?>()
    val stdinBuffers = mutableListOf<ByteArray?>()
    val envs = mutableListOf<Map<String, String>>()

    override suspend fun run(
        cmd: List<String>,
        workDir: File?,
        env: Map<String, String>,
        stdin: ByteArray?,
        onLine: (String) -> Unit,
    ): Int {
        calls += cmd
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
        npm = File("/t/node/bin/npm"),
        firebase = File("/t/npm-global/bin/firebase"),
        binDir = File("/t/node/bin"),
    )
    private val fb = tools.firebase.path
    private val pid = "noctuliste---lethal-tempo"

    private fun cli(runner: FakeProcessRunner, secrets: () -> List<String> = { emptyList() }) =
        FirebaseCli(runner, tools, OutputRedactor(secrets))

    @Test
    fun setSecretSendsPasswordOnStdinOnly() = runBlocking {
        val runner = FakeProcessRunner { 0 to "✔ Created a new secret version" }
        val password = "p@\$\$ \"w o\""
        val log = mutableListOf<String>()
        cli(runner) { listOf(password) }.setSecret(pid, File("/w s"), "GMAIL_APP_PASSWORD", password.toCharArray()) { log += it }
        assertEquals(
            listOf(fb, "functions:secrets:set", "GMAIL_APP_PASSWORD", "--data-file", "-", "--force", "--project", pid),
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
            listOf(fb, "deploy", "--only", "hosting,functions", "--non-interactive", "--force", "--project", pid),
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

    @Test
    fun loginListReturnsEmailsAndNeverLogsTokens() = runBlocking {
        val json = """{"status":"success","result":[{"user":{"email":"a@example.org"},"tokens":{"refresh_token":"SECRET_REFRESH"}}]}"""
        val runner = FakeProcessRunner { 0 to json }
        assertEquals(listOf("a@example.org"), cli(runner).loginList())
        assertEquals(listOf(fb, "login:list", "--json"), runner.calls.single())
    }

    @Test
    fun parsesProjectsDatabasesAppsFunctions() = runBlocking {
        val runner = FakeProcessRunner { cmd ->
            0 to when (cmd[1]) {
                "projects:list" -> """{"status":"success","result":[{"projectId":"$pid","displayName":"NoctuListe"}]}"""
                "firestore:databases:list" -> """{"status":"success","result":[{"name":"projects/$pid/databases/(default)","locationId":"europe-west6"}]}"""
                "firestore:locations" -> """{"status":"success","result":[{"locationId":"nam5"},{"locationId":"europe-west6"}]}"""
                "apps:list" -> """{"status":"success","result":[{"appId":"1:568:web:bbc6","displayName":"Web"}]}"""
                "apps:sdkconfig" -> """{"status":"success","result":{"fileName":"x","sdkConfig":{"projectId":"$pid","appId":"1:568:web:bbc6","apiKey":"AIzaTest"}}}"""
                "functions:list" -> """{"status":"success","result":[{"id":"onSignupWritten","region":"europe-west6"}]}"""
                else -> error("unexpected ${cmd[1]}")
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
        assertEquals(listOf(tools.npm.path, "install", "--no-audit", "--no-fund"), runner.calls.single())
    }
}

class ToolPathsTest {
    @Test
    fun windowsNamesForBundledTools() {
        val p = ToolPathsResolver.bundled(File("/a b/tools"), "Windows 11")
        assertTrue(p.node.path.endsWith("node.exe"))
        assertTrue(p.npm.path.endsWith("npm.cmd"))
        assertTrue(p.firebase.path.endsWith("firebase.cmd"))
    }

    @Test
    fun unixNamesForBundledTools() {
        val p = ToolPathsResolver.bundled(File("/a b/tools"), "Mac OS X")
        assertEquals(File("/a b/tools/node/bin/node"), p.node)
        assertEquals(File("/a b/tools/npm-global/bin/firebase"), p.firebase)
    }

    @Test
    fun fromPathNeedsNodeAndFirebase() {
        val have = setOf(File("/usr/local/bin/node"), File("/usr/local/bin/npm"), File("/usr/local/bin/firebase"))
        val p = ToolPathsResolver.fromPath("/usr/bin:/usr/local/bin", "Mac OS X") { it in have }
        assertEquals(File("/usr/local/bin/firebase"), p?.firebase)
        assertNull(ToolPathsResolver.fromPath("/usr/local/bin", "Mac OS X") { it.name != "node" && it in have })
    }
}
