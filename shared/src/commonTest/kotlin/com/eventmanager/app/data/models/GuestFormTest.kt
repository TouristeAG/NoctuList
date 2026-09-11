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
        assertEquals(listOf(0, 0), decoded.map { it.maxRequests })
    }

    @Test
    fun offeredAccesses_roundTripWithMaxRequests() {
        val accesses = listOf(
            VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage"),
            VenueAccess(id = "a2", venueName = "GROOVE", name = "VIP"),
        )
        val encoded = GuestFormOfferedAccessCodec.encode(
            accesses,
            mapOf("a1" to 5, "a2" to 3),
            maxGuests = 10,
        )
        val decoded = GuestFormOfferedAccessCodec.decode(encoded)
        assertEquals(listOf(5, 3), decoded.map { it.maxRequests })
        assertTrue(encoded.contains("\"maxRequests\":5"))
    }

    @Test
    fun offeredAccesses_omitsUnlimitedMaxRequests() {
        val encoded = GuestFormOfferedAccessCodec.encode(
            listOf(VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage")),
        )
        assertTrue(!encoded.contains("maxRequests"))
        assertEquals(0, GuestFormOfferedAccessCodec.decode(encoded).single().maxRequests)
    }

    @Test
    fun offeredAccesses_legacyJsonWithoutMaxRequestsIsUnlimited() {
        val decoded = GuestFormOfferedAccessCodec.decode("""[{"id":"a1","name":"Backstage"}]""")
        assertEquals(0, decoded.single().maxRequests)
    }

    @Test
    fun offeredAccesses_clampsMaxRequestsToMaxGuests() {
        val encoded = GuestFormOfferedAccessCodec.encode(
            listOf(VenueAccess(id = "a1", venueName = "GROOVE", name = "Backstage")),
            mapOf("a1" to 999),
            maxGuests = 10,
        )
        assertEquals(10, GuestFormOfferedAccessCodec.decode(encoded).single().maxRequests)
    }

    @Test
    fun accessQuota_flagsOverflowAndIgnoresUnlimited() {
        val offered = listOf(
            GuestFormOfferedAccess(id = "a1", name = "Backstage", maxRequests = 5),
            GuestFormOfferedAccess(id = "a2", name = "VIP", maxRequests = 0),
        )
        val over = GuestFormSubmission(
            people = List(6) { GuestFormPerson("P$it", setOf("a1")) },
        )
        assertEquals(listOf("a1"), over.accessQuotaViolations(offered).map { it.id })

        val atCap = GuestFormSubmission(
            people = List(5) { GuestFormPerson("P$it", setOf("a1")) },
        )
        assertTrue(atCap.accessQuotaViolations(offered).isEmpty())

        val unlimited = GuestFormSubmission(
            people = List(6) { GuestFormPerson("P$it", setOf("a2")) },
        )
        assertTrue(unlimited.accessQuotaViolations(offered).isEmpty())
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
    fun wouldBeClosedOnPublicPage_whenExpiryAlreadyPast() {
        val now = 1_700_000_000_000L
        val open = GuestForm(formId = "f1", status = GuestFormStatus.OPEN.name, expiresAtMillis = now + 1)
        val past = GuestForm(formId = "f2", status = GuestFormStatus.OPEN.name, expiresAtMillis = now)
        assertTrue(!open.wouldBeClosedOnPublicPage(now))
        assertTrue(past.wouldBeClosedOnPublicPage(now))
    }

    @Test
    fun submission_toManualEntries_keepsAccesses() {
        val entries = GuestFormSubmission(
            people = listOf(GuestFormPerson("Ada", setOf("a1", "a2"))),
        ).toManualEntries()
        assertEquals("Ada", entries.single().name)
        assertEquals(setOf("a1", "a2"), entries.single().accessIds)
    }

    @Test
    fun fieldLabels_roundTrip() {
        val encoded = GuestFormFieldLabelsCodec.encode(
            mapOf(
                GuestFormFieldLabelsCodec.KEY_COMMENTS to "Envie de quelque chose à manger",
                GuestFormFieldLabelsCodec.KEY_ACCESS_LABEL to "Autres demandes",
            ),
        )
        val decoded = GuestFormFieldLabelsCodec.decode(encoded)
        assertEquals("Envie de quelque chose à manger", decoded[GuestFormFieldLabelsCodec.KEY_COMMENTS])
        assertEquals("Autres demandes", decoded[GuestFormFieldLabelsCodec.KEY_ACCESS_LABEL])
        assertEquals(2, decoded.size)
    }

    @Test
    fun fieldLabels_emptyAndBlankAreDropped() {
        assertEquals("", GuestFormFieldLabelsCodec.encode(emptyMap()))
        assertEquals("", GuestFormFieldLabelsCodec.encode(mapOf(GuestFormFieldLabelsCodec.KEY_COMMENTS to "  ")))
        assertEquals(emptyMap(), GuestFormFieldLabelsCodec.decode(""))
        assertEquals(emptyMap(), GuestFormFieldLabelsCodec.decode("{}"))
        assertEquals(emptyMap(), GuestFormFieldLabelsCodec.decode("not json"))
    }

    @Test
    fun fieldLabels_unknownKeysIgnoredAndValuesTrimmed() {
        val encoded = GuestFormFieldLabelsCodec.encode(
            mapOf(
                "submit" to "Nope",
                GuestFormFieldLabelsCodec.KEY_EMAIL to "  Contact resto  ",
            ),
        )
        val decoded = GuestFormFieldLabelsCodec.decode(encoded)
        assertEquals(mapOf(GuestFormFieldLabelsCodec.KEY_EMAIL to "Contact resto"), decoded)
        assertTrue("submit" !in decoded)
    }

    @Test
    fun fieldLabels_capsLength() {
        val tooLong = "x".repeat(GuestFormFieldLabelsCodec.MAX_LABEL_LENGTH + 20)
        val decoded = GuestFormFieldLabelsCodec.decode(
            GuestFormFieldLabelsCodec.encode(mapOf(GuestFormFieldLabelsCodec.KEY_PHONE to tooLong)),
        )
        assertEquals(GuestFormFieldLabelsCodec.MAX_LABEL_LENGTH, decoded.getValue(GuestFormFieldLabelsCodec.KEY_PHONE).length)
    }

    @Test
    fun fieldLabels_disclaimerAllowsLongerText() {
        val text = "Les accès cochés sont une demande uniquement. Ils ne sont jamais garantis — une confirmation sera donnée le soir, selon le contexte de la soirée."
        assertTrue(text.length > GuestFormFieldLabelsCodec.MAX_LABEL_LENGTH)
        val decoded = GuestFormFieldLabelsCodec.decode(
            GuestFormFieldLabelsCodec.encode(mapOf(GuestFormFieldLabelsCodec.KEY_DISCLAIMER to text)),
        )
        assertEquals(text, decoded[GuestFormFieldLabelsCodec.KEY_DISCLAIMER])
    }

    @Test
    fun logo_emptyStaysEmpty() {
        assertEquals("", GuestFormLogoCodec.fitForFirestore(""))
        assertEquals("", GuestFormLogoCodec.fitForFirestore("   "))
    }

    @Test
    fun logo_smallPayloadPassesThrough() {
        val raw = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        assertEquals("data:image/png;base64,$raw", GuestFormLogoCodec.fitForFirestore(raw))
        val uri = "data:image/png;base64,$raw"
        assertEquals(uri, GuestFormLogoCodec.fitForFirestore(uri))
    }

    @Test
    fun logo_oversizedUndecodablePayloadIsDropped() {
        val huge = "x".repeat(GuestFormLogoCodec.MAX_DATA_URI_CHARS + 20)
        assertEquals("", GuestFormLogoCodec.fitForFirestore(huge))
    }

    @Test
    fun recentlyClosed_usesReviewedAtForDecidedForms() {
        val now = 2_000_000_000_000L
        val window = GuestForm.RECENTLY_CLOSED_WINDOW_MS
        val accepted = GuestForm(
            formId = "a1",
            status = GuestFormStatus.ACCEPTED.name,
            reviewedAt = now - 1_000L,
            lastModified = now - window * 2,
        )
        assertTrue(accepted.isListedAsRecentlyClosed(now))
        assertEquals(now - 1_000L, accepted.closedAtMillis())

        val stale = accepted.copy(reviewedAt = now - window - 1L)
        assertTrue(!stale.isListedAsRecentlyClosed(now))
    }

    @Test
    fun recentlyClosed_excludesPendingAndOpen() {
        val now = 2_000_000_000_000L
        val pending = GuestForm(
            formId = "p1",
            status = GuestFormStatus.PENDING_REVIEW.name,
            lastModified = now,
            submittedAt = now,
        )
        val open = GuestForm(
            formId = "o1",
            status = GuestFormStatus.OPEN.name,
            expiresAtMillis = now + 10_000L,
        )
        assertTrue(!pending.isListedAsRecentlyClosed(now))
        assertTrue(pending.isListedAsPending())
        assertTrue(!open.isListedAsRecentlyClosed(now))
        assertTrue(open.isListedAsOpen(now))
    }

    @Test
    fun recentlyClosed_expiredUsesLastModified() {
        val now = 2_000_000_000_000L
        val expired = GuestForm(
            formId = "e1",
            status = GuestFormStatus.EXPIRED.name,
            lastModified = now - 3_600_000L,
        )
        assertEquals(now - 3_600_000L, expired.closedAtMillis())
        assertTrue(expired.isListedAsRecentlyClosed(now))
    }

    @Test
    fun listedAsOpen_excludesResponseChildrenAndExpired() {
        val now = 2_000_000_000_000L
        val child = GuestForm(
            formId = "c1",
            status = GuestFormStatus.OPEN.name,
            parentFormId = "parent",
            expiresAtMillis = now + 1L,
        )
        val expiredOpen = GuestForm(
            formId = "x1",
            status = GuestFormStatus.OPEN.name,
            expiresAtMillis = now - 1L,
        )
        assertTrue(!child.isListedAsOpen(now))
        assertTrue(!expiredOpen.isListedAsOpen(now))
        assertTrue(expiredOpen.isListedAsRecentlyClosed(now))
    }

    @Test
    fun shouldIgnoreRemoteOpen_neverResurrectsDecidedOrPending() {
        assertTrue(shouldIgnoreRemoteOpenGuestForm(GuestFormStatus.PENDING_REVIEW, "OPEN"))
        assertTrue(shouldIgnoreRemoteOpenGuestForm(GuestFormStatus.ACCEPTED, "OPEN"))
        assertTrue(shouldIgnoreRemoteOpenGuestForm(GuestFormStatus.REJECTED, "OPEN"))
        assertTrue(shouldIgnoreRemoteOpenGuestForm(GuestFormStatus.EXPIRED, "OPEN"))
        assertTrue(!shouldIgnoreRemoteOpenGuestForm(GuestFormStatus.OPEN, "OPEN"))
        assertTrue(!shouldIgnoreRemoteOpenGuestForm(GuestFormStatus.PENDING_REVIEW, "PENDING_REVIEW"))
    }

    @Test
    fun remoteStatusAlreadyMatches_skipsEnqueueWhenServerHasAttemptedStatus() {
        assertTrue(guestFormRemoteStatusAlreadyMatches("EXPIRED", "EXPIRED"))
        assertTrue(guestFormRemoteStatusAlreadyMatches("expired", "EXPIRED"))
        assertTrue(!guestFormRemoteStatusAlreadyMatches("EXPIRED", "OPEN"))
        assertTrue(!guestFormRemoteStatusAlreadyMatches("EXPIRED", null))
        assertTrue(!guestFormRemoteStatusAlreadyMatches("", "EXPIRED"))
    }
}
