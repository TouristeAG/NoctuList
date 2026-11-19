package com.eventmanager.app.setup

import com.eventmanager.app.ui.setup.RetryAction
import com.eventmanager.app.ui.setup.retryAction
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryActionTest {
    private fun error(id: SetupErrorId, repair: Repair?) =
        StepState.Error(SetupError(id, SetupStepId.TOOLS, RetryPolicy.NONE, repair, null, "", ""))

    @Test
    fun brokenToolsOnAnyStepAreReinstalled() {
        // The error says "Click Retry: the app installs them again", whichever step it shows on.
        for (step in listOf(SetupStepId.TOOLS, SetupStepId.RULES, SetupStepId.SERVER)) {
            assertEquals(RetryAction.REINSTALL_TOOLS, retryAction(step, error(SetupErrorId.TOOLS_BROKEN, Repair.REINSTALL_TOOLS)), step.name)
        }
    }

    @Test
    fun otherErrorsRunTheStepOrResendTheTestMail() {
        assertEquals(RetryAction.RUN_STEP, retryAction(SetupStepId.RULES, error(SetupErrorId.TRANSIENT, null)))
        assertEquals(RetryAction.SEND_TEST_MAIL, retryAction(SetupStepId.SERVER, error(SetupErrorId.MAIL_TEST_FAILED, null)))
        assertEquals(RetryAction.RUN_STEP, retryAction(SetupStepId.SITE, StepState.Todo))
    }
}
