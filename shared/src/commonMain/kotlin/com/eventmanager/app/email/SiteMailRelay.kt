package com.eventmanager.app.email

import com.eventmanager.app.data.remote.FirestoreGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/** How a mail handed to the site ended. */
enum class SiteMailResult {
    /** The site's server sent it with the association's Gmail. */
    Sent,
    /** The server answered but refused it (bad address, Gmail error). */
    Rejected,
    /** No answer in time: the server is not deployed, asleep too long, or offline. */
    NoAnswer,
    /** The message is over the Firestore document limit (large logo or wallet pass). */
    TooLarge,
    /** No organization, signed-in team account or site on this device. */
    Unavailable,
}

/**
 * Sends a finished QR e-mail through the site when the device has no Gmail account of its own:
 * the MIME message the app would give the Gmail API goes to `orgs/{orgId}/mailOutbox/{id}`,
 * the portal Cloud Function (onMailOutboxWritten) sends it with the association's Gmail and
 * answers DONE or REJECTED. Gmail on the device always comes first; this is only the fallback.
 */
class SiteMailOutbox(
    private val gateway: FirestoreGateway,
    private val orgId: String,
    private val uid: String,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val newId: () -> String = ::randomMailId,
) {
    suspend fun send(to: String, mimeMessage: String, fromName: String): SiteMailResult {
        val recipient = to.trim()
        if (recipient.isEmpty() || mimeMessage.isEmpty()) return SiteMailResult.Rejected
        if (mimeMessage.length > MAX_RAW_LENGTH) return SiteMailResult.TooLarge
        val id = newId()
        try {
            gateway.setPlainDocument(
                orgId,
                COLLECTION,
                id,
                mapOf(
                    "schema" to SCHEMA,
                    "status" to "PENDING",
                    "to" to recipient.take(254),
                    "raw" to mimeMessage,
                    "fromName" to fromName.trim().take(80),
                    "createdBy" to uid,
                    "createdAt" to clock(),
                ),
                merge = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Site mail: hand-over failed: ${e.message}")
            return SiteMailResult.Unavailable
        }
        val deadline = clock() + ANSWER_TIMEOUT_MS
        var lastStatus = "PENDING"
        while (clock() < deadline) {
            pause(POLL_INTERVAL_MS)
            val data = try {
                gateway.getDocumentFromServer(orgId, COLLECTION, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            lastStatus = data["status"]?.toString().orEmpty()
            when (lastStatus) {
                "DONE" -> return SiteMailResult.Sent.also { forget(id) }
                "REJECTED" -> return SiteMailResult.Rejected.also { forget(id) }
            }
        }
        // Never claimed: take it back so a server that wakes up later does not send a stale mail.
        // A claimed one is left to finish; the server drops the message from it once answered.
        if (lastStatus == "PENDING") forget(id)
        return SiteMailResult.NoAnswer
    }

    private suspend fun forget(id: String) {
        try {
            gateway.deleteDocument(orgId, COLLECTION, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    companion object {
        const val COLLECTION = "mailOutbox"
        const val SCHEMA = 1
        /** Same bound as the rules and the function (MAX_RAW_LENGTH). */
        const val MAX_RAW_LENGTH = 900_000
        /** Room for a cold start of the function plus the SMTP exchange. */
        const val ANSWER_TIMEOUT_MS = 60_000L
        const val POLL_INTERVAL_MS = 2_000L
    }
}

private const val ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

private fun randomMailId(): String = buildString { repeat(24) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) } }

/**
 * Process-wide access for the send dialogs, which have no view model. The view model installs a
 * provider that answers null whenever the site cannot be used (no Firebase organization, portal
 * off, no signed-in team account).
 */
object SiteMailRelay {
    class Target(val outbox: SiteMailOutbox, val fromName: String)

    private var provider: (() -> Target?)? = null

    fun install(provider: (() -> Target?)?) {
        this.provider = provider
    }

    private fun target(): Target? = runCatching { provider?.invoke() }.getOrNull()

    /** True when a QR e-mail can go through the site from this device. */
    fun isAvailable(): Boolean = target() != null

    suspend fun send(to: String, mimeMessage: String): SiteMailResult {
        val target = target() ?: return SiteMailResult.Unavailable
        return target.outbox.send(to, mimeMessage, target.fromName)
    }
}
