package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormPerson
import com.eventmanager.app.data.models.GuestFormRequestKind
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.GuestFormSubmission
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestListReviewsTest {
    private val day = 86_400_000L
    private val event = 1_800_000_000_000L
    private val grant = GuestListGrant(id = "G1", eventDateMillis = event)

    private fun submission(email: String = "a@b.ch", phone: String = "+41 79 1", notes: String = "n") = GuestFormSubmissionCodec.encode(
        GuestFormSubmission(
            contactEmail = email, emergencyPhone = phone, notes = notes,
            people = listOf(GuestFormPerson("Ann Lee", setOf("bs", "vip")), GuestFormPerson("Bob", emptySet())),
        ),
    )

    private fun form(
        id: String,
        status: GuestFormStatus = GuestFormStatus.PENDING_REVIEW,
        date: Long = event,
        venue: String = "",
        org: String = "",
        portalUid: String = "",
        kind: GuestFormRequestKind = GuestFormRequestKind.ADD,
        submittedAt: Long = 10L,
        parent: String = "",
        removeKeys: String = "",
        sub: String = submission(),
    ) = GuestForm(
        formId = id, firebaseOrgId = org, venueName = venue, eventName = "Band", eventDateMillis = date,
        status = status.name, submissionJson = sub, submittedAt = submittedAt, portalUid = portalUid,
        requestKind = kind.name, parentFormId = parent, removeGuestKeys = removeKeys,
    )

    @Test
    fun coversSameDayOnly() {
        val out = GuestListReviews.covered(grant, listOf(form("a"), form("b", date = event + 2 * day)), "")
        assertEquals(listOf("a"), out.map { it.formId })
    }

    @Test
    fun venueGrantHidesOtherVenue() {
        val g = grant.copy(venueName = "Groove")
        val forms = listOf(form("a", venue = "Groove"), form("b", venue = "Terreau"), form("c", venue = "BOTH"), form("d", venue = ""))
        assertEquals(listOf("a", "c", "d"), GuestListReviews.covered(g, forms, "").map { it.formId }.sorted())
        assertEquals(listOf("a", "b", "c", "d"), GuestListReviews.covered(grant, forms, "").map { it.formId }.sorted())
    }

    @Test
    fun onlyPendingReview() {
        val forms = GuestFormStatus.values().map { form(it.name, status = it) }
        assertEquals(listOf("PENDING_REVIEW"), GuestListReviews.covered(grant, forms, "").map { it.formId })
    }

    @Test
    fun orgFilter() {
        val forms = listOf(form("a", org = "o1"), form("b", org = "o2"), form("c", org = ""))
        assertEquals(listOf("a", "c"), GuestListReviews.covered(grant, forms, "o1").map { it.formId }.sorted())
        assertEquals(3, GuestListReviews.covered(grant, forms, "").size)
    }

    @Test
    fun sortedBySubmittedAt() {
        val forms = listOf(form("late", submittedAt = 30L), form("early", submittedAt = 5L))
        assertEquals(listOf("early", "late"), GuestListReviews.covered(grant, forms, "").map { it.formId })
    }

    @Test
    fun addCarriesWholeRequest() {
        val j = GuestListReviews.toJson(form("a", venue = "Groove"), emptyList(), emptyList(), emptyMap())
        assertEquals("FORM", j.getValue("source").jsonPrimitive.content)
        assertEquals("ADD", j.getValue("kind").jsonPrimitive.content)
        assertEquals("Band", j.getValue("listName").jsonPrimitive.content)
        assertEquals("Groove", j.getValue("venueName").jsonPrimitive.content)
        assertEquals(event, j.getValue("eventDate").jsonPrimitive.content.toLong())
        assertEquals(10L, j.getValue("submittedAt").jsonPrimitive.content.toLong())
        assertEquals("a@b.ch", j.getValue("contactEmail").jsonPrimitive.content)
        assertEquals("+41 79 1", j.getValue("emergencyPhone").jsonPrimitive.content)
        assertEquals("n", j.getValue("notes").jsonPrimitive.content)
        val people = j.getValue("people").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Ann Lee", "Bob"), people.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("bs", "vip"), people[0].getValue("accessIds").jsonArray.map { it.jsonPrimitive.content }.sorted())
        assertEquals("", j.getValue("requestedBy").jsonPrimitive.content)
    }

    @Test
    fun portalAddUsesTemplateContact() {
        val template = form("T", status = GuestFormStatus.OPEN, portalUid = "u1").copy(prefillEmail = "t@x.ch", prefillPhone = "+41 1")
        val answer = form("R", portalUid = "u1", parent = "T", sub = submission(email = "", phone = ""))
        val j = GuestListReviews.toJson(answer, listOf(template, answer), emptyList(), mapOf("u1" to "Marie Dupont"))
        assertEquals("PORTAL", j.getValue("source").jsonPrimitive.content)
        assertEquals("t@x.ch", j.getValue("contactEmail").jsonPrimitive.content)
        assertEquals("+41 1", j.getValue("emergencyPhone").jsonPrimitive.content)
        assertEquals("Marie Dupont", j.getValue("requestedBy").jsonPrimitive.content)
    }

    @Test
    fun removeListsTargetedGuests() {
        val a = Guest(nanoId = "AAAAAAAAAAAAAAAAAAAAA", name = "Ann", lastNameAbbreviation = "L", invitations = 0, venueName = "Main")
        val b = Guest(nanoId = "BBBBBBBBBBBBBBBBBBBBB", name = "Bob", lastNameAbbreviation = "", invitations = 0, venueName = "Main")
        val keys = TemporaryGuestPortalQrDocument.guestKey(a)
        val f = form("r", portalUid = "u1", kind = GuestFormRequestKind.REMOVE, removeKeys = keys, sub = "")
        val j = GuestListReviews.toJson(f, listOf(f), listOf(a, b), emptyMap())
        assertEquals("REMOVE", j.getValue("kind").jsonPrimitive.content)
        val rg = j.getValue("removeGuests").jsonArray.map { it.jsonObject }
        assertEquals(1, rg.size)
        assertEquals(keys, rg[0].getValue("guestKey").jsonPrimitive.content)
        assertEquals("Ann L", rg[0].getValue("name").jsonPrimitive.content)
    }

    @Test
    fun noFormIdInJson() {
        val f = form("SECRETFORMID123")
        assertFalse(GuestListReviews.toJson(f, listOf(f), emptyList(), emptyMap()).toString().contains("SECRETFORMID123"))
    }

    @Test
    fun reviewKeyIsHashOfFormId() {
        val k = GuestListReviews.reviewKey(form("abc"))
        assertEquals(16, k.length)
        assertTrue(k.all { it in "0123456789abcdef" })
        assertEquals(k, GuestListReviews.reviewKey(form("abc")))
        assertEquals(PortalProjectionBuilder.sha256("abc").take(16), k)
    }
}
