package com.eventmanager.app.setup

import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.setup_err_login_expired_steps
import com.eventmanager.app.resources.setup_err_login_expired_steps_login
import com.eventmanager.app.ui.setup.errorSteps
import kotlin.test.Test
import kotlin.test.assertEquals

class SetupErrorTextsTest {
    @Test
    fun expiredSignInOnTheSignInStepSaysToClickRetry() {
        assertEquals(Res.string.setup_err_login_expired_steps_login, errorSteps(SetupErrorId.LOGIN_EXPIRED, SetupStepId.LOGIN))
        assertEquals(Res.string.setup_err_login_expired_steps, errorSteps(SetupErrorId.LOGIN_EXPIRED, SetupStepId.RULES))
    }
}
