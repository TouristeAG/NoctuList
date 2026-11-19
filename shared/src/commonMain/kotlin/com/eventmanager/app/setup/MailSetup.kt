package com.eventmanager.app.setup

import com.eventmanager.app.email.SiteMailOutcome
import com.eventmanager.app.email.SiteMailRelay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** How the site's server sends mail: a Gmail app password, or a Workspace account through delegation. */
enum class MailMode(val param: String) { APP_PASSWORD("app_password"), WORKSPACE("workspace") }

/** The service account the site's functions send Workspace mail with, as the admin console needs it. */
data class MailServiceAccountInfo(val email: String, val clientId: String)

object MailSetup {
    /** The only scope the delegation needs. */
    const val GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send"
    const val DELEGATION_CONSOLE = "https://admin.google.com/ac/owl/domainwidedelegation"

    private val CONSUMER_DOMAINS = setOf("gmail.com", "googlemail.com")

    /** A consumer Gmail address can only use an app password; any other domain is taken as Workspace. */
    fun suggestedMode(address: String): MailMode {
        val domain = address.trim().substringAfterLast('@', "").lowercase()
        return if (domain in CONSUMER_DOMAINS) MailMode.APP_PASSWORD else MailMode.WORKSPACE
    }

    /**
     * The mode once the admin stopped typing [sender] (the UI waits before calling this): a valid address
     * suggests its mode, unless the admin [picked] one by hand.
     */
    fun modeAfterTyping(sender: String, current: MailMode, picked: Boolean): MailMode =
        if (picked || !SetupValidators.gmail(sender.trim())) current else suggestedMode(sender)

    /** The displayed address follows the sending account while it is blank or still the previous one. */
    fun followSender(previousSender: String, sender: String, from: String): String =
        if (from.isBlank() || from.equals(previousSender, ignoreCase = true)) sender else from

    /** The catalogue error for a `failure` code the server wrote on a refused test mail. */
    fun failureToError(code: String): SetupErrorId = when (code.trim()) {
        "delegation:unauthorized_client", "delegation:access_denied" -> SetupErrorId.MAIL_DELEGATION_MISSING
        "smtp:535", "smtp:534" -> SetupErrorId.MAIL_AUTH_REFUSED
        else -> SetupErrorId.MAIL_TEST_FAILED
    }
}

/** Sends the setup's test mail through the site (its outbox and Cloud Function). */
fun interface MailTester {
    suspend fun sendTest(to: String): SiteMailOutcome
}

/** The production [MailTester]: the outbox the app already uses, Unavailable when the device has none. */
class SiteMailRelayTester(
    private val language: () -> String,
    private val clock: () -> Long,
) : MailTester {
    override suspend fun sendTest(to: String): SiteMailOutcome =
        SiteMailRelay.sendWithFailure(to) { fromName -> TestMailMessage.build(to, fromName, clock(), language()) }
}

/** The short plain-text message sent at the end of the server step. */
@OptIn(ExperimentalEncodingApi::class)
object TestMailMessage {
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]+")
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** French unless the app is in English. The server rewrites From, To and Date with its own values. */
    fun build(to: String, fromName: String, nowMillis: Long, language: String = "fr"): String {
        val english = language.lowercase().startsWith("en")
        val subject = if (english) "NoctuList: test e-mail" else "NoctuList : e-mail de test"
        val body = if (english) "This e-mail confirms that the site can send messages."
        else "Cet e-mail confirme que le site peut envoyer des messages."
        val name = header(fromName).ifBlank { "NoctuList" }
        return buildString {
            append("From: ${displayName(name)} <noreply@eventmanager.app>\r\n")
            append("To: ${header(to)}\r\n")
            append("Subject: ${encodedWord(subject)}\r\n")
            append("Date: ${rfc5322Date(nowMillis)}\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("\r\n")
            Base64.encode("$body\r\n".encodeToByteArray()).chunked(76).forEach { append(it).append("\r\n") }
        }
    }

    private fun header(value: String) = value.replace(CONTROL, " ").trim()

    private fun encodedWord(value: String): String =
        if (value.all { it.code in 0x20..0x7E }) value else "=?UTF-8?B?${Base64.encode(value.encodeToByteArray())}?="

    private fun displayName(value: String): String =
        if (value.all { it.code in 0x20..0x7E }) "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else encodedWord(value)

    private fun rfc5322Date(millis: Long): String {
        val t = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${DAYS[t.dayOfWeek.ordinal]}, ${two(t.dayOfMonth)} ${MONTHS[t.monthNumber - 1]} ${t.year} " +
            "${two(t.hour)}:${two(t.minute)}:${two(t.second)} +0000"
    }
}
