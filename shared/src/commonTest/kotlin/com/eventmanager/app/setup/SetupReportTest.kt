package com.eventmanager.app.setup

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupReportTest {
    private val error = SetupError(
        SetupErrorId.BUILD_SA_PERMISSION, SetupStepId.SERVER, RetryPolicy.AFTER_REPAIR, Repair.GRANT_BUILD_ROLE, null,
        "missing permission on the build service account",
        "line\npass: abcd efgh\nlast",
    )

    @Test
    fun reportHasStepIdHeadlineAndNoSecret() {
        val raw = SetupReport.build(
            SetupStepId.SERVER, error, listOf(RepairNote(Repair.GRANT_BUILD_ROLE, "role granted")),
            os = "Mac OS X 14", versions = "node v22, firebase-tools 15",
        )
        val report = OutputRedactor { listOf("abcd efgh") }.redact(raw)
        assertContains(report, "Step 9")
        assertContains(report, "BUILD_SA_PERMISSION")
        assertContains(report, "missing permission on the build service account")
        assertContains(report, "role granted")
        assertContains(report, "Mac OS X 14")
        assertFalse("abcd" in report)
    }

    @Test
    fun reportKeepsOnlyTheLast40LinesOfDetails() {
        val details = (1..100).joinToString("\n") { "row$it" }
        val report = SetupReport.build(SetupStepId.SITE, error.copy(details = details), emptyList(), "os", "v")
        assertTrue("row100" in report && "row61" in report)
        assertFalse("row60\n" in report + "\n")
    }
}
