package com.eventmanager.app.email

import com.eventmanager.app.data.remote.NoOpFirestoreGateway
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiteMailOutboxTest {
    private class FakeGateway(private val answer: (Int) -> Map<String, Any?>?) : NoOpFirestoreGateway() {
        val written = mutableMapOf<String, Map<String, Any?>>()
        val deleted = mutableListOf<String>()
        var reads = 0
        var failWrite = false

        override fun isAvailable(): Boolean = true

        override suspend fun setPlainDocument(
            orgId: String,
            collection: String,
            docId: String,
            data: Map<String, Any?>,
            merge: Boolean,
        ) {
            if (failWrite) error("denied")
            assertEquals("org1", orgId)
            assertEquals(SiteMailOutbox.COLLECTION, collection)
            assertEquals(false, merge)
            written[docId] = data
        }

        override suspend fun getDocumentFromServer(orgId: String, collection: String, docId: String): Map<String, Any?>? {
            reads += 1
            return answer(reads)
        }

        override suspend fun deleteDocument(orgId: String, collection: String, docId: String) {
            deleted += docId
        }
    }

    private var now = 1_000L

    private fun outbox(gateway: FakeGateway) = SiteMailOutbox(
        gateway = gateway,
        orgId = "org1",
        uid = "admin1",
        clock = { now },
        pause = { now += it },
        newId = { "mail1" },
    )

    @Test
    fun handsOverTheDocumentTheRulesExpectAndReportsSent() = runBlocking {
        val gateway = FakeGateway { if (it < 3) mapOf("status" to "PROCESSING") else mapOf("status" to "DONE") }
        val outcome = outbox(gateway).send(" lea@mail.example ", "From: x\r\n\r\nbody", " Asso ")
        assertEquals(SiteMailOutcome(SiteMailResult.Sent, ""), outcome)
        val doc = gateway.written.getValue("mail1")
        assertEquals(
            setOf("schema", "status", "to", "raw", "fromName", "createdBy", "createdAt"),
            doc.keys,
        )
        assertEquals(1, doc["schema"])
        assertEquals("PENDING", doc["status"])
        assertEquals("lea@mail.example", doc["to"])
        assertEquals("Asso", doc["fromName"])
        assertEquals("admin1", doc["createdBy"])
        assertEquals(listOf("mail1"), gateway.deleted)
    }

    @Test
    fun rejectedAnswerIsReportedAndCleanedUp() = runBlocking {
        val gateway = FakeGateway { mapOf("status" to "REJECTED", "reason" to "SEND_FAILED") }
        assertEquals(SiteMailResult.Rejected, outbox(gateway).send("lea@mail.example", "m", "A").result)
        assertEquals(listOf("mail1"), gateway.deleted)
    }

    @Test
    fun rejectedAnswerCarriesTheFailureCode() = runBlocking {
        val gateway = FakeGateway { mapOf("status" to "REJECTED", "reason" to "SEND_FAILED", "failure" to "delegation:unauthorized_client") }
        assertEquals(
            SiteMailOutcome(SiteMailResult.Rejected, "delegation:unauthorized_client"),
            outbox(gateway).send("lea@mail.example", "m", "A"),
        )
    }

    @Test
    fun relaySendKeepsAnsweringAResult() = runBlocking {
        val gateway = FakeGateway { mapOf("status" to "REJECTED", "failure" to "smtp:535") }
        SiteMailRelay.install { SiteMailRelay.Target(outbox(gateway), "Asso") }
        try {
            assertEquals(SiteMailResult.Rejected, SiteMailRelay.send("lea@mail.example", "m"))
            var name = ""
            val outcome = SiteMailRelay.sendWithFailure("lea@mail.example") { name = it; "m" }
            assertEquals(SiteMailOutcome(SiteMailResult.Rejected, "smtp:535"), outcome)
            assertEquals("Asso", name)
        } finally {
            SiteMailRelay.install(null)
        }
    }

    @Test
    fun noAnswerTakesBackAnUnclaimedMail() = runBlocking {
        val gateway = FakeGateway { mapOf("status" to "PENDING") }
        assertEquals(SiteMailResult.NoAnswer, outbox(gateway).send("lea@mail.example", "m", "A").result)
        assertEquals(listOf("mail1"), gateway.deleted)
        assertTrue(gateway.reads >= 2)
    }

    @Test
    fun noAnswerLeavesAClaimedMailToTheServer() = runBlocking {
        val gateway = FakeGateway { mapOf("status" to "PROCESSING") }
        assertEquals(SiteMailResult.NoAnswer, outbox(gateway).send("lea@mail.example", "m", "A").result)
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun oversizedMessageIsNotWritten() = runBlocking {
        val gateway = FakeGateway { null }
        val big = "x".repeat(SiteMailOutbox.MAX_RAW_LENGTH + 1)
        assertEquals(SiteMailResult.TooLarge, outbox(gateway).send("lea@mail.example", big, "A").result)
        assertTrue(gateway.written.isEmpty())
    }

    @Test
    fun deniedWriteMeansUnavailable() = runBlocking {
        val gateway = FakeGateway { null }.apply { failWrite = true }
        assertEquals(SiteMailResult.Unavailable, outbox(gateway).send("lea@mail.example", "m", "A").result)
    }

    @Test
    fun relayWithoutTargetIsUnavailable() = runBlocking {
        SiteMailRelay.install(null)
        assertEquals(false, SiteMailRelay.isAvailable())
        assertEquals(SiteMailResult.Unavailable, SiteMailRelay.send("lea@mail.example", "m"))
        assertEquals(SiteMailOutcome(SiteMailResult.Unavailable, ""), SiteMailRelay.sendWithFailure("lea@mail.example") { "m" })
    }
}
