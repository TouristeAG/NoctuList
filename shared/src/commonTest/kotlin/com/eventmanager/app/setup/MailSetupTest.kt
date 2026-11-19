package com.eventmanager.app.setup

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailSetupTest {
    @Test
    fun suggestedModeByDomain() {
        assertEquals(MailMode.APP_PASSWORD, MailSetup.suggestedMode("a@gmail.com"))
        assertEquals(MailMode.APP_PASSWORD, MailSetup.suggestedMode("A@GoogleMail.com "))
        assertEquals(MailMode.WORKSPACE, MailSetup.suggestedMode("a@collectif-nocturne.ch"))
    }

    @Test
    fun failureCodesMapToErrors() {
        assertEquals(SetupErrorId.MAIL_DELEGATION_MISSING, MailSetup.failureToError("delegation:unauthorized_client"))
        assertEquals(SetupErrorId.MAIL_DELEGATION_MISSING, MailSetup.failureToError("delegation:access_denied"))
        assertEquals(SetupErrorId.MAIL_AUTH_REFUSED, MailSetup.failureToError("smtp:535"))
        assertEquals(SetupErrorId.MAIL_AUTH_REFUSED, MailSetup.failureToError("smtp:534"))
        assertEquals(SetupErrorId.MAIL_TEST_FAILED, MailSetup.failureToError("gmail:403:forbidden"))
        assertEquals(SetupErrorId.MAIL_TEST_FAILED, MailSetup.failureToError("delegation:network"))
        assertEquals(SetupErrorId.MAIL_TEST_FAILED, MailSetup.failureToError("config:no_service_account"))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun testMailIsAPlainFrenchMessageToTheAdmin() {
        val raw = TestMailMessage.build("admin@example.org", "Collectif", 0L)
        val (head, body) = raw.split("\r\n\r\n", limit = 2)
        assertTrue("To: admin@example.org" in head.lines())
        assertTrue("Content-Type: text/plain; charset=UTF-8" in head.lines())
        assertTrue("Date: Thu, 01 Jan 1970 00:00:00 +0000" in head.lines())
        val subject = head.lines().first { it.startsWith("Subject: ") }.removePrefix("Subject: ")
        assertEquals("NoctuList : e-mail de test", subject)
        val text = Base64.decode(body.replace("\r\n", "")).decodeToString()
        assertEquals("Cet e-mail confirme que le site peut envoyer des messages.", text.trim())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun testMailFollowsAnEnglishApp() {
        val raw = TestMailMessage.build("admin@example.org", "Collectif", 0L, language = "en")
        val (head, body) = raw.split("\r\n\r\n", limit = 2)
        assertTrue("Subject: NoctuList: test e-mail" in head.lines())
        assertEquals("This e-mail confirms that the site can send messages.", Base64.decode(body.replace("\r\n", "")).decodeToString().trim())
    }

    @Test
    fun testMailHeadersCannotBeInjected() {
        val raw = TestMailMessage.build("admin@example.org\r\nBcc: x@y.z", "A\r\nBcc: x@y.z", 0L)
        val head = raw.substringBefore("\r\n\r\n")
        assertTrue(head.lines().none { it.startsWith("Bcc") })
    }

    @Test
    fun fromFollowsTheSenderUntilEdited() {
        assertEquals("b@x.ch", MailSetup.followSender(previousSender = "a@x.ch", sender = "b@x.ch", from = "a@x.ch"))
        assertEquals("b@x.ch", MailSetup.followSender(previousSender = "a@x.ch", sender = "b@x.ch", from = ""))
        assertEquals("no-reply@x.ch", MailSetup.followSender(previousSender = "a@x.ch", sender = "b@x.ch", from = "no-reply@x.ch"))
    }

    @Test
    fun modeIsSuggestedOnlyForAFinishedAddressUntilPicked() {
        assertEquals(MailMode.WORKSPACE, MailSetup.modeAfterTyping("x@asso.ch", MailMode.APP_PASSWORD, picked = false))
        assertEquals(MailMode.APP_PASSWORD, MailSetup.modeAfterTyping("x@gmail.com", MailMode.WORKSPACE, picked = false))
        assertEquals(MailMode.APP_PASSWORD, MailSetup.modeAfterTyping("x@gma", MailMode.APP_PASSWORD, picked = false))
        assertEquals(MailMode.APP_PASSWORD, MailSetup.modeAfterTyping("x@asso.ch", MailMode.APP_PASSWORD, picked = true))
    }
}
