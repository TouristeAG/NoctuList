package com.eventmanager.app.data.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestFormTest {

    @Test
    fun submission_roundTrips() {
        val submission = GuestFormSubmission(
            contactEmail = "manager@nova.ch",
            emergencyPhone = "+41 79 000 00 00",
            notes = "Late arrival",
            people = listOf(
                GuestFormPerson("Ada", setOf("a1")),
                GuestFormPerson("Bo"),
            ),
        )
        assertEquals(submission, GuestFormSubmissionCodec.decode(GuestFormSubmissionCodec.encode(submission)))
    }

    @Test
    fun submission_decode_rejectsGarbage() {
        assertNull(GuestFormSubmissionCodec.decode(""))
        assertNull(GuestFormSubmissionCodec.decode("not json"))
        assertNull(GuestFormSubmissionCodec.decode("""{"people":[{"name":""}]}"""))
    }

    @Test
    fun offeredAccesses_roundTripWithNames() {
        val accesses = listOf(
            VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage"),
            VenueAccess(id = "a2", venueName = "GROOVE", name = "VIP"),
        )
        val encoded = GuestFormOfferedAccessCodec.encode(accesses)
        assertEquals(listOf("a1", "a2"), GuestFormOfferedAccessCodec.decode(encoded).map { it.id })
        assertEquals(listOf("Backstage", "VIP"), GuestFormOfferedAccessCodec.decode(encoded).map { it.name })
    }

    @Test
    fun offeredAccesses_legacyCommaIdsStillResolve() {
        val decoded = GuestFormOfferedAccessCodec.decode("a1, a2")
        assertEquals(listOf("a1", "a2"), decoded.map { it.id })
    }

    @Test
    fun expiry_landsOnEndOfChosenDay() {
        val event = 1_700_000_000_000L
        val after = resolveGuestFormExpiry(GuestFormExpiry.AFTER_RESPONSE, event)
        val day = resolveGuestFormExpiry(GuestFormExpiry.EVENT_DAY, event)
        val before = resolveGuestFormExpiry(GuestFormExpiry.DAY_BEFORE, event)
        val next = resolveGuestFormExpiry(GuestFormExpiry.DAY_AFTER, event)
        assertTrue(after > event)
        assertTrue(day > event)
        assertTrue(before < event || before > 0L)
        assertEquals(after, next)
    }

    @Test
    fun expiry_manualIsCappedAtThreeDaysAfterEvent() {
        val event = 1_700_000_000_000L
        val farFuture = event + 30L * 24 * 60 * 60 * 1000
        val capped = resolveGuestFormExpiry(GuestFormExpiry.MANUAL, event, farFuture)
        val hardCap = guestFormHardRetentionDeadlineMillis(event)
        assertEquals(hardCap, capped)
        assertTrue(capped < farFuture)
    }

    @Test
    fun hardRetention_isThreeDaysAfterEvent() {
        val event = 1_700_000_000_000L
        val deadline = guestFormHardRetentionDeadlineMillis(event)
        assertTrue(deadline > event)
        val form = GuestForm(
            formId = "f1",
            eventDateMillis = event,
            expiresAtMillis = deadline,
        )
        assertTrue(!form.isPastHardRetention(deadline))
        assertTrue(form.isPastHardRetention(deadline + 1L))
    }

    @Test
    fun submission_toManualEntries_keepsAccesses() {
        val entries = GuestFormSubmission(
            people = listOf(GuestFormPerson("Ada", setOf("a1", "a2"))),
        ).toManualEntries()
        assertEquals("Ada", entries.single().name)
        assertEquals(setOf("a1", "a2"), entries.single().accessIds)
    }
}
