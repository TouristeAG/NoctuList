package com.eventmanager.app.setup

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleOpsTest {
    private val tools = ToolPaths(
        node = File("/t/node/bin/node"),
        npm = File("/t/node/lib/node_modules/npm/bin/npm-cli.js"),
        firebase = File("/t/npm-global/lib/node_modules/firebase-tools/lib/bin/firebase.js"),
        binDir = File("/t/node/bin"),
    )
    private val helper = File("/t/helper/noctulist-gcp.cjs")
    private val libDir = "/t/npm-global/lib/node_modules/firebase-tools/lib"
    private val pid = "noctuliste---lethal-tempo"
    private val env = SetupEnvironment(java.nio.file.Files.createTempDirectory("gops test").toFile(), null)
    private val redactor = OutputRedactor { emptyList() }
    private val logs = SetupLogFiles(env.logDir, redactor) { 0L }

    private fun ops(runner: FakeProcessRunner) = HelperGoogleOps(runner, tools, helper, env, logs, redactor)
    private fun ok(data: String) = 0 to """{"ok":true,"data":$data}"""

    @Test
    fun ensureRoleBuildsFixedCommand() = runBlocking {
        val runner = FakeProcessRunner { ok("""{"changed":true}""") }
        val changed = ops(runner).ensureRole(pid, "serviceAccount:a@b.iam.gserviceaccount.com", "roles/datastore.user")
        assertTrue(changed)
        assertEquals(
            listOf(
                tools.node.path, helper.path, libDir, "ensure-role", "--project", pid,
                "--member", "serviceAccount:a@b.iam.gserviceaccount.com", "--role", "roles/datastore.user",
            ),
            runner.calls.single(),
        )
        assertEquals(env.workDir, runner.workDirs.single())
        assertEquals(CommandLimits.READ, runner.limitsSeen.single())
    }

    @Test
    fun invalidArgumentsNeverReachTheCommandLine() = runBlocking {
        val runner = FakeProcessRunner { ok("{}") }
        val o = ops(runner)
        assertFailsWith<IllegalArgumentException> { o.ensureRole(pid, "x; rm -rf /", "roles/editor") }
        assertFailsWith<IllegalArgumentException> { o.ensureRole(pid, "user:a@b.c", "editor") }
        assertFailsWith<IllegalArgumentException> { o.projectNumber("Bad Id") }
        assertFailsWith<IllegalArgumentException> { o.enableApis(pid, listOf("evil.com")) }
        assertFailsWith<IllegalArgumentException> { o.linkBilling(pid, "accounts/1") }
        assertFailsWith<IllegalArgumentException> { o.functions(pid, "a b") }
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun errorJsonBecomesGoogleOpsException() = runBlocking {
        val runner = FakeProcessRunner {
            0 to """{"ok":false,"error":{"status":403,"reason":"SERVICE_DISABLED","message":"off","service":"x.googleapis.com"}}"""
        }
        val e = assertFailsWith<GoogleOpsException> { ops(runner).projectNumber(pid) }
        assertEquals(GoogleApiError(403, "SERVICE_DISABLED", "off", "x.googleapis.com"), e.error)
    }

    @Test
    fun nonJsonOutputIsUnknownError() = runBlocking {
        val runner = FakeProcessRunner { 1 to "Error: boom\n    at foo (bar.js:1)\n\n" }
        val e = assertFailsWith<GoogleOpsException> { ops(runner).projectNumber(pid) }
        assertEquals(0, e.error.status)
        assertEquals("HELPER_CRASHED", e.error.reason)
        assertEquals("at foo (bar.js:1)", e.error.message)
    }

    @Test
    fun parsesResultsAndUsesLongLimitsForSlowCommands() = runBlocking {
        val runner = FakeProcessRunner { cmd ->
            when (cmd[3]) {
                "check-auth" -> ok("""{"email":"me@x.com"}""")
                "project-number" -> ok("""{"number":"123"}""")
                "test-permissions" -> ok("""{"granted":["a.b"],"missing":["c.d"]}""")
                "billing-status" -> ok("""{"enabled":false,"accounts":[{"name":"billingAccounts/AB-1","displayName":"Main"}]}""")
                "functions-status" -> ok("""{"functions":[{"name":"f","region":"us-central1","state":"ACTIVE","messages":["m"]}]}""")
                "storage-status" -> ok("""{"bucket":null}""")
                "rules-status" -> ok("""{"firestore":true,"storage":false}""")
                "hosting-status" -> ok("""{"live":true,"url":"u"}""")
                "ensure-mail-sa" -> ok("""{"email":"s@p.iam.gserviceaccount.com","clientId":"99"}""")
                else -> ok("{}")
            }
        }
        val o = ops(runner)
        assertEquals("me@x.com", o.checkAuth())
        assertEquals("123", o.projectNumber(pid))
        assertEquals(listOf("c.d"), o.missingPermissions(pid, listOf("a.b", "c.d")))
        assertEquals("a.b,c.d", runner.calls.last().let { it[it.indexOf("--permissions") + 1] })
        assertEquals(BillingStatus(false, listOf(BillingAccount("billingAccounts/AB-1", "Main"))), o.billing(pid))
        assertEquals(listOf(FunctionState("f", "us-central1", "ACTIVE", listOf("m"))), o.functions(pid, "default"))
        assertEquals(null, o.storageBucket(pid))
        assertEquals(true to false, o.rulesReleased(pid))
        assertTrue(o.hostingLive(pid))
        o.linkBilling(pid, "billingAccounts/AB-1")
        o.enableApis(pid, listOf("run.googleapis.com"))
        assertEquals(MailServiceAccount("s@p.iam.gserviceaccount.com", "99"), o.ensureMailServiceAccount(pid))
        val slow = CommandLimits(420_000, 420_000)
        runner.calls.forEachIndexed { i, c ->
            val expected = if (c[3] == "enable-apis" || c[3] == "ensure-mail-sa") slow else CommandLimits.READ
            assertEquals(expected, runner.limitsSeen[i], c[3])
        }
    }

    @Test
    fun eachStepViewLogsToItsOwnStep() = runBlocking {
        val runner = FakeProcessRunner { cmd ->
            when (cmd[3]) {
                "rules-status" -> 0 to "rules call\n" + """{"ok":true,"data":{"firestore":true,"storage":true}}"""
                else -> 0 to "hosting call\n" + """{"ok":true,"data":{"live":true}}"""
            }
        }
        val base = ops(runner)
        val rules = base.forStep(SetupStepId.RULES)
        val site = base.forStep(SetupStepId.SITE) // made after: must not move the rules calls to the site log
        rules.rulesReleased(pid)
        site.hostingLive(pid)
        val rulesLog = logs.latest(SetupStepId.RULES)!!.readText()
        val siteLog = logs.latest(SetupStepId.SITE)!!.readText()
        assertTrue("rules call" in rulesLog && "hosting call" !in rulesLog, rulesLog)
        assertTrue("hosting call" in siteLog && "rules call" !in siteLog, siteLog)
    }

    @Test
    fun mailServiceAccountIsReadOnly() = runBlocking {
        var answer = ok("""{"email":"s@p.iam.gserviceaccount.com","clientId":"99"}""")
        val runner = FakeProcessRunner { answer }
        val o = ops(runner)
        assertEquals(MailServiceAccount("s@p.iam.gserviceaccount.com", "99"), o.mailServiceAccount(pid))
        assertEquals(listOf(tools.node.path, helper.path, libDir, "get-mail-sa", "--project", pid), runner.calls.single())
        assertEquals(CommandLimits.READ, runner.limitsSeen.single())
        answer = ok("null")
        assertEquals(null, o.mailServiceAccount(pid))
    }
}
