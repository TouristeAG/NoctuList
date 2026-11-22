package com.eventmanager.app.portal.certificate

import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.PortalInputValidator
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CertificateReviewTest {
    private val now = 1_800_000_000_000L
    private val uid1 = "PortalUid000000000000001"
    private val uid2 = "PortalUid000000000000002"

    private fun doc(uid: String, status: String, requestedAt: Long, extra: Map<String, Any?> = emptyMap()) =
        "${uid}_CERTIFICATE_EMAIL" to (mapOf<String, Any?>(
            "uid" to uid, "type" to "CERTIFICATE_EMAIL", "status" to status, "email" to "lea@example.org",
            "from" to "2026-01-01", "to" to "2026-12-31", "jobTypes" to listOf("Bar"), "requestedAt" to requestedAt,
        ) + extra)

    @Test
    fun listsRequestsInReviewOldestFirst() {
        val docs = listOf(
            doc(uid1, "PENDING_REVIEW", now - 1_000),
            doc(uid2, "APPROVED", now - 9_000, mapOf("approvedAt" to now - 1_000, "approvalId" to "a1")),
            doc("PortalUid000000000000003", "PENDING", now - 20_000),
            doc("PortalUid000000000000004", "DONE", now - 30_000),
        )
        val list = certificateReviewRequests(docs, now)
        assertEquals(listOf(uid2, uid1), list.map { it.portalUid })
        assertEquals(20260101, list[0].fromDay)
        assertEquals(20261231, list[0].toDay)
        assertEquals(listOf("Bar"), list[0].jobTypes)
        assertTrue(list.none { it.stuck })
    }

    @Test
    fun staleApprovalIsMarkedStuck() {
        val list = certificateReviewRequests(
            listOf(
                doc(uid1, "APPROVED", now - 900_000, mapOf("approvedAt" to now - 6 * 60_000L)),
                doc(uid2, "PROCESSING", now - 900_000, mapOf("processingAt" to now - 60_000L, "reason" to "SEND_FAILED")),
            ),
            now,
        )
        assertTrue(list.first { it.portalUid == uid1 }.stuck)
        assertTrue(!list.first { it.portalUid == uid2 }.stuck)
        assertEquals("SEND_FAILED", list.first { it.portalUid == uid2 }.lastReason)
    }

    @Test
    fun parserAcceptsReviewFields() {
        val data = doc(
            uid1, "PENDING_REVIEW", now,
            mapOf("reviewAt" to now, "processedAt" to now, "reason" to "SEND_FAILED", "approvalId" to "x", "approvedAt" to now, "note" to "n"),
        ).second
        assertNotNull(PortalInputValidator.parseRequest("${uid1}_CERTIFICATE_EMAIL", data))
        val extra = data + ("surprise" to 1)
        assertEquals(null, PortalInputValidator.parseRequest("${uid1}_CERTIFICATE_EMAIL", extra))
    }

    private val volunteer = Volunteer(
        id = "V1", name = "Léa", lastNameAbbreviation = "Martin", email = "lea@example.org",
        phoneNumber = "", gender = Gender.FEMALE,
    )

    private fun job(volunteerId: String, type: String, day: LocalDate) = Job(
        volunteerId = volunteerId, jobType = JobType.BAR, jobTypeName = type, venueName = "V",
        date = day.atTime(12, 0).atZone(ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli(), shiftTime = ShiftTime.BEFORE_MIDNIGHT,
    )

    @Test
    fun draftUsesTheRequestSelection() {
        val today = LocalDate.of(2026, 6, 15)
        val nowMs = today.atTime(12, 0).atZone(ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        val template = CertificateDefaults.template("fr", "Asso")
        val configs = listOf(JobTypeConfig(name = "Bar", description = ""), JobTypeConfig(name = "Door", description = ""))
        val jobs = listOf(
            job("V1", "Bar", LocalDate.of(2026, 2, 1)),
            job("V1", "Bar", LocalDate.of(2025, 2, 1)),
            job("V1", "Door", LocalDate.of(2026, 3, 1)),
            job("V1", "Bar", LocalDate.of(2026, 9, 1)),
            job("V2", "Bar", LocalDate.of(2026, 4, 1)),
        )
        val request = certificateReviewRequests(listOf(doc(uid1, "PENDING_REVIEW", nowMs)), nowMs).single()
        val draft = assertNotNull(certificateDraft(template, "Asso", volunteer, jobs, configs, request, nowMs))
        val text = draft.body.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }
        assertTrue("1er février 2026" in text, text)
        assertTrue("2025" !in text, text)
        assertTrue("septembre" !in text, text)
        assertTrue("mars" !in text, "Door is not requested: $text")
        assertTrue("avril" !in text, "another volunteer's job: $text")
        assertEquals(null, certificateDraft(template, "Asso", volunteer, emptyList(), configs, request, nowMs))
    }

    @Test
    fun directDraftTakesAnySelection() {
        val template = CertificateDefaults.template("fr", "Asso")
        val configs = listOf(JobTypeConfig(name = "Door", description = ""))
        val nowMs = LocalDate.of(2026, 6, 15).atTime(12, 0).atZone(ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        val jobs = listOf(job("V1", "Door", LocalDate.of(2026, 3, 1)))
        assertNotNull(directDraft(template, "Asso", volunteer, jobs, configs, 20260101, 20261231, setOf("Door"), nowMs))
        assertEquals(null, directDraft(template, "Asso", volunteer, jobs, configs, 20260101, 20261231, setOf("Bar"), nowMs))
    }
}
